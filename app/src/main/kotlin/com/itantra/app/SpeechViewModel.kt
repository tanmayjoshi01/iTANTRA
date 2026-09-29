package com.itantra.app

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.app.link.BluetoothTextReceiver
import com.itantra.app.link.BluetoothTextSender
import com.itantra.app.link.Language
import com.itantra.app.link.MessageType
import com.itantra.app.link.PendingQueue
import com.itantra.app.link.ReceiverBrowser
import com.itantra.app.link.ReceiverTts
import com.itantra.app.link.TextLines
import com.itantra.app.link.TextMessage
import com.itantra.app.link.TcpTextReceiver
import com.itantra.app.link.TcpTextSender
import com.itantra.app.speech.AudioRecorderFrameSource
import com.itantra.app.speech.SpeechController
import com.itantra.app.speech.SpeechTuning
import com.itantra.app.speech.VadDiagnostics
import com.itantra.speechengine.segmentation.SpeechSegmenter
import com.itantra.speechengine.stt.IndicConformerRecognizer
import com.itantra.speechengine.stt.SpeechRecognizer
import com.itantra.speechengine.vad.EnergyZcrVoiceActivityDetector
import com.itantra.speechengine.vad.VoiceActivityDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Holds the [SpeechController] across configuration changes, so a rotation
 * does not stop listening or reload the model.
 */
class SpeechViewModel(application: Application) : AndroidViewModel(application) {

    private val filesDir: File? = application.getExternalFilesDir(null)

    /** STT language of this phone. Hindi is the default. */
    private val _sourceLanguage = MutableStateFlow(Language.HI)
    val sourceLanguage: StateFlow<Language> = _sourceLanguage.asStateFlow()

    /** Whether the selected language's ONNX model is present on this phone. */
    private val _sourceModelInstalled = MutableStateFlow(isModelInstalled(Language.HI))
    val sourceModelInstalled: StateFlow<Boolean> = _sourceModelInstalled.asStateFlow()

    /** Languages whose STT model is actually present on this phone (rechecked on each language switch). */
    private val _installedLanguages = MutableStateFlow(installedLanguages())
    val installedLanguagesFlow: StateFlow<Set<Language>> = _installedLanguages.asStateFlow()

    /**
     * The speech controller for [sourceLanguage]. Only one exists (and only one
     * model is loaded) at a time: switching language closes the old one first.
     */
    private val _controller = MutableStateFlow(createController(Language.HI))
    val controllerFlow: StateFlow<SpeechController> = _controller.asStateFlow()
    val controller: SpeechController get() = _controller.value

    /** Phone B's receiving side lives in the process, not this screen, so it survives the UI closing. */
    private val hub = ReceiverHub.get(application)

    /** Phone B role: receives recognized text over TCP. */
    val receiver: TcpTextReceiver get() = hub.receiver

    /** Phone A role: sends each recognized utterance over TCP. */
    val sender = TcpTextSender(viewModelScope, log = { Log.i(LINK_TAG, it) })

    /** Phone B role: speaks each received message aloud. */
    val tts: ReceiverTts get() = hub.tts

    /** Phone B alert mode: received messages are spoken loudly and shown as an alert banner. */
    val alertMode: MutableStateFlow<Boolean> get() = hub.alertMode

    /** The received SOS shown until acknowledged (Phone B). */
    val activeSos: StateFlow<TextMessage?> get() = hub.activeSos
    val advertisedName: String get() = hub.advertisedName

    fun acknowledgeSos() = hub.acknowledgeSos()

    /** Type given to every outgoing message from this phone (manual Send and STT results). */
    val outgoingMode = MutableStateFlow(MessageType.NORMAL)

    /** Second transport: Bluetooth Classic RFCOMM (Phone B receives, Phone A sends). */
    val btReceiver: BluetoothTextReceiver get() = hub.btReceiver
    val btSender = BluetoothTextSender(application, viewModelScope, log = { Log.i(TRANSPORT_TAG, it) })

    /** Which transport recognized text is sent over, and which receiver the UI shows. Wi-Fi is the default. */
    val transport = MutableStateFlow(Transport.WIFI)

    /** Wi-Fi discovery: Phone B advertises its receiver (see [ReceiverHub]), Phone A browses for it. Manual IP stays as fallback. */
    val browser = ReceiverBrowser(application, log = { Log.i(LINK_TAG, it) })

