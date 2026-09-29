package com.itantra.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itantra.app.link.BluetoothTextReceiver
import com.itantra.app.link.BluetoothTextSender
import com.itantra.app.link.DEFAULT_LINK_PORT
import com.itantra.app.link.Language
import com.itantra.app.link.MessageType
import com.itantra.app.link.ReceiverBrowser
import com.itantra.app.link.ReceiverTts
import com.itantra.app.link.TcpTextReceiver
import com.itantra.app.link.TcpTextSender
import com.itantra.app.link.TextLines
import com.itantra.app.link.TextMessage
import com.itantra.app.link.NodeTranslator
import com.itantra.app.link.Translation
import com.itantra.app.link.localIpv4Addresses
import com.itantra.app.speech.SpeechError
import com.itantra.app.speech.SpeechState
import com.itantra.app.ui.ItantraColors
import com.itantra.app.ui.ItantraTheme
import java.text.DateFormat
import java.util.Date

/**
 * Single entry point of the application. Owns the RECORD_AUDIO runtime
 * permission request (the app layer's job; speech-engine only declares the
 * permission) and renders [SpeechState]. Holds no speech logic itself.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SpeechViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreenIfSos(intent)
        // The theme is always light, so keep system bar icons dark regardless of the system dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            // Follows language switches: the view model replaces the controller (one model at a time).
            val controller by viewModel.controllerFlow.collectAsState()
            val state by controller.state.collectAsState()
            val sourceLanguage by viewModel.sourceLanguage.collectAsState()
            val sourceModelInstalled by viewModel.sourceModelInstalled.collectAsState()
            val installedLanguages by viewModel.installedLanguagesFlow.collectAsState()
            val activeSos by viewModel.activeSos.collectAsState()
            val hearingLanguage by viewModel.hearingLanguage.collectAsState()
            val translations by viewModel.translations.collectAsState()
            val translationNodeOnline by viewModel.translationNodeOnline.collectAsState()
            val pending by viewModel.pending.collectAsState()
            val browserState by viewModel.browser.state.collectAsState()
            val senderState by viewModel.sender.state.collectAsState()
            val receiverState by viewModel.receiver.state.collectAsState()
            val ttsState by viewModel.tts.state.collectAsState()
            val alertMode by viewModel.alertMode.collectAsState()
            val transport by viewModel.transport.collectAsState()
            val outgoingMode by viewModel.outgoingMode.collectAsState()
            val btSenderState by viewModel.btSender.state.collectAsState()
            val btReceiverState by viewModel.btReceiver.state.collectAsState()
            val receivedAt by viewModel.receivedAt.collectAsState()
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
            // The SOS notification works only if notifications are allowed (runtime permission on Android 13+).
            // Asked when a receiver is started; the receiver starts either way.
            var afterNotificationPrompt by remember { mutableStateOf<(() -> Unit)?>(null) }
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                afterNotificationPrompt?.invoke()
                afterNotificationPrompt = null
            }
            val withNotifications: (() -> Unit) -> Unit = { action ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ) {
                    action()
                } else {
                    afterNotificationPrompt = action
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val bt = BtUi(
                sender = btSenderState,
                receiver = btReceiverState,
                onRefresh = { withBt { viewModel.btSender.refreshPairedDevices() } },
                onConnect = { device -> withBt { viewModel.btSender.connect(device) } },
                onDisconnect = { viewModel.btSender.disconnect() },
                onSend = { viewModel.sendOutgoing(it) },
                onStartReceiver = { withNotifications { withBt { viewModel.btReceiver.start() } } },
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
            ItantraTheme {
                val sos = activeSos
                if (sos != null) {
                    SosScreen(sos, translation = sos.id?.let { translations[it] }, onAcknowledge = {
                        viewModel.acknowledgeSos()
                        clearShowOverLockScreen()
                    })
                    return@ItantraTheme
                }
                AppScreen(
                    tab = tab,
                    onTab = { tab = it },
                    senderState = senderState,
                    onConnect = { host, port -> viewModel.sender.connect(host, port) },
                    onDisconnect = { viewModel.sender.disconnect() },
                    onSendText = { viewModel.sendOutgoing(it) },
                    receiverState = receiverState,
                    onStartReceiver = { withNotifications { viewModel.receiver.start() } },
                    onStopReceiver = viewModel.receiver::stop,
                    onClearReceived = viewModel.receiver::clearMessages,
                    ttsState = ttsState,
                    alertMode = alertMode,
                    onAlertMode = { viewModel.alertMode.value = it },
                    onInstallHindi = ::installHindiVoice,
                    transport = transport,
                    onTransport = { viewModel.transport.value = it },
                    bt = bt,
                    lang = LangUi(
                        source = sourceLanguage,
                        modelInstalled = sourceModelInstalled,
                        onSource = viewModel::selectSourceLanguage,
                        installed = installedLanguages,
                        pending = pending,
                        discovery = browserState,
                        onFindReceivers = viewModel.browser::start,
                        onConnectFound = { viewModel.sender.connect(it.host, it.port) },
                    ),
                    outgoingMode = outgoingMode,
                    onOutgoingMode = { viewModel.outgoingMode.value = it },
                    receivedAt = receivedAt,
                    onSos = viewModel::sendSos,
                    advertisedName = viewModel.advertisedName,
                    translate = TranslateUi(
                        hearing = hearingLanguage,
                        onHearing = viewModel::selectHearingLanguage,
                        results = translations,
                        nodeOnline = translationNodeOnline,
                        nodeAddress = viewModel.translationNodeAddress,
                        onSetNode = viewModel::setTranslationNode,
                    ),
                    fullScreenSosAllowed = SosNotifier.canUseFullScreenIntent(this@MainActivity),
                    onAllowFullScreenSos = ::openFullScreenIntentSettings,
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showOverLockScreenIfSos(intent)
    }

    /** Opened from the SOS notification: show over the lock screen and turn the screen on. */
    private fun showOverLockScreenIfSos(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifications.EXTRA_SOS, false) != true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun clearShowOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
    }

    /** Android 14+: the user decides whether this app may show full-screen notifications. */
    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
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

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

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
    lang: LangUi,
    outgoingMode: MessageType,
    onOutgoingMode: (MessageType) -> Unit,
    receivedAt: Map<String, Long>,
    onSos: () -> Unit,
    advertisedName: String,
    translate: TranslateUi,
    fullScreenSosAllowed: Boolean,
    onAllowFullScreenSos: () -> Unit,
    state: SpeechState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearTranscript: () -> Unit,
) {
    val link = senderLink(transport, senderState, bt, lang.discovery)
    val receiverView = if (transport == Transport.WIFI) wifiReceiverView(receiverState) else btReceiverView(bt.receiver)
    // Scaffold's content padding keeps content clear of system bars (edge-to-edge).
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp) // phones use the full width; tablets get a centred, readable column
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val (headerStatus, headerOk) = if (tab == Tab.SpeakAndSend) {
                    (if (link.connected) stringResource(R.string.hdr_connected) else stringResource(R.string.hdr_ready)) to link.connected
                } else {
                    (if (receiverView.running) stringResource(R.string.hdr_listening) else stringResource(R.string.hdr_receiver_off)) to receiverView.running
                }
                AppHeader(headerStatus, headerOk)
                Segmented(
                    options = listOf(stringResource(R.string.tab_speak_send_ui), stringResource(R.string.tab_receive_ui)),
                    selectedIndex = tab.ordinal,
                    onSelect = { onTab(Tab.entries[it]) },
                )
                if (tab == Tab.SpeakAndSend) {
                    SpeakAndSendScreen(
                        link, transport, onTransport, senderState, onConnect, onDisconnect, onSendText, bt, lang,
                        outgoingMode, onOutgoingMode, onSos, state, onStart, onStop, onClearTranscript,
                    )
                } else {
                    ReceiveScreen(
                        receiverView, transport, onTransport, bt, onStartReceiver, onStopReceiver, onClearReceived,
                        ttsState, alertMode, onAlertMode, onInstallHindi, receivedAt,
                        advertisedName, fullScreenSosAllowed, onAllowFullScreenSos, translate,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AppHeader(status: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = ItantraColors.PrimaryDark,
            )
            Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyMedium, color = ItantraColors.Muted)
        }
        StatusPill(status, ok)
    }
}

