package com.itantra.app.link

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Phone A side of the demo link: a TCP client that sends each message as one
 * UTF-8 line. Everything runs on one serial IO dispatcher, so messages leave in
 * the order they were queued and never block the UI thread. Failures become
 * [Status.Failed], never exceptions.
 */
class TcpTextSender(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val log: (String) -> Unit = {},
) {

    sealed interface Status {
        data object Disconnected : Status
        data object Connecting : Status
        data class Connected(val address: String) : Status
        data class Failed(val message: String) : Status
    }

    /** [lastEvent]: the outcome of the most recent send attempt, shown on screen for the demo. */
    data class State(val status: Status = Status.Disconnected, val sentCount: Int = 0, val lastEvent: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Touched only on the serial `io` dispatcher (and by close()).
    @Volatile private var socket: Socket? = null
    private var writer: Writer? = null

    fun connect(host: String, port: Int) = scope.launch(io) {
        log("client connect requested: ${host.trim()}:$port")
        closeSocket()
        _state.update { it.copy(status = Status.Connecting) }
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host.trim(), port), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            socket = s
            writer = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
            _state.update { it.copy(status = Status.Connected("${host.trim()}:$port")) }
            log("TCP connected: ${host.trim()}:$port")
        } catch (e: Exception) {
            log("TCP connect failed: $e")
            closeSocket()
            _state.update { it.copy(status = Status.Failed("Connection failed: ${e.message ?: e.javaClass.simpleName}")) }
        }
    }

    /** Queues [text] as one line. When not connected it is not sent, and [State.lastEvent] says so. */
    fun send(text: String) = scope.launch(io) {
        val line = text.replace(Regex("[\r\n]+"), " ").trim()
        if (line.isEmpty()) return@launch
        val w = writer
        if (w == null) {
            log("TCP send skipped (not connected): \"$line\"")
            _state.update { it.copy(lastEvent = "NOT sent (not connected): $line") }
            return@launch
        }
        log("TCP send started: \"$line\"")
        try {
            w.write(line)
            w.write("\n")
            w.flush()
            _state.update { it.copy(sentCount = it.sentCount + 1, lastEvent = "Sent: $line") }
            log("TCP send completed: \"$line\"")
        } catch (e: IOException) {
            log("TCP send failed: \"$line\" $e")
            closeSocket()
            _state.update {
                it.copy(
                    status = Status.Failed("Send failed: ${e.message ?: e.javaClass.simpleName}"),
                    lastEvent = "NOT sent (send failed): $line",
                )
            }
        }
    }

    fun disconnect() = scope.launch(io) {
        log("client disconnect")
        closeSocket()
        _state.update { it.copy(status = Status.Disconnected) }
    }

    /** Immediate teardown (e.g. ViewModel cleared), without waiting for queued sends. */
    fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        writer = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