    /** Messages waiting for a connection (persisted, so they survive an app restart). */
    private val pendingQueue = PendingQueue(filesDir?.let { File(it, PENDING_FILE_NAME) })
    private val _pending = MutableStateFlow(pendingQueue.messages)
    val pending: StateFlow<List<TextMessage>> = _pending.asStateFlow()

    init {
        // One logcat line per new transcript entry, so device runs leave
        // measurable evidence (read by app/scripts/score_live_stt.py). Each new
        // non-blank recognized text is also sent (or queued) as an outgoing message.
        viewModelScope.launch {
            _controller.collectLatest { current ->
                var logged = 0
                current.state.map { it.transcript }.distinctUntilChanged().collect { transcript ->
                    if (transcript.size < logged) logged = 0 // transcript cleared: new session
                    transcript.drop(logged).forEachIndexed { i, entry ->
                        Log.i(
                            TAG,
                            "Transcript #${logged + i + 1}: audioDurationMs=${entry.audioDurationMs} " +
                                "inferenceTimeMs=${entry.result.inferenceTimeMs} text=\"${entry.result.text}\"",
                        )
                        if (entry.result.text.isNotBlank()) {
                            Log.i(LINK_TAG, "STT transcript (${_sourceLanguage.value.code}): \"${entry.result.text}\"")
                            // Recognized by the selected language's own model, so it is in that language.
                            send(entry.result.text, outgoingMode.value, _sourceLanguage.value)
                        }
                    }
                    logged = transcript.size
                }
            }
        }
        // Deliver queued messages, in order, as soon as the selected transport is connected.
        viewModelScope.launch {
            combine(transport, sender.state, btSender.state) { t, _, _ -> t to isConnected(t) }
                .distinctUntilChanged()
                .collect { (t, connected) -> if (connected) flushPending(t) }
        }
    }

    /**
     * Switches the STT language. Refused while listening. Closes (and so
     * releases) the current recognizer before the next one is created; the new
     * model loads on the next Start.
     */
    fun selectSourceLanguage(language: Language) {
        if (language == _sourceLanguage.value || controller.state.value.isListening) return
        controller.close()
        _sourceLanguage.value = language
        _sourceModelInstalled.value = isModelInstalled(language)
        _installedLanguages.value = installedLanguages()
        _controller.value = createController(language)
        Log.i(
            LANG_TAG,
            "selected=${language.code} requestedModel=${language.modelFile} installed=${_sourceModelInstalled.value}" +
                if (_sourceModelInstalled.value) "" else " -> speech input disabled (typed text only)",
        )
    }

    /**
     * Sends [text] in the current source language as the selected [outgoingMode]
     * over the selected transport, or queues it if that transport is not
     * connected. Used by both manual Send and STT forwarding.
     */
    fun sendOutgoing(text: String) {
        // Typed text is tagged by its script, so e.g. Hindi typed while Odia is selected is not mislabeled.
        val language = Language.ofScript(text) ?: _sourceLanguage.value
        if (language != _sourceLanguage.value) {
            Log.i(LANG_TAG, "typed text is ${language.code} (selected ${_sourceLanguage.value.code}); tagged as ${language.code}")
        }
        send(text, outgoingMode.value, language)
    }

    /**
     * Sends an SOS over the selected transport. If not connected it is queued
     * ahead of other pending messages, and on Wi-Fi the first discovered
     * receiver is connected to at once so it is delivered as soon as possible.
     */
    fun sendSos() {
        Log.i(SOS_TAG, "SOS pressed (transport=${transport.value})")
        send("SOS from ${Build.MODEL}", MessageType.SOS, _sourceLanguage.value)
        if (transport.value == Transport.WIFI && sender.state.value.status !is TcpTextSender.Status.Connected &&
            sender.state.value.status !is TcpTextSender.Status.Connecting
        ) {
            browser.state.value.found.firstOrNull()?.let {
                Log.i(SOS_TAG, "not connected: connecting to discovered ${it.name} to deliver SOS")
                sender.connect(it.host, it.port)
            }
        }
    }

    private fun send(text: String, type: MessageType, language: Language) {
        val message = TextMessage(type, text, language, newMessageId())
        val line = TextLines.encode(message) ?: return
        val t = transport.value
        if (!isConnected(t)) {
            Log.i(TRANSPORT_TAG, "Outgoing message QUEUED (not connected): type=${message.type} lang=${message.language.code} text=\"$text\"")
            pendingQueue.add(line, first = type == MessageType.SOS)
            _pending.value = pendingQueue.messages
            return
        }
        Log.i(TRANSPORT_TAG, "Outgoing message: type=${message.type} lang=${message.language.code} text=\"$text\" via $t line=\"$line\"")
        sendLine(t, line)
    }