// ---------------------------------------------------------------------------
// Speak & Send (Phone A)
// ---------------------------------------------------------------------------

@Composable
private fun SpeakAndSendScreen(
    link: SenderLink,
    transport: Transport,
    onTransport: (Transport) -> Unit,
    senderState: TcpTextSender.State,
    onConnect: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onSendText: (String) -> Unit,
    bt: BtUi,
    lang: LangUi,
    outgoingMode: MessageType,
    onOutgoingMode: (MessageType) -> Unit,
    onSos: () -> Unit,
    state: SpeechState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearTranscript: () -> Unit,
) {
    val alert = outgoingMode == MessageType.ALERT

    // 1. Connection
    SectionCard(title = stringResource(R.string.sec_connection)) {
        TransportSelector(transport, onTransport)
        StatusLine(
            text = when {
                link.connected -> stringResource(R.string.connected_to, link.peerName)
                link.connecting -> stringResource(R.string.connecting)
                link.failed -> stringResource(R.string.connect_failed)
                else -> stringResource(R.string.not_connected)
            },
            ok = link.connected,
            error = link.failed && !link.connected,
        )
        if (link.connected) {
            TextButton(onClick = if (transport == Transport.WIFI) onDisconnect else bt.onDisconnect) {
                Text(stringResource(R.string.disconnect))
            }
        } else if (!link.connecting) {
            if (transport == Transport.WIFI) WifiDeviceList(lang) else BluetoothDeviceList(bt)
        }
    }

    // SOS: one tap, over the selected transport (queued first and auto-connected if needed).
    SosButton(onSos)

    // 2. Message mode
    SectionCard(title = stringResource(R.string.sec_message_mode)) {
        Segmented(
            options = listOf(stringResource(R.string.mode_normal_ui), stringResource(R.string.mode_alert_ui)),
            selectedIndex = if (alert) 1 else 0,
            onSelect = { onOutgoingMode(if (it == 1) MessageType.ALERT else MessageType.NORMAL) },
            selectedColor = { if (it == 1) ItantraColors.Alert else ItantraColors.Primary },
        )
        Text(
            stringResource(if (alert) R.string.mode_alert_caption else R.string.mode_normal_caption),
            style = MaterialTheme.typography.bodySmall,
            color = if (alert) ItantraColors.Alert else ItantraColors.Muted,
        )
    }

    // 3. Language
    SectionCard(title = stringResource(R.string.sec_i_speak)) {
        LanguageChips(selected = lang.source, installed = lang.installed, onSelect = { if (!state.isListening) lang.onSource(it) })
        if (!lang.modelInstalled) {
            Text(
                stringResource(R.string.model_unavailable_friendly, lang.source.displayName),
                style = MaterialTheme.typography.bodySmall,
                color = ItantraColors.Pending,
            )
        }
    }

    // 4. Push to talk (the main control)
    HoldToTalkButton(
        isListening = state.isListening,
        alert = alert,
        enabled = lang.modelInstalled,
        onPress = onStart,
        onRelease = onStop,
    )
    Text(
        text = when {
            !lang.modelInstalled -> stringResource(R.string.ptt_unavailable)
            state.isLoadingModel -> stringResource(R.string.ptt_loading)
            state.isListening -> stringResource(R.string.ptt_speaking)
            state.isRecognizing -> stringResource(R.string.ptt_processing)
            else -> stringResource(R.string.ptt_hint)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = ItantraColors.Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    // Hands-free alternative to holding the button (same Start/Stop path).
    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onStart, enabled = !state.isListening && lang.modelInstalled) { Text(stringResource(R.string.start)) }
        TextButton(onClick = onStop, enabled = state.isListening) { Text(stringResource(R.string.stop)) }
    }
    state.error?.let { error ->
        Text(friendlyError(error), color = ItantraColors.Alert, style = MaterialTheme.typography.bodyMedium)
    }

    // 5. Recognized message + delivery status
    val last = state.transcript.lastOrNull()?.result?.text?.takeIf { it.isNotBlank() }
    AnimatedVisibility(visible = last != null) {
        if (last != null) {
            val delivery = deliveryStatus(last, lang.pending, link)
            SectionCard(title = stringResource(R.string.sec_recognized), accent = if (alert) ItantraColors.Alert else null) {
                Text(last, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.recognized_meta, lang.source.nativeName),
                    style = MaterialTheme.typography.bodySmall,
                    color = ItantraColors.Success,
                )
                Text(delivery.first, color = delivery.second, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }

    // 6. Pending / SOS pending
    if (lang.pending.isNotEmpty()) {
        SectionCard(title = stringResource(R.string.sec_pending), accent = ItantraColors.Pending) {
            lang.pending.forEach { m ->
                Text(
                    when (m.type) {
                        MessageType.SOS -> stringResource(R.string.sos_pending, m.text)
                        MessageType.ALERT -> stringResource(R.string.alert_pending, m.text)
                        MessageType.NORMAL -> stringResource(R.string.message_pending, m.text)
                    },
                    color = if (m.type == MessageType.NORMAL) ItantraColors.Pending else ItantraColors.Alert,
                    fontWeight = if (m.type == MessageType.SOS) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Text(stringResource(R.string.pending_caption), style = MaterialTheme.typography.bodySmall, color = ItantraColors.Muted)
        }
    }

    // 7. Type a message
    SectionCard(title = stringResource(R.string.sec_type)) {
        var text by rememberSaveable(lang.source) { mutableStateOf(lang.source.sample) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            )
            Button(
                onClick = { onSendText(text) },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = if (alert) ItantraColors.Alert else ItantraColors.Primary),
                shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(R.string.send)) }
        }
    }

    // 8. Recent recognized messages
    val history = state.transcript.dropLast(1).filter { it.result.text.isNotBlank() }.asReversed()
    if (history.isNotEmpty()) {
        SectionCard(title = stringResource(R.string.sec_recent, history.size)) {
            history.take(10).forEach { entry ->
                Text("• ${entry.result.text}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    // 9. Advanced / diagnostics (collapsed)
    AdvancedSection {
        ManualIpConnect(senderState, onConnect)
        DiagLine(stringResource(R.string.diag_wifi_sender), senderStatusText(senderState))
        senderState.lastEvent?.let { DiagLine(stringResource(R.string.diag_last_wifi_send), it) }
        DiagLine(stringResource(R.string.diag_bt_sender), bt.sender.status)
        bt.sender.lastEvent?.let { DiagLine(stringResource(R.string.diag_last_bt_send), it) }
        DiagLine(stringResource(R.string.diag_speech), stringResource(statusText(state)))
        DiagLine(
            stringResource(R.string.diag_model),
            "${lang.source.modelFile} (${if (lang.modelInstalled) "installed" else "missing"})",
        )
        state.error?.let { DiagLine(stringResource(R.string.diag_error), errorText(it)) }
        state.transcript.asReversed().take(10).forEach { entry ->
            DiagLine(
                entry.result.text.ifBlank { stringResource(R.string.empty_result) },
                stringResource(R.string.transcript_entry_timing, entry.result.inferenceTimeMs, entry.audioDurationMs),
            )
        }
        OutlinedButton(
            onClick = onClearTranscript,
            enabled = state.transcript.isNotEmpty() && !state.isListening && !state.isRecognizing,
        ) { Text(stringResource(R.string.clear_transcript)) }
    }
}

@Composable
private fun WifiDeviceList(lang: LangUi) {
    val d = lang.discovery
    // Discovery is the normal way to connect: start browsing as soon as the list is shown.
    LaunchedEffect(Unit) { lang.onFindReceivers() }
    Text(stringResource(R.string.available_devices), style = MaterialTheme.typography.labelLarge, color = ItantraColors.Muted)
    if (d.found.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.searching) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.searching_devices), color = ItantraColors.Muted)
            } else {
                Text(stringResource(R.string.no_receivers_found), color = ItantraColors.Muted, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = lang.onFindReceivers) { Text(stringResource(R.string.scan_again)) }
            }
        }
    }
    d.found.forEach { f ->
        DeviceRow(name = f.name.removePrefix("iTANTRA ").trim(), subtitle = stringResource(R.string.device_wifi_subtitle)) {
            lang.onConnectFound(f)
        }
    }
}

