package com.itantra.app

import android.app.Application
import android.os.Build
import android.util.Log
import com.itantra.app.link.BluetoothTextReceiver
import com.itantra.app.link.MessageType
import com.itantra.app.link.ReceiverAdvertiser
import com.itantra.app.link.ReceiverTts
import com.itantra.app.link.TcpTextReceiver
import com.itantra.app.link.TextLines
import com.itantra.app.link.TextMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Phone B's receiving side, owned by the process rather than the screen: the
 * Wi-Fi and Bluetooth receivers, NSD advertising, TTS, and SOS handling. While
 * a receiver runs, [ReceiverService] keeps the process in the foreground, so
 * messages (and SOS) still arrive after the UI is closed. A force-stopped app
 * receives nothing.
 */
class ReceiverHub private constructor(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val receiver = TcpTextReceiver(scope, log = { Log.i(LINK_TAG, it) })
    val btReceiver = BluetoothTextReceiver(app, scope, log = { Log.i(TRANSPORT_TAG, it) })
    val tts = ReceiverTts(app)

    /** Receiver alert switch: every received message is spoken loudly and shown as an alert. */
    val alertMode = MutableStateFlow(false)

    /** Name this phone advertises to nearby senders over Wi-Fi. */
    val advertisedName = "iTANTRA ${Build.MODEL}"
    private val advertiser = ReceiverAdvertiser(app, log = { Log.i(LINK_TAG, it) })

    /** When each received message arrived (key: message id, or the raw line if it has none), for the UI. */
    private val _receivedAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val receivedAt: StateFlow<Map<String, Long>> = _receivedAt.asStateFlow()

    /** The SOS being shown until the user acknowledges it; null when there is none. */
    private val _activeSos = MutableStateFlow<TextMessage?>(null)
    val activeSos: StateFlow<TextMessage?> = _activeSos.asStateFlow()

    private val alarm = SosAlarm(app)

    init {
        handleNewMessages(receiver.state.map { it.messages })
        handleNewMessages(btReceiver.state.map { it.messages })
        // Advertise the Wi-Fi receiver while it is listening or connected.
        scope.launch {
            receiver.state.map { it.status }.distinctUntilChanged().collect { status ->
                when (status) {
                    is TcpTextReceiver.Status.Listening -> advertiser.register(status.port, advertisedName)
                    is TcpTextReceiver.Status.Connected -> Unit
                    else -> advertiser.unregister()
                }
            }
        }
        // Keep the process alive (foreground service) exactly while some receiver is running.
        scope.launch {
            combine(receiver.state, btReceiver.state) { wifi, bt ->
                wifi.status is TcpTextReceiver.Status.Listening || wifi.status is TcpTextReceiver.Status.Connected || bt.running
            }.distinctUntilChanged().collect { running ->
                if (running) ReceiverService.start(app) else ReceiverService.stop(app)
            }
        }
    }

    fun acknowledgeSos() {
        Log.i(SOS_TAG, "SOS acknowledged")
        _activeSos.value = null
        alarm.stop()
        SosNotifier.cancel(app)
    }

    fun stopReceivers() {
        receiver.stop()
        btReceiver.stop()
    }

    /** Speaks each newly received message once, in arrival order; an SOS raises the SOS state instead. */
    private fun handleNewMessages(messagesFlow: Flow<List<String>>) {
        scope.launch {
            var handled = 0
            val seenIds = HashSet<String>()
            messagesFlow.distinctUntilChanged().collect { messages ->
                if (messages.size < handled) {
                    handled = 0 // received list cleared
                    seenIds.clear()
                }
                messages.drop(handled).forEach { line ->
                    val message = TextLines.decode(line)
                    if (message.id != null && !seenIds.add(message.id)) {
                        Log.i(TRANSPORT_TAG, "Receiver: duplicate id=${message.id} ignored")
                        return@forEach
                    }
                    val key = message.id ?: line
                    if (key !in _receivedAt.value) _receivedAt.value = _receivedAt.value + (key to System.currentTimeMillis())
                    if (message.type == MessageType.SOS) {
                        raiseSos(message)
                        return@forEach
                    }
                    // No translation exists, so playback is always in the language the text is in.
                    Log.i(
                        TRANSPORT_TAG,
                        "Receiver: received type=${message.type} lang=${message.language.code} " +
                            "playback=${message.language.code} translation=unavailable text=\"${message.text}\"",
                    )
                    // The message's own type decides; the receiver's ALERT switch still forces alert mode.
                    tts.speak(message.text, alert = message.type == MessageType.ALERT || alertMode.value, language = message.language)
                }
                handled = messages.size
            }
        }
    }

    private fun raiseSos(message: TextMessage) {
        Log.i(SOS_TAG, "SOS RECEIVED lang=${message.language.code} id=${message.id} text=\"${message.text}\"")
        _activeSos.value = message
        alarm.start()
        SosNotifier.show(app, message)
    }

    companion object {
        const val LINK_TAG = "TcpTextLink"
        const val TRANSPORT_TAG = "TextTransport"
        const val SOS_TAG = "Sos"

        @Volatile private var instance: ReceiverHub? = null

        fun get(app: Application): ReceiverHub =
            instance ?: synchronized(this) { instance ?: ReceiverHub(app).also { instance = it } }
    }
}
