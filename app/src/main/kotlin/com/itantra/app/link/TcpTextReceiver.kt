package com.itantra.app.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

const val DEFAULT_LINK_PORT = 5000

/**
 * Phone B side of the demo link: a TCP server that accepts one client at a
 * time and appends every received UTF-8 line as a message. When a client
 * disconnects it goes back to listening; received messages are kept.
 * All socket I/O runs on [Dispatchers.IO].
 */
class TcpTextReceiver(
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_LINK_PORT,
    private val log: (String) -> Unit = {},
) {

    sealed interface Status {
        data object Stopped : Status
        data class Listening(val port: Int) : Status
        data class Connected(val peer: String) : Status
        data class Failed(val message: String) : Status
    }

    data class State(val status: Status = Status.Stopped, val messages: List<String> = emptyList())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        log("receiver started")
        job = scope.launch(Dispatchers.IO) {
            try {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(port))
                    server = ss
                    while (isActive) {
                        _state.update { it.copy(status = Status.Listening(ss.localPort)) }
                        log("receiver listening on ${ss.localPort}")
                        ss.accept().use { socket ->
                            client = socket
                            receiveLines(socket)
                            client = null
                        }
                    }
                }
            } catch (e: IOException) {
                // stop() closes the server socket to unblock accept(); that is not an error.
                if (isActive) {
                    log("receiver failed: $e")
                    _state.update { it.copy(status = Status.Failed(e.message ?: e.javaClass.simpleName)) }
                }
            } finally {
                server = null
                client = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeQuietly(client)
        closeQuietly(server)
        _state.update { it.copy(status = Status.Stopped) }
    }

    fun clearMessages() = _state.update { it.copy(messages = emptyList()) }

    private fun receiveLines(socket: Socket) {
        _state.update { it.copy(status = Status.Connected(socket.inetAddress.hostAddress ?: "?")) }
        log("receiver: client connected from ${socket.inetAddress.hostAddress}")
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        try {
            while (true) {
                val line = reader.readLine() ?: break // client closed the connection
                if (line.isNotBlank()) {
                    log("TCP received: \"$line\"")
                    _state.update { it.copy(messages = it.messages + line) }
                }
            }
            log("receiver: client closed connection")
        } catch (e: IOException) {
            log("receiver: client dropped: $e")
            // Client dropped (reset/timeout): go back to listening.
        }
    }

    private fun closeQuietly(c: AutoCloseable?) {
        try {
            c?.close()
        } catch (_: IOException) {
        }
    }
}