@Composable
private fun BluetoothDeviceList(bt: BtUi) {
    LaunchedEffect(Unit) { bt.onRefresh() }
    if (bt.sender.status.contains("OFF")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.bluetooth_off), color = ItantraColors.Alert, modifier = Modifier.weight(1f))
            Button(onClick = bt.onEnableBluetooth) { Text(stringResource(R.string.turn_on_bluetooth)) }
        }
        return
    }
    Text(stringResource(R.string.paired_devices), style = MaterialTheme.typography.labelLarge, color = ItantraColors.Muted)
    if (bt.sender.pairedDevices.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.no_paired_devices), color = ItantraColors.Muted, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = bt.onRefresh) { Text(stringResource(R.string.scan_again)) }
        }
    }
    bt.sender.pairedDevices.forEach { device ->
        DeviceRow(name = device.name, subtitle = stringResource(R.string.device_bt_subtitle)) { bt.onConnect(device) }
    }
}

@Composable
private fun DeviceRow(name: String, subtitle: String, onConnect: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = ItantraColors.SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Dot(ItantraColors.Success)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ItantraColors.Muted)
            }
            Button(onClick = onConnect, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.connect)) }
        }
    }
}

@Composable
private fun ManualIpConnect(state: TcpTextSender.State, onConnect: (String, Int) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(DEFAULT_LINK_PORT.toString()) }
    Text(stringResource(R.string.manual_connection), style = MaterialTheme.typography.labelLarge)
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
    OutlinedButton(
        onClick = { onConnect(host, port.toIntOrNull() ?: DEFAULT_LINK_PORT) },
        enabled = host.isNotBlank() && state.status !is TcpTextSender.Status.Connecting,
    ) { Text(stringResource(R.string.connect)) }
}

