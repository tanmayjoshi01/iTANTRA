package com.itantra.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
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
import java.io.Writer
import java.util.UUID

/** Fixed RFCOMM service UUID for the iTANTRA text link (both phones run the same app). */
val ITANTRA_RFCOMM_UUID: UUID = UUID.fromString("7a1c4b9e-2f3d-4e5a-9b6c-1d2e3f4a5b6c")

private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    context.getSystemService(BluetoothManager::class.java)?.adapter

/** Human-readable reason Bluetooth cannot be used right now, or null if it can. */
private fun unavailableReason(adapter: BluetoothAdapter?): String? = when {
    adapter == null -> "Bluetooth not available on this device"
    !adapter.isEnabled -> "Bluetooth is OFF"
    else -> null
}

/**
 * Phone B side over Bluetooth Classic: an RFCOMM server that accepts one paired
 * client at a time and appends each received UTF-8 line. Goes back to listening
 * when the client disconnects. Requires BLUETOOTH_CONNECT (API 31+), which the
 * activity requests; a missing permission becomes a FAILED status, not a crash.
 */
@SuppressLint("MissingPermission")
class BluetoothTextReceiver(
    context: Context,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    data class State(
        val status: String = "STOPPED",
        val running: Boolean = false,
        val failed: Boolean = false,
        val messages: List<String> = emptyList(),
    )

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var client: BluetoothSocket? = null
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val adapter = bluetoothAdapter(appContext)
        unavailableReason(adapter)?.let { reason ->
            log("bt receiver not started: $reason")
            _state.update { it.copy(status = "FAILED: $reason", failed = true, running = false) }
            return
        }
        log("bt receiver started")
        job = scope.launch(Dispatchers.IO) {
            try {
                val ss = adapter!!.listenUsingRfcommWithServiceRecord("iTANTRA", ITANTRA_RFCOMM_UUID)
                server = ss
                ss.use {
                    while (isActive) {
                        _state.update { it.copy(status = "LISTENING (Bluetooth)", running = true, failed = false) }
                        log("bt receiver listening")
                        val socket = ss.accept()
                        client = socket
                        socket.use { receive(it) }
                        client = null
                    }
                }
            } catch (e: Exception) { // IOException, or SecurityException without permission
                if (isActive) {
                    log("bt receiver failed: $e")
                    _state.update { it.copy(status = "FAILED: ${e.message ?: e.javaClass.simpleName}", failed = true, running = false) }
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
        _state.update { it.copy(status = "STOPPED", running = false, failed = false) }
        log("bt receiver stopped")
    }

    fun clearMessages() = _state.update { it.copy(messages = emptyList()) }

    private fun receive(socket: BluetoothSocket) {
        val peer = socket.remoteDevice?.name ?: socket.remoteDevice?.address ?: "?"
        _state.update { it.copy(status = "CONNECTED (from $peer)") }
        log("bt receiver accepted connection from $peer")
        try {
            TextLines.readMessages(socket.inputStream.bufferedReader(Charsets.UTF_8)) { line ->
                log("bt received: \"$line\"")
                _state.update { it.copy(messages = it.messages + line) }
            }
            log("bt receiver: peer disconnected")
        } catch (e: IOException) {
            log("bt receiver: connection lost: $e")
        }
    }

    private fun closeQuietly(c: AutoCloseable?) {
        try {
            c?.close()
        } catch (_: IOException) {
        }
    }
}

/**
 * Phone A side over Bluetooth Classic: connects to an already-paired device
 * (pairing is done in Android Settings) and sends one UTF-8 line per message on
 * one serial IO dispatcher, so order is preserved.
 */
@SuppressLint("MissingPermission")
class BluetoothTextSender(
    context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val log: (String) -> Unit = {},
) {
    data class PairedDevice(val name: String, val address: String)

    data class State(
        val status: String = "DISCONNECTED",
        val connected: Boolean = false,
        val connecting: Boolean = false,
        val failed: Boolean = false,
        val sentCount: Int = 0,
        val lastEvent: String? = null,
        val pairedDevices: List<PairedDevice> = emptyList(),
    )

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var socket: BluetoothSocket? = null
    private var writer: Writer? = null

    /** Reloads the paired-device list (no discovery). */
    fun refreshPairedDevices() {
        val adapter = bluetoothAdapter(appContext)
        val reason = unavailableReason(adapter)
        if (reason != null) {
            _state.update { it.copy(pairedDevices = emptyList(), status = "FAILED: $reason", failed = true) }
            log("bt paired list unavailable: $reason")
            return
        }
        try {
            val devices = adapter!!.bondedDevices.orEmpty()
                .map { PairedDevice(it.name ?: it.address, it.address) }
                .sortedBy { it.name }
            _state.update { it.copy(pairedDevices = devices, failed = if (it.connected) false else it.failed) }
            log("bt paired devices: ${devices.joinToString { it.name }}")
        } catch (e: SecurityException) {
            _state.update { it.copy(status = "FAILED: Bluetooth permission not granted", failed = true) }
        }
    }

    fun connect(device: PairedDevice) = scope.launch(io) {
        log("bt connect requested: ${device.name} ${device.address}")
        closeSocket()
        _state.update { it.copy(status = "CONNECTING to ${device.name}", connecting = true, connected = false, failed = false) }
        try {
            val adapter = bluetoothAdapter(appContext)
            unavailableReason(adapter)?.let { throw IOException(it) }
            val s = adapter!!.getRemoteDevice(device.address).createRfcommSocketToServiceRecord(ITANTRA_RFCOMM_UUID)
            s.connect() // blocking; throws if the peer is unreachable or not listening
            socket = s
            writer = s.outputStream.bufferedWriter(Charsets.UTF_8)
            _state.update { it.copy(status = "CONNECTED to ${device.name}", connected = true, connecting = false) }
            log("bt connection established: ${device.name}")
        } catch (e: Exception) { // IOException, or SecurityException without permission
            log("bt connect failed: $e")
            closeSocket()
            _state.update {
                it.copy(status = "FAILED: ${e.message ?: e.javaClass.simpleName}", connected = false, connecting = false, failed = true)
            }
        }
    }

    fun send(text: String) = scope.launch(io) {
        val line = TextLines.toLine(text) ?: return@launch
        log("bt send requested: \"$line\"")
        val w = writer
        if (w == null) {
            log("bt send skipped (not connected)")
            _state.update { it.copy(lastEvent = "NOT sent (not connected): $line") }
            return@launch
        }
        try {
            log("bt send started")
            w.write(line)
            w.write("\n")
            w.flush()
            _state.update { it.copy(sentCount = it.sentCount + 1, lastEvent = "Sent: $line") }
            log("bt send completed: \"$line\"")
        } catch (e: IOException) {
            log("bt send failed / connection lost: $e")
            closeSocket()
            _state.update {
                it.copy(
                    status = "FAILED: connection lost (${e.message ?: e.javaClass.simpleName})",
                    connected = false,
                    failed = true,
                    lastEvent = "NOT sent (send failed): $line",
                )
            }
        }
    }

    fun disconnect() = scope.launch(io) {
        log("bt disconnect")
        closeSocket()
        _state.update { it.copy(status = "DISCONNECTED", connected = false, connecting = false, failed = false) }
    }

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
}
