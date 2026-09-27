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
import com.itantra.app.link.ReceiverAdvertiser
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
import kotlinx.coroutines.flow.Flow
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

    /**
     * The speech controller for [sourceLanguage]. Only one exists (and only one
     * model is loaded) at a time: switching language closes the old one first.
     */
    private val _controller = MutableStateFlow(createController(Language.HI))
    val controllerFlow: StateFlow<SpeechController> = _controller.asStateFlow()
    val controller: SpeechController get() = _controller.value

    /** Phone B role: receives recognized text over TCP. */
    val receiver = TcpTextReceiver(viewModelScope, log = { Log.i(LINK_TAG, it) })

    /** Phone A role: sends each recognized utterance over TCP. */
    val sender = TcpTextSender(viewModelScope, log = { Log.i(LINK_TAG, it) })

    /** Phone B role: speaks each received message aloud. */
    val tts = ReceiverTts(application)

    /** Phone B alert mode: received messages are spoken loudly and shown as an alert banner. */
    val alertMode = MutableStateFlow(false)

    /** Type given to every outgoing message from this phone (manual Send and STT results). */
    val outgoingMode = MutableStateFlow(MessageType.NORMAL)

    /**
     * Receiver voice: null = AUTO (speak each message in its own language). An
     * explicit language only changes the VOICE used; the text is not translated.
     */
    val voiceLanguage = MutableStateFlow<Language?>(null)

    /** Second transport: Bluetooth Classic RFCOMM (Phone B receives, Phone A sends). */
    val btReceiver = BluetoothTextReceiver(application, viewModelScope, log = { Log.i(TRANSPORT_TAG, it) })
    val btSender = BluetoothTextSender(application, viewModelScope, log = { Log.i(TRANSPORT_TAG, it) })

    /** Which transport recognized text is sent over, and which receiver the UI shows. Wi-Fi is the default. */
    val transport = MutableStateFlow(Transport.WIFI)

    /** Wi-Fi discovery: Phone B advertises its receiver, Phone A browses for it. Manual IP stays as fallback. */
    private val advertiser = ReceiverAdvertiser(application, log = { Log.i(LINK_TAG, it) })
    val browser = ReceiverBrowser(application, log = { Log.i(LINK_TAG, it) })

    /** Messages waiting for a connection (persisted, so they survive an app restart). */
    private val pendingQueue = PendingQueue(filesDir?.let { File(it, PENDING_FILE_NAME) })
    private val _pending = MutableStateFlow(pendingQueue.messages)
    val pending: StateFlow<List<TextMessage>> = _pending.asStateFlow()

    init {
        // Speak each newly received message once, in arrival order, whichever transport it came over.
        speakNewMessages(receiver.state.map { it.messages })
        speakNewMessages(btReceiver.state.map { it.messages })
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
                            sendOutgoing(entry.result.text)
                        }
                    }
                    logged = transcript.size
                }
            }
        }
        // Advertise the Wi-Fi receiver while it is listening or connected.
        viewModelScope.launch {
            receiver.state.map { it.status }.distinctUntilChanged().collect { status ->
                when (status) {
                    is TcpTextReceiver.Status.Listening -> advertiser.register(status.port, "iTANTRA ${Build.MODEL}")
                    is TcpTextReceiver.Status.Connected -> Unit
                    else -> advertiser.unregister()
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
        _controller.value = createController(language)
        Log.i(TAG, "Source language -> ${language.code}, model installed=${_sourceModelInstalled.value}")
    }

    /**
     * Sends [text] in the current source language as the selected [outgoingMode]
     * over the selected transport, or queues it if that transport is not
     * connected. Used by both manual Send and STT forwarding.
     */
    fun sendOutgoing(text: String) {
        val message = TextMessage(outgoingMode.value, text, _sourceLanguage.value, newMessageId())
        val line = TextLines.encode(message) ?: return
        val t = transport.value
        if (!isConnected(t)) {
            Log.i(TRANSPORT_TAG, "Outgoing message QUEUED (not connected): type=${message.type} lang=${message.language.code} text=\"$text\"")
            pendingQueue.add(line)
            _pending.value = pendingQueue.messages
            return
        }
        Log.i(TRANSPORT_TAG, "Outgoing message: type=${message.type} lang=${message.language.code} text=\"$text\" via $t")
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

    private fun speakNewMessages(messagesFlow: Flow<List<String>>) {
        viewModelScope.launch {
            var spoken = 0
            val seenIds = HashSet<String>()
            messagesFlow.distinctUntilChanged().collect { messages ->
                if (messages.size < spoken) {
                    spoken = 0 // received list cleared
                    seenIds.clear()
                }
                messages.drop(spoken).forEach { line ->
                    val message = TextLines.decode(line)
                    if (message.id != null && !seenIds.add(message.id)) {
                        Log.i(TRANSPORT_TAG, "Receiver: duplicate id=${message.id} ignored")
                        return@forEach
                    }
                    val voice = voiceLanguage.value ?: message.language
                    Log.i(TRANSPORT_TAG, "Receiver: received type=${message.type} lang=${message.language.code} voice=${voice.code} text=\"${message.text}\"")
                    // The message's own type decides; the receiver's ALERT switch still forces alert mode.
                    tts.speak(message.text, alert = message.type == MessageType.ALERT || alertMode.value, language = voice)
                }
                spoken = messages.size
            }
        }
    }

    private fun isModelInstalled(language: Language): Boolean = filesDir?.let { File(it, language.modelFile).exists() } == true

    private fun createController(language: Language) = SpeechController(
        audioSourceFactory = { AudioRecorderFrameSource() },
        segmenterFactory = { SpeechSegmenter(createVad(), SpeechTuning.segmenterConfig) },
        recognizerFactory = { createRecognizer(getApplication(), language) },
        recognitionDispatcher = Dispatchers.Default.limitedParallelism(1),
    )

    override fun onCleared() {
        controller.close()
        sender.close()
        receiver.stop()
        advertiser.unregister()
        browser.stop()
        btSender.close()
        btReceiver.stop()
        tts.shutdown()
    }

    private companion object {
        const val TAG = "SpeechViewModel"
        const val LINK_TAG = "TcpTextLink"
        const val TRANSPORT_TAG = "TextTransport"
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
            val recognizer = IndicConformerRecognizer(modelFile, tokensFile)
            Log.i(
                TAG,
                "Model loaded: lang=${language.code} file=${modelFile.name} bytes=${modelFile.length()} " +
                    "tokens=${tokensFile.name} loadTimeMs=${(System.nanoTime() - startNs) / 1_000_000}",
            )
            return recognizer
        }
    }
}

enum class Transport { WIFI, BLUETOOTH }