// ---------------------------------------------------------------------------
// Receive (Phone B)
// ---------------------------------------------------------------------------

@Composable
private fun ReceiveScreen(
    view: ReceiverView,
    transport: Transport,
    onTransport: (Transport) -> Unit,
    bt: BtUi,
    onStartWifi: () -> Unit,
    onStopWifi: () -> Unit,
    onClearWifi: () -> Unit,
    tts: ReceiverTts.State,
    alertMode: Boolean,
    onAlertMode: (Boolean) -> Unit,
    onInstallHindi: () -> Unit,
    receivedAt: Map<String, Long>,
    advertisedName: String,
    fullScreenSosAllowed: Boolean,
    onAllowFullScreenSos: () -> Unit,
    translate: TranslateUi,
) {
    val onStart = if (transport == Transport.WIFI) onStartWifi else bt.onStartReceiver
    val onStop = if (transport == Transport.WIFI) onStopWifi else bt.onStopReceiver
    val onClear = if (transport == Transport.WIFI) onClearWifi else bt.onClearReceived
    val messages = TextLines.dropDuplicates(view.messages.map(TextLines::decode))

    // 1. Receiver status
    SectionCard(title = stringResource(R.string.sec_receiver)) {
        TransportSelector(transport, onTransport)
        StatusLine(
            text = when {
                view.connectedPeer != null -> stringResource(R.string.receiving_from, view.connectedPeer)
                view.running -> stringResource(R.string.listening_ui)
                view.failed -> stringResource(R.string.receiver_failed_ui)
                else -> stringResource(R.string.receiver_off_ui)
            },
            ok = view.running,
            error = view.failed,
        )
        if (view.running) {
            Text(
                stringResource(
                    if (transport == Transport.WIFI) R.string.visible_as_wifi else R.string.visible_as_bt,
                    advertisedName,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = ItantraColors.Success,
            )
            Text(stringResource(R.string.background_hint), style = MaterialTheme.typography.bodySmall, color = ItantraColors.Muted)
        }
        if (!fullScreenSosAllowed) {
            OutlinedButton(onClick = onAllowFullScreenSos) { Text(stringResource(R.string.allow_full_screen_sos)) }
        }
        if (transport == Transport.BLUETOOTH && bt.receiver.status.contains("OFF")) {
            Button(onClick = bt.onEnableBluetooth) { Text(stringResource(R.string.turn_on_bluetooth)) }
        } else if (view.running) {
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.stop_receiver)) }
        } else {
            Button(onClick = onStart, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.start_receiver))
            }
        }
    }

    // 2. Emergency alert (latest message, if it is an alert)
    val latest = messages.lastOrNull()
    AnimatedVisibility(visible = latest != null && (latest.type != MessageType.NORMAL || alertMode)) {
        if (latest != null) AlertCard(latest, translate.results[latest.id ?: rawLineFor(latest, view.messages)])
    }

    // 3. Hearing language: the original, or a translation the installed model really provides.
    SectionCard(title = stringResource(R.string.sec_hearing)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = translate.hearing == null,
                onClick = { translate.onHearing(null) },
                label = { Text(stringResource(R.string.hearing_original)) },
            )
            Translation.TARGETS.forEach { target ->
                FilterChip(
                    selected = translate.hearing == target,
                    onClick = { translate.onHearing(target) },
                    label = { Text(stringResource(R.string.hearing_translated, target.nativeName)) },
                )
            }
        }
        Text(
            if (translate.hearing == null) {
                stringResource(R.string.hearing_original_caption)
            } else {
                val source = Translation.DIRECTIONS.first { it.second == translate.hearing }.first
                stringResource(R.string.hearing_translate_caption, source.nativeName, translate.hearing.nativeName)
            },
            style = MaterialTheme.typography.bodySmall,
            color = ItantraColors.Muted,
        )
        if (translate.hearing != null) {
            when (translate.nodeOnline) {
                true -> Text(stringResource(R.string.translation_ready), color = ItantraColors.Success, style = MaterialTheme.typography.bodyMedium)
                false -> Text(stringResource(R.string.translation_offline), color = ItantraColors.Alert, style = MaterialTheme.typography.bodyMedium)
                null -> Text(stringResource(R.string.translation_checking), color = ItantraColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (tts.speaking) {
            Text(stringResource(R.string.speaking), style = MaterialTheme.typography.titleMedium, color = ItantraColors.Primary)
        }
        tts.lastProblem?.let { Text(it, color = ItantraColors.Alert, style = MaterialTheme.typography.bodyMedium) }
        if (tts.hindiDataMissing) {
            OutlinedButton(onClick = onInstallHindi) { Text(stringResource(R.string.install_hindi_voice)) }
        }
    }

    // 4. Incoming messages, newest first
    SectionCard(title = stringResource(R.string.sec_incoming, messages.size)) {
        if (messages.isEmpty()) {
            Text(stringResource(R.string.no_messages_yet), color = ItantraColors.Muted)
        }
        messages.asReversed().forEach { m ->
            val key = m.id ?: rawLineFor(m, view.messages)
            MessageCard(m, receivedAt[key], translate.results[key])
        }
    }

    // 5. Advanced / diagnostics (collapsed)
    AdvancedSection {
        view.addressLine?.let { DiagLine(stringResource(R.string.diag_this_device), it) }
        DiagLine(stringResource(R.string.diag_receiver), view.status)
        DiagLine(stringResource(R.string.diag_tts), tts.status)
        TranslationNodeSetting(translate)
        tts.lastStartLatencyMs?.let { DiagLine(stringResource(R.string.diag_tts_latency), "$it ms") }
        DiagLine(
            stringResource(R.string.diag_voices),
            Language.entries.joinToString("  ") { l -> "${l.displayName} ${if (tts.voices[l] == true) "✓" else "✗"}" },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.force_alert), modifier = Modifier.weight(1f))
            Switch(checked = alertMode, onCheckedChange = onAlertMode)
        }
        OutlinedButton(onClick = onClear, enabled = view.messages.isNotEmpty()) { Text(stringResource(R.string.clear_received)) }
    }
}

