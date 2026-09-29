package com.itantra.app

import android.app.Application
import android.os.Build
import android.util.Log
import com.itantra.app.link.BluetoothTextReceiver
import com.itantra.app.link.Language
import com.itantra.app.link.NodeTranslator
import com.itantra.app.link.Translation
import com.itantra.app.link.MessageType
import com.itantra.app.link.ReceiverAdvertiser
import com.itantra.app.link.ReceiverTts
import com.itantra.app.link.TcpTextReceiver
import com.itantra.app.link.TextLines
import com.itantra.app.link.TextMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** What this receiver wants to hear: null = each message in its original language, else a translation target. */
    private val _hearingLanguage = MutableStateFlow<Language?>(null)
    val hearingLanguage: StateFlow<Language?> = _hearingLanguage.asStateFlow()

    /** Client for the laptop translation node (the one translation model). */
    val translator = NodeTranslator()

    /** Whether the translation node answered last time (null = not checked yet). */
    private val _translationNodeOnline = MutableStateFlow<Boolean?>(null)
    val translationNodeOnline: StateFlow<Boolean?> = _translationNodeOnline.asStateFlow()

    /** Translation outcome per received message (same key as [receivedAt]). The original text is never replaced. */
    private val _translations = MutableStateFlow<Map<String, TranslationState>>(emptyMap())
    val translations: StateFlow<Map<String, TranslationState>> = _translations.asStateFlow()

    /** Messages to translate (if asked for) and speak, one at a time in arrival order. */
    private val speechQueue = Channel<Pair<String, TextMessage>>(Channel.UNLIMITED)

    init {
        handleNewMessages(receiver.state.map { it.messages })
        handleNewMessages(btReceiver.state.map { it.messages })
        scope.launch { for ((key, message) in speechQueue) translateAndSpeak(key, message) }
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

    fun selectHearingLanguage(language: Language?) {
        _hearingLanguage.value = language
        Log.i(TRANSLATION_TAG, "hearing language -> ${language?.code ?: "original"}")
        if (language != null) checkTranslationNode()
    }

    fun setTranslationNode(host: String, port: Int) {
        translator.host = host
        translator.port = port
        checkTranslationNode()
    }

    fun checkTranslationNode() {
        val target = _hearingLanguage.value ?: Translation.TARGETS.first()
        val source = Translation.DIRECTIONS.first { it.second == target }.first
        scope.launch {
            val ok = withContext(Dispatchers.IO) { translator.healthy(source, target) }
            _translationNodeOnline.value = ok
            Log.i(TRANSLATION_TAG, "node ${translator.host}:${translator.port} ${if (ok) "ONLINE" else "UNREACHABLE"}")
        }
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
                    Log.i(
                        TRANSPORT_TAG,
                        "Receiver: received type=${message.type} lang=${message.language.code} " +
                            "hearing=${_hearingLanguage.value?.code ?: "original"} text=\"${message.text}\"",
                    )
                    // SOS is shown and sounded at once; translation/speech never delays it.
                    if (message.type == MessageType.SOS) raiseSos(message)
                    speechQueue.trySend(key to message)
                }
                handled = messages.size
            }
        }
    }

    /**
     * Translates [message] if the receiver asked for another language and the
     * model covers the pair, then speaks the final text in that language's
     * voice. If translation is unavailable or fails, the original is spoken in
     * its own language and the failure is recorded for the UI (never claimed as translated).
     */
    private suspend fun translateAndSpeak(key: String, message: TextMessage) {
        // The message's own type decides; the receiver's ALERT switch still forces alert mode.
        val alert = message.type != MessageType.NORMAL || alertMode.value
        var text = message.text
        var voice = message.language
        when (val route = Translation.route(message.language, _hearingLanguage.value)) {
            Translation.Route.Original -> Unit
            is Translation.Route.Unsupported -> {
                val reason = "no translation model for ${route.source.displayName} → ${route.target.displayName}"
                record(key, TranslationState.Failed(route.target, reason))
                Log.i(TRANSLATION_TAG, "id=${message.id} ${route.source.code}->${route.target.code}: $reason; speaking original")
            }
            is Translation.Route.Translate -> {
                record(key, TranslationState.Pending(route.target))
                val startNs = System.nanoTime()
                val reply = withContext(Dispatchers.IO) { translator.translate(message.text, route.source, route.target) }
                val ms = (System.nanoTime() - startNs) / 1_000_000
                when (reply) {
                    is Translation.Reply.Ok -> {
                        _translationNodeOnline.value = true
                        record(key, TranslationState.Done(route.target, reply.text, ms))
                        text = reply.text
                        voice = route.target
                        Log.i(
                            TRANSLATION_TAG,
                            "id=${message.id} ${route.source.code}->${route.target.code} ${ms}ms (node ${reply.nodeMs}ms): " +
                                "\"${message.text}\" -> \"${reply.text}\"",
                        )
                    }
                    is Translation.Reply.Failed -> {
                        if ("unreachable" in reply.reason) _translationNodeOnline.value = false
                        record(key, TranslationState.Failed(route.target, reply.reason))
                        Log.w(TRANSLATION_TAG, "id=${message.id} ${route.source.code}->${route.target.code} FAILED after ${ms}ms: ${reply.reason}; speaking original")
                    }
                }
            }
        }
        Log.i(TRANSLATION_TAG, "speak id=${message.id} voice=${voice.code} alert=$alert text=\"$text\"")
        tts.speak(text, alert = alert, language = voice)
    }

    private fun record(key: String, state: TranslationState) {
        _translations.value = _translations.value + (key to state)
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
        const val TRANSLATION_TAG = "Translation"

        @Volatile private var instance: ReceiverHub? = null

        fun get(app: Application): ReceiverHub =
            instance ?: synchronized(this) { instance ?: ReceiverHub(app).also { instance = it } }
    }
}

/** Translation of one received message, for display next to the (always kept) original. */
sealed interface TranslationState {
    val target: Language

    data class Pending(override val target: Language) : TranslationState

    data class Done(override val target: Language, val text: String, val latencyMs: Long) : TranslationState

    data class Failed(override val target: Language, val reason: String) : TranslationState
}
