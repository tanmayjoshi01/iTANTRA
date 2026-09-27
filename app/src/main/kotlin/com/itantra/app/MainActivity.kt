package com.itantra.app

import android.Manifest
import com.itantra.app.link.BluetoothTextSender
import com.itantra.app.link.BluetoothTextReceiver
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import android.os.Build
import android.bluetooth.BluetoothAdapter
import android.annotation.SuppressLint
import com.itantra.app.link.ReceiverTts
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import android.speech.tts.TextToSpeech
import android.content.Intent
import android.content.ActivityNotFoundException
import com.itantra.app.link.localIpv4Addresses
import com.itantra.app.link.TcpTextSender
import com.itantra.app.link.TcpTextReceiver
import com.itantra.app.link.DEFAULT_LINK_PORT
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ColumnScope
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itantra.app.speech.SpeechError
import com.itantra.app.speech.SpeechState

/**
 * Single entry point of the application. Owns the RECORD_AUDIO runtime
 * permission request (the app layer's job; speech-engine only declares the
 * permission) and renders [SpeechState]. Holds no speech logic itself.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SpeechViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = viewModel.controller
        setContent {
            val state by controller.state.collectAsState()
            val senderState by viewModel.sender.state.collectAsState()
            val receiverState by viewModel.receiver.state.collectAsState()
            val ttsState by viewModel.tts.state.collectAsState()
            val alertMode by viewModel.alertMode.collectAsState()
            val transport by viewModel.transport.collectAsState()
            val btSenderState by viewModel.btSender.state.collectAsState()
            val btReceiverState by viewModel.btReceiver.state.collectAsState()
            var pendingBtAction by remember { mutableStateOf<(() -> Unit)?>(null) }
            val btPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) pendingBtAction?.invoke()
                pendingBtAction = null
            }
            // BLUETOOTH_CONNECT is a runtime permission on Android 12+; ask only when a Bluetooth action is used.
            val withBt: (() -> Unit) -> Unit = { action ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                ) {
                    action()
                } else {
                    pendingBtAction = action
                    btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            val bt = BtUi(
                sender = btSenderState,
                receiver = btReceiverState,
                onRefresh = { withBt { viewModel.btSender.refreshPairedDevices() } },
                onConnect = { device -> withBt { viewModel.btSender.connect(device) } },
                onDisconnect = { viewModel.btSender.disconnect() },
                onSend = { viewModel.btSender.send(it) },
                onStartReceiver = { withBt { viewModel.btReceiver.start() } },
                onStopReceiver = viewModel.btReceiver::stop,
                onClearReceived = viewModel.btReceiver::clearMessages,
                onEnableBluetooth = { withBt { enableBluetooth() } },
            )
            var tab by rememberSaveable { mutableStateOf(Tab.SpeakAndSend) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) controller.start() else controller.onPermissionDenied()
            }
            MaterialTheme {
                AppScreen(
                    tab = tab,
                    onTab = { tab = it },
                    senderState = senderState,
                    onConnect = { host, port -> viewModel.sender.connect(host, port) },
                    onDisconnect = { viewModel.sender.disconnect() },
                    onSendText = { viewModel.sender.send(it) },
                    receiverState = receiverState,
                    onStartReceiver = viewModel.receiver::start,
                    onStopReceiver = viewModel.receiver::stop,
                    onClearReceived = viewModel.receiver::clearMessages,
                    ttsState = ttsState,
                    alertMode = alertMode,
                    onAlertMode = { viewModel.alertMode.value = it },
                    onInstallHindi = ::installHindiVoice,
                    transport = transport,
                    onTransport = { viewModel.transport.value = it },
                    bt = bt,
                    state = state,
                    onStart = {
                        if (hasMicrophonePermission()) {
                            controller.start()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStop = controller::stop,
                    onClearTranscript = controller::clearTranscript,
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Android does not deliver microphone audio to backgrounded apps
        // without a foreground service, so stop listening when not visible.
        // A rotation keeps listening (the ViewModel survives it).
        if (!isChangingConfigurations) viewModel.controller.stop()
    }

    @SuppressLint("MissingPermission") // only called after BLUETOOTH_CONNECT is granted (withBt)
    private fun enableBluetooth() {
        try {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: Exception) {
        }
    }

    private fun installHindiVoice() {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(ReceiverTts.GOOGLE_TTS))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

private enum class Tab { SpeakAndSend, Receive }

@Composable
private fun AppScreen(
    tab: Tab,
    onTab: (Tab) -> Unit,
    senderState: TcpTextSender.State,
    onConnect: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onSendText: (String) -> Unit,
    receiverState: TcpTextReceiver.State,
    onStartReceiver: () -> Unit,
    onStopReceiver: () -> Unit,
    onClearReceived: () -> Unit,
    ttsState: ReceiverTts.State,
    alertMode: Boolean,
    onAlertMode: (Boolean) -> Unit,
    onInstallHindi: () -> Unit,
    transport: Transport,
    onTransport: (Transport) -> Unit,
    bt: BtUi,
    state: SpeechState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearTranscript: () -> Unit,
) {
    val transcriptListState = rememberLazyListState()
    // Keep the newest utterance in view as entries are appended.
    LaunchedEffect(state.transcript.size) {
        if (state.transcript.isNotEmpty()) transcriptListState.animateScrollToItem(state.transcript.lastIndex)
    }
    // Scaffold's content padding keeps text clear of system bars, which
    // Android 15+ draws over app content when targetSdk >= 35.
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabButton(stringResource(R.string.tab_speak_send), tab == Tab.SpeakAndSend) { onTab(Tab.SpeakAndSend) }
                TabButton(stringResource(R.string.tab_receive), tab == Tab.Receive) { onTab(Tab.Receive) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabButton(stringResource(R.string.transport_wifi), transport == Transport.WIFI) { onTransport(Transport.WIFI) }
                TabButton(stringResource(R.string.transport_bluetooth), transport == Transport.BLUETOOTH) { onTransport(Transport.BLUETOOTH) }
            }
            if (tab == Tab.Receive) {
                if (transport == Transport.WIFI) {
                    ReceiverPanel(wifiReceiverView(receiverState), onStartReceiver, onStopReceiver, onClearReceived, ttsState, alertMode, onAlertMode, onInstallHindi)
                } else {
                    if (bt.receiver.status.contains("OFF")) {
                        OutlinedButton(onClick = bt.onEnableBluetooth) { Text(stringResource(R.string.turn_on_bluetooth)) }
                    }
                    ReceiverPanel(
                        ReceiverView(bt.receiver.status, bt.receiver.failed, bt.receiver.running, bt.receiver.messages, addressLine = null),
                        bt.onStartReceiver, bt.onStopReceiver, bt.onClearReceived, ttsState, alertMode, onAlertMode, onInstallHindi,
                    )
                }
                return@Column
            }
            if (transport == Transport.WIFI) {
                SenderPanel(senderState, onConnect, onDisconnect, onSendText)
            } else {
                BluetoothSenderPanel(bt)
            }
            Text(stringResource(R.string.status_label, stringResource(statusText(state))))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !state.isListening) {
                    Text(stringResource(R.string.start))
                }
                Button(onClick = onStop, enabled = state.isListening) {
                    Text(stringResource(R.string.stop))
                }
            }
            HoldToTalkButton(isListening = state.isListening, onPress = onStart, onRelease = onStop)
            state.transcript.lastOrNull()?.result?.text?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.recognized_label, it), style = MaterialTheme.typography.titleLarge)
            }
            if (state.isLoadingModel) {
                Text(stringResource(R.string.loading_model))
            }
            state.error?.let { error ->
                Text(errorText(error), color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.transcript_label, state.transcript.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                // Only when idle, so an in-flight result cannot land in the new session.
                OutlinedButton(
                    onClick = onClearTranscript,
                    enabled = state.transcript.isNotEmpty() && !state.isListening && !state.isRecognizing,
                ) {
                    Text(stringResource(R.string.clear_transcript))
                }
            }
            if (state.transcript.isEmpty()) {
                Text(stringResource(R.string.transcript_empty))
            }
            LazyColumn(
                state = transcriptListState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.transcript) { index, entry ->
                    Column {
                        Text(
                            stringResource(
                                R.string.transcript_entry,
                                index + 1,
                                entry.result.text.ifBlank { stringResource(R.string.empty_result) },
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.transcript_entry_timing,
                                entry.result.inferenceTimeMs,
                                entry.audioDurationMs,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun statusText(state: SpeechState): Int = when {
    state.isListening && state.isRecognizing -> R.string.status_listening_and_recognizing
    state.isListening -> R.string.status_listening
    state.isRecognizing -> R.string.status_recognizing
    else -> R.string.status_idle
}

@Composable
private fun errorText(error: SpeechError): String = when (error) {
    SpeechError.MicrophonePermissionDenied -> stringResource(R.string.error_permission_denied)
    is SpeechError.AudioCaptureFailed -> stringResource(R.string.error_audio_capture, error.message)
    is SpeechError.RecognitionFailed -> stringResource(R.string.error_recognition, error.message)
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

/** Phone A: where recognized text is sent. */
@Composable
private fun SenderPanel(
    state: TcpTextSender.State,
    onConnect: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onSendText: (String) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(DEFAULT_LINK_PORT.toString()) }
    var testText by rememberSaveable { mutableStateOf("नमस्ते") }
    val connected = state.status is TcpTextSender.Status.Connected
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.receiver_ip)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(96.dp),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (connected) {
            OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
        } else {
            Button(
                onClick = { onConnect(host, port.toIntOrNull() ?: DEFAULT_LINK_PORT) },
                enabled = host.isNotBlank() && state.status !is TcpTextSender.Status.Connecting,
            ) { Text(stringResource(R.string.connect)) }
        }
        Text(senderStatusText(state), color = if (state.status is TcpTextSender.Status.Failed) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
    state.lastEvent?.let {
        Text(it, color = if (it.startsWith("NOT")) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            label = { Text(stringResource(R.string.test_message)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { onSendText(testText) }, enabled = connected && testText.isNotBlank()) {
            Text(stringResource(R.string.send))
        }
    }
}

@Composable
private fun senderStatusText(state: TcpTextSender.State): String = when (val s = state.status) {
    TcpTextSender.Status.Disconnected -> stringResource(R.string.link_disconnected)
    TcpTextSender.Status.Connecting -> stringResource(R.string.link_connecting)
    is TcpTextSender.Status.Connected -> stringResource(R.string.link_connected, s.address, state.sentCount)
    is TcpTextSender.Status.Failed -> s.message
}

/** Phone B: received messages, oldest first. */
@Composable
private fun ColumnScope.ReceiverPanel(
    view: ReceiverView,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    tts: ReceiverTts.State,
    alertMode: Boolean,
    onAlertMode: (Boolean) -> Unit,
    onInstallHindi: () -> Unit,
) {
    val running = view.running
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) {
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.stop_receiver)) }
        } else {
            Button(onClick = onStart) { Text(stringResource(R.string.start_receiver)) }
        }
        OutlinedButton(onClick = onClear, enabled = view.messages.isNotEmpty()) { Text(stringResource(R.string.clear_received)) }
    }
    Text(view.status, color = if (view.failed) MaterialTheme.colorScheme.error else Color.Unspecified)
    view.addressLine?.let { Text(it) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TabButton(stringResource(R.string.mode_normal), !alertMode) { onAlertMode(false) }
        TabButton(stringResource(R.string.mode_alert), alertMode) { onAlertMode(true) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tts.status, color = if (tts.ready) Color.Unspecified else MaterialTheme.colorScheme.error)
        if (tts.hindiDataMissing) {
            OutlinedButton(onClick = onInstallHindi) { Text(stringResource(R.string.install_hindi_voice)) }
        }
    }
    if (tts.speaking) Text(stringResource(R.string.speaking), style = MaterialTheme.typography.titleMedium)
    tts.lastStartLatencyMs?.let { Text(stringResource(R.string.tts_latency, it), style = MaterialTheme.typography.bodySmall) }
    if (alertMode && view.messages.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFD32F2F))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.alert_banner), color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text(view.messages.last(), color = Color.White, style = MaterialTheme.typography.headlineMedium)
        }
    }
    Text(stringResource(R.string.received_label, view.messages.size), style = MaterialTheme.typography.titleMedium)
    val listState = rememberLazyListState()
    LaunchedEffect(view.messages.size) {
        if (view.messages.isNotEmpty()) listState.animateScrollToItem(view.messages.lastIndex)
    }
    LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(view.messages) { index, message ->
            Text(stringResource(R.string.transcript_entry, index + 1, message), style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * Push-to-talk on top of the existing Start/Stop path: press starts listening,
 * release stops (which flushes, recognizes, and forwards the utterance).
 */
@Composable
private fun HoldToTalkButton(isListening: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(if (isListening) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    },
                )
            },
    ) {
        Text(
            stringResource(if (isListening) R.string.release_to_send else R.string.hold_to_talk),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** What the Receive tab shows, for either transport. */
private data class ReceiverView(
    val status: String,
    val failed: Boolean,
    val running: Boolean,
    val messages: List<String>,
    val addressLine: String?,
)

@Composable
private fun wifiReceiverView(state: TcpTextReceiver.State): ReceiverView {
    val status = when (val s = state.status) {
        TcpTextReceiver.Status.Stopped -> stringResource(R.string.receiver_stopped)
        is TcpTextReceiver.Status.Listening -> stringResource(R.string.receiver_listening, s.port)
        is TcpTextReceiver.Status.Connected -> stringResource(R.string.receiver_connected, s.peer)
        is TcpTextReceiver.Status.Failed -> stringResource(R.string.receiver_failed, s.message)
    }
    val addresses = remember(state.status) { localIpv4Addresses() }
    return ReceiverView(
        status = status,
        failed = state.status is TcpTextReceiver.Status.Failed,
        running = state.status !is TcpTextReceiver.Status.Stopped && state.status !is TcpTextReceiver.Status.Failed,
        messages = state.messages,
        addressLine = stringResource(R.string.this_device_ip, addresses.ifEmpty { listOf("?") }.joinToString(), DEFAULT_LINK_PORT),
    )
}

/** Bluetooth state and actions for the UI (Bluetooth actions go through the permission check). */
private class BtUi(
    val sender: BluetoothTextSender.State,
    val receiver: BluetoothTextReceiver.State,
    val onRefresh: () -> Unit,
    val onConnect: (BluetoothTextSender.PairedDevice) -> Unit,
    val onDisconnect: () -> Unit,
    val onSend: (String) -> Unit,
    val onStartReceiver: () -> Unit,
    val onStopReceiver: () -> Unit,
    val onClearReceived: () -> Unit,
    val onEnableBluetooth: () -> Unit,
)

/** Phone A over Bluetooth: pick an already-paired phone, connect, send. */
@Composable
private fun BluetoothSenderPanel(bt: BtUi) {
    var testText by rememberSaveable { mutableStateOf("नमस्ते") }
    val s = bt.sender
    LaunchedEffect(Unit) { bt.onRefresh() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.connected) {
            OutlinedButton(onClick = bt.onDisconnect) { Text(stringResource(R.string.disconnect)) }
        } else {
            OutlinedButton(onClick = bt.onRefresh) { Text(stringResource(R.string.refresh_paired)) }
        }
        if (s.status.contains("OFF")) {
            OutlinedButton(onClick = bt.onEnableBluetooth) { Text(stringResource(R.string.turn_on_bluetooth)) }
        }
        Text(s.status, color = if (s.failed) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
    if (!s.connected && !s.connecting) {
        Text(stringResource(R.string.paired_devices_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            s.pairedDevices.forEach { device -> OutlinedButton(onClick = { bt.onConnect(device) }) { Text(device.name) } }
        }
    }
    s.lastEvent?.let { Text(it, color = if (it.startsWith("NOT")) MaterialTheme.colorScheme.error else Color.Unspecified) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            label = { Text(stringResource(R.string.test_message)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { bt.onSend(testText) }, enabled = s.connected && testText.isNotBlank()) {
            Text(stringResource(R.string.send))
        }
    }
}