/** The raw received line a decoded id-less message came from (its receivedAt key). */
private fun rawLineFor(m: TextMessage, lines: List<String>): String =
    lines.firstOrNull { TextLines.decode(it) == m } ?: m.text

@Composable
private fun AlertCard(message: TextMessage, translation: TranslationState?) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ItantraColors.Alert,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Emergency alert: ${message.text}" },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (message.type == MessageType.SOS) R.string.sos_received_title else R.string.emergency_alert),
                color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(message.text, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            TranslationLine(translation, onRed = true)
            Text(
                stringResource(R.string.alert_meta, message.language.nativeName),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MessageCard(m: TextMessage, time: Long?, translation: TranslationState?) {
    val alert = m.type != MessageType.NORMAL
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (alert) ItantraColors.AlertContainer else ItantraColors.SurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (alert) {
                Text(
                    stringResource(if (m.type == MessageType.SOS) R.string.sos_label else R.string.alert_label),
                    color = ItantraColors.Alert, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                m.text,
                style = MaterialTheme.typography.titleMedium,
                color = if (alert) ItantraColors.AlertDark else MaterialTheme.colorScheme.onSurface,
            )
            TranslationLine(translation, onRed = false)
            val timeText = time?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
            Text(
                listOfNotNull(stringResource(R.string.received_in, m.language.nativeName), timeText).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = ItantraColors.Muted,
            )
        }
    }
}

/** "→ ଓଡ଼ିଆ: <translation>", "translating…", or an honest "Translation unavailable" (the original was played). */
@Composable
private fun TranslationLine(state: TranslationState?, onRed: Boolean) {
    state ?: return
    val strong = if (onRed) Color.White else ItantraColors.PrimaryDark
    val weak = if (onRed) Color.White.copy(alpha = 0.85f) else ItantraColors.Muted
    when (state) {
        is TranslationState.Pending ->
            Text(stringResource(R.string.translating, state.target.nativeName), color = weak, style = MaterialTheme.typography.bodyMedium)
        is TranslationState.Done -> {
            Text(stringResource(R.string.translated_to, state.target.nativeName), color = weak, style = MaterialTheme.typography.labelMedium)
            Text(state.text, color = strong, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        is TranslationState.Failed ->
            Text(
                stringResource(R.string.translation_unavailable, state.reason),
                color = if (onRed) Color.White else ItantraColors.Alert,
                style = MaterialTheme.typography.bodySmall,
            )
    }
}

@Composable
private fun TranslationNodeSetting(translate: TranslateUi) {
    var address by rememberSaveable { mutableStateOf(translate.nodeAddress) }
    Text(stringResource(R.string.translation_node), style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it.trim() },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = {
            val host = address.substringBeforeLast(':', address)
            val port = address.substringAfterLast(':', "").toIntOrNull() ?: NodeTranslator.DEFAULT_PORT
            translate.onSetNode(host, port)
        }) { Text(stringResource(R.string.check)) }
    }
}

// ---------------------------------------------------------------------------
// Shared components
// ---------------------------------------------------------------------------

@Composable
private fun SectionCard(title: String, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accent ?: ItantraColors.Muted,
            )
            content()
        }
    }
}