    private fun flushPending(t: Transport) {
        if (_pending.value.isEmpty()) return
        Log.i(TRANSPORT_TAG, "Connected over $t: delivering ${_pending.value.size} pending message(s)")
        pendingQueue.drain { sendLine(t, it) }
        _pending.value = pendingQueue.messages
    }

    private fun sendLine(t: Transport, line: String) = when (t) {
        Transport.WIFI -> sender.send(line)
        Transport.BLUETOOTH -> btSender.send(line)
    }

    private fun isConnected(t: Transport): Boolean = when (t) {
        Transport.WIFI -> sender.state.value.status is TcpTextSender.Status.Connected
        Transport.BLUETOOTH -> btSender.state.value.connected
    }

    val receivedAt: StateFlow<Map<String, Long>> get() = hub.receivedAt

    private fun isModelInstalled(language: Language): Boolean = filesDir?.let { File(it, language.modelFile).exists() } == true

    private fun installedLanguages(): Set<Language> = Language.entries.filter(::isModelInstalled).toSet()

    private fun createController(language: Language) = SpeechController(
        audioSourceFactory = { AudioRecorderFrameSource() },
        segmenterFactory = { SpeechSegmenter(createVad(), SpeechTuning.segmenterConfig) },
        recognizerFactory = { createRecognizer(getApplication(), language) },
        recognitionDispatcher = Dispatchers.Default.limitedParallelism(1),
    )

    override fun onCleared() {
        controller.close()
        sender.close()
        browser.stop()
        btSender.close()
        // Receivers and TTS belong to ReceiverHub: they keep running (with the foreground service) after the UI closes.
    }

    private companion object {
        const val TAG = "SpeechViewModel"
        const val LINK_TAG = "TcpTextLink"
        const val TRANSPORT_TAG = "TextTransport"
        const val LANG_TAG = "LanguageModel"
        const val SOS_TAG = "Sos"
        const val PENDING_FILE_NAME = "pending_messages.txt"

        // Enable with `adb shell setprop log.tag.VadDiag DEBUG` (checked at each
        // Start); off by default, so normal runs log nothing extra.
        const val VAD_DIAG_TAG = "VadDiag"

        fun newMessageId(): String = UUID.randomUUID().toString().take(8)

        fun createVad(): VoiceActivityDetector {
            val vad = EnergyZcrVoiceActivityDetector(SpeechTuning.vadConfig)
            return if (Log.isLoggable(VAD_DIAG_TAG, Log.DEBUG)) {
                VadDiagnostics(vad, log = { Log.d(VAD_DIAG_TAG, it) })
            } else {
                vad
            }
        }

        // Development-only placement, the same one speech-engine's own device
        // tests use: files pushed with adb into the app's external files
        // directory. How the model reaches devices in production is not yet
        // decided, and nothing is bundled into the APK.
        const val SHARED_TOKENS_FILE_NAME = "tokens.txt"

        fun createRecognizer(application: Application, language: Language): SpeechRecognizer {
            val dir = application.getExternalFilesDir(null)
                ?: throw IllegalStateException("App external files directory is unavailable")
            val modelFile = File(dir, language.modelFile)
            // A language-specific token list if provided; otherwise the shared one (verified for Hindi only).
            val tokensFile = File(dir, language.tokensFile).takeIf { it.exists() } ?: File(dir, SHARED_TOKENS_FILE_NAME)
            val startNs = System.nanoTime()
            Log.i(LANG_TAG, "loading STT model: lang=${language.code} requested=${modelFile.name} exists=${modelFile.exists()}")
            val recognizer = try {
                IndicConformerRecognizer(modelFile, tokensFile)
            } catch (e: Exception) {
                Log.w(LANG_TAG, "STT model load FAILED: lang=${language.code} file=${modelFile.name}: $e")
                throw e
            }
            Log.i(
                TAG,
                "Model loaded: lang=${language.code} file=${modelFile.name} bytes=${modelFile.length()} " +
                    "tokens=${tokensFile.name} loadTimeMs=${(System.nanoTime() - startNs) / 1_000_000}",
            )
            Log.i(LANG_TAG, "STT model loaded OK: lang=${language.code} file=${modelFile.name} tokens=${tokensFile.name}")
            return recognizer
        }
    }
}

enum class Transport { WIFI, BLUETOOTH }