/** Compact segmented control (tabs / transport / message mode). */
@Composable
private fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    selectedColor: (Int) -> Color = { ItantraColors.Primary },
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(ItantraColors.SurfaceVariant)
            .padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) selectedColor(i) else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(i) })
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    label,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TransportSelector(transport: Transport, onTransport: (Transport) -> Unit) {
    Segmented(
        options = listOf(stringResource(R.string.transport_wifi_ui), stringResource(R.string.transport_bluetooth_ui)),
        selectedIndex = transport.ordinal,
        onSelect = { onTransport(Transport.entries[it]) },
    )
}

/**
 * Transmission-language selector: equal-width tiles of fixed height (the
 * native name, and whether speech input or only typed text is available), so
 * the options stay aligned on narrow phones. State and selection logic unchanged.
 */
@Composable
private fun LanguageChips(selected: Language, installed: Set<Language>, onSelect: (Language) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        Language.entries.forEach { l ->
            val isSelected = selected == l
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) ItantraColors.PrimaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) ItantraColors.Primary else ItantraColors.SurfaceVariant,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(l) }),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Text(
                        if (isSelected) "✓ ${l.nativeName}" else l.nativeName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) ItantraColors.PrimaryDark else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(if (l in installed) R.string.lang_speech else R.string.text_only),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (l in installed) ItantraColors.Success else ItantraColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SosButton(onSos: () -> Unit) {
    val description = stringResource(R.string.sos_button_description)
    Button(
        onClick = onSos,
        colors = ButtonDefaults.buttonColors(containerColor = ItantraColors.Alert),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .semantics { contentDescription = description },
    ) {
        Text(stringResource(R.string.sos_button), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

/** Full-screen SOS state on the receiver: covers the whole UI until acknowledged. */
@Composable
private fun SosScreen(message: TextMessage, translation: TranslationState?, onAcknowledge: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(ItantraColors.Alert)
            .padding(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Text("🚨", style = MaterialTheme.typography.displayLarge)
            Text("SOS", color = Color.White, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black)
            Text(
                stringResource(R.string.sos_received),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(message.text, color = Color.White, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            TranslationLine(translation, onRed = true)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onAcknowledge,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ItantraColors.AlertDark),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(72.dp),
            ) {
                Text(stringResource(R.string.acknowledge_sos), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, ok: Boolean, error: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (error) ItantraColors.Alert else if (ok) ItantraColors.Success else ItantraColors.Muted)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusPill(text: String, ok: Boolean) {
    Surface(shape = RoundedCornerShape(50), color = if (ok) ItantraColors.SuccessContainer else ItantraColors.SurfaceVariant) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Dot(if (ok) ItantraColors.Success else ItantraColors.Muted)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (ok) ItantraColors.Success else ItantraColors.Muted)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun AdvancedSection(content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text(stringResource(if (open) R.string.advanced_hide else R.string.advanced_show), color = ItantraColors.Muted)
    }
    AnimatedVisibility(visible = open) {
        Surface(shape = RoundedCornerShape(16.dp), color = ItantraColors.SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodySmall, color = ItantraColors.Muted)
}

/**
 * Push-to-talk on top of the existing Start/Stop path: press starts listening,
 * release stops (which flushes, recognizes, and forwards the utterance).
 */
@Composable
private fun HoldToTalkButton(isListening: Boolean, alert: Boolean, enabled: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    val base = if (alert) ItantraColors.Alert else ItantraColors.Primary
    val color = when {
        !enabled -> ItantraColors.Muted.copy(alpha = 0.4f)
        isListening -> if (alert) ItantraColors.AlertDark else ItantraColors.PrimaryDark
        else -> base
    }
    val label = stringResource(if (isListening) R.string.release_to_send else R.string.hold_to_talk)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(color)
            .semantics { contentDescription = label }
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onPress()
                                tryAwaitRelease()
                                onRelease()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// UI state helpers (presentation only)
// ---------------------------------------------------------------------------

/** Phone A's link on the selected transport, in human terms. */
private data class SenderLink(
    val connected: Boolean,
    val connecting: Boolean,
    val failed: Boolean,
    val peerName: String,
    val lastEvent: String?,
)

@Composable
private fun senderLink(transport: Transport, tcp: TcpTextSender.State, bt: BtUi, discovery: ReceiverBrowser.State): SenderLink {
    val fallbackName = stringResource(R.string.receiver_generic)
    return when (transport) {
        Transport.WIFI -> {
            val address = (tcp.status as? TcpTextSender.Status.Connected)?.address
            val name = address?.let { a -> discovery.found.firstOrNull { a.startsWith("${it.host}:") }?.name?.removePrefix("iTANTRA ")?.trim() }
            SenderLink(
                connected = tcp.status is TcpTextSender.Status.Connected,
                connecting = tcp.status is TcpTextSender.Status.Connecting,
                failed = tcp.status is TcpTextSender.Status.Failed,
                peerName = name ?: fallbackName,
                lastEvent = tcp.lastEvent,
            )
        }
        Transport.BLUETOOTH -> SenderLink(
            connected = bt.sender.connected,
            connecting = bt.sender.connecting,
            failed = bt.sender.failed && !bt.sender.status.contains("OFF"), // "Bluetooth is off" is shown separately
            peerName = bt.sender.status.removePrefix("CONNECTED to ").takeIf { bt.sender.connected } ?: fallbackName,
            lastEvent = bt.sender.lastEvent,
        )
    }
}

/** Delivery status line for the latest recognized text: pending, sent, or not sent. */
@Composable
private fun deliveryStatus(text: String, pending: List<TextMessage>, link: SenderLink): Pair<String, Color> = when {
    pending.any { it.text == text } -> {
        val sos = pending.any { it.text == text && it.type != MessageType.NORMAL }
        stringResource(if (sos) R.string.delivery_sos_pending else R.string.delivery_pending) to
            (if (sos) ItantraColors.Alert else ItantraColors.Pending)
    }
    // lastEvent is the sender's own record ("Sent: <line>" / "NOT sent (...): <line>"); the line ends with the text.
    link.lastEvent?.startsWith("Sent") == true && link.lastEvent.endsWith(text) ->
        stringResource(R.string.delivery_sent, link.peerName) to ItantraColors.Success
    link.lastEvent?.startsWith("NOT") == true && link.lastEvent.endsWith(text) ->
        stringResource(R.string.delivery_not_sent) to ItantraColors.Alert
    else -> stringResource(R.string.delivery_recognized_only) to ItantraColors.Muted
}

private fun statusText(state: SpeechState): Int = when {
    state.isListening && state.isRecognizing -> R.string.status_listening_and_recognizing
    state.isListening -> R.string.status_listening
    state.isRecognizing -> R.string.status_recognizing
    else -> R.string.status_idle
}

/** Technical error text (diagnostics only). */
@Composable
private fun errorText(error: SpeechError): String = when (error) {
    SpeechError.MicrophonePermissionDenied -> stringResource(R.string.error_permission_denied)
    is SpeechError.AudioCaptureFailed -> stringResource(R.string.error_audio_capture, error.message)
    is SpeechError.RecognitionFailed -> stringResource(R.string.error_recognition, error.message)
}

/** Human-readable error for the main screen (no stack traces or file paths). */
@Composable
private fun friendlyError(error: SpeechError): String = when (error) {
    SpeechError.MicrophonePermissionDenied -> stringResource(R.string.error_permission_denied)
    is SpeechError.AudioCaptureFailed -> stringResource(R.string.friendly_mic_problem)
    is SpeechError.RecognitionFailed ->
        if (error.message.contains("not found", ignoreCase = true)) {
            stringResource(R.string.friendly_model_missing)
        } else {
            stringResource(R.string.friendly_recognition_failed)
        }
}

@Composable
private fun senderStatusText(state: TcpTextSender.State): String = when (val s = state.status) {
    TcpTextSender.Status.Disconnected -> stringResource(R.string.link_disconnected)
    TcpTextSender.Status.Connecting -> stringResource(R.string.link_connecting)
    is TcpTextSender.Status.Connected -> stringResource(R.string.link_connected, s.address, state.sentCount)
    is TcpTextSender.Status.Failed -> s.message
}

/** What the Receive tab shows, for either transport. */
private data class ReceiverView(
    val status: String,
    val failed: Boolean,
    val running: Boolean,
    val messages: List<String>,
    val addressLine: String?,
    val connectedPeer: String?,
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
        connectedPeer = if (state.status is TcpTextReceiver.Status.Connected) stringResource(R.string.sender_generic) else null,
    )
}

private fun btReceiverView(state: BluetoothTextReceiver.State): ReceiverView = ReceiverView(
    status = state.status,
    failed = state.failed,
    running = state.running,
    messages = state.messages,
    addressLine = null,
    connectedPeer = state.status.takeIf { it.startsWith("CONNECTED (from ") }?.removePrefix("CONNECTED (from ")?.removeSuffix(")"),
)

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

/** Receiver translation: what to hear, per-message results, and the translation node. */
private class TranslateUi(
    val hearing: Language?,
    val onHearing: (Language?) -> Unit,
    val results: Map<String, TranslationState>,
    val nodeOnline: Boolean?,
    val nodeAddress: String,
    val onSetNode: (String, Int) -> Unit,
)

/** Language selection, pending messages and discovery state for the UI. */
private class LangUi(
    val source: Language,
    val modelInstalled: Boolean,
    val onSource: (Language) -> Unit,
    val installed: Set<Language>,
    val pending: List<TextMessage>,
    val discovery: ReceiverBrowser.State,
    val onFindReceivers: () -> Unit,
    val onConnectFound: (ReceiverBrowser.Found) -> Unit,
)
