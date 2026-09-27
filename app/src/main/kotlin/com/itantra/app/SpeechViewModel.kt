package com.itantra.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.app.link.BluetoothTextReceiver
import com.itantra.app.link.BluetoothTextSender
import com.itantra.app.link.ReceiverTts
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Holds the [SpeechController] across configuration changes, so a rotation
 * does not stop listening or reload the model.
 */
class SpeechViewModel(application: Application) : AndroidViewModel(application) {

    val controller = SpeechController(
        audioSourceFactory = { AudioRecorderFrameSource() },
        segmenterFactory = { SpeechSegmenter(createVad(), SpeechTuning.segmenterConfig) },
        recognizerFactory = { createRecognizer(application) },
        recognitionDispatcher = Dispatchers.Default.limitedParallelism(1),
    )

    /** Phone B role: receives recognized text over TCP. */
    val receiver = TcpTextReceiver(viewModelScope, log = { Log.i(LINK_TAG, it) })

    /** Phone A role: sends each recognized utterance over TCP. */
    val sender = TcpTextSender(viewModelScope, log = { Log.i(LINK_TAG, it) })

    /** Phone B role: speaks each received message aloud. */
    val tts = ReceiverTts(application)

    /** Phone B alert mode: received messages are spoken loudly and shown as an alert banner. */
    val alertMode = MutableStateFlow(false)

    /** Second transport: Bluetooth Classic RFCOMM (Phone B receives, Phone A sends). */
    val btReceiver = BluetoothTextReceiver(application, viewModelScope, log = { Log.i(TRANSPORT_TAG, it) })
    val btSender = BluetoothTextSender(application, viewModelScope, log = { Log.i(TRANSPORT_TAG, it) })

    /** Which transport recognized text is sent over, and which receiver the UI shows. Wi-Fi is the default. */
    val transport = MutableStateFlow(Transport.WIFI)

    init {
        // Speak each newly received message once, in arrival order, whichever transport it came over.
        speakNewMessages(receiver.state.map { it.messages })
        speakNewMessages(btReceiver.state.map { it.messages })
        // One logcat line per new transcript entry, so device runs leave
        // measurable evidence (read by app/scripts/score_live_stt.py). Each new
        // non-blank recognized text is also sent to Phone B when connected.
        viewModelScope.launch {
            var logged = 0
            controller.state.map { it.transcript }.distinctUntilChanged().collect { transcript ->
                if (transcript.size < logged) logged = 0 // transcript cleared: new session
                transcript.drop(logged).forEachIndexed { i, entry ->
                    Log.i(
                        TAG,
                        "Transcript #${logged + i + 1}: audioDurationMs=${entry.audioDurationMs} " +
                            "inferenceTimeMs=${entry.result.inferenceTimeMs} text=\"${entry.result.text}\"",
                    )
                    if (entry.result.text.isNotBlank()) {
                        Log.i(LINK_TAG, "STT transcript: \"${entry.result.text}\"")
                        // send() itself reports "not connected" (log + on-screen) instead of dropping silently.
                        when (transport.value) {
                            Transport.WIFI -> {
                                Log.i(LINK_TAG, "Forwarding STT transcript over Wi-Fi, status=${sender.state.value.status}")
                                sender.send(entry.result.text)
                            }
                            Transport.BLUETOOTH -> {
                                Log.i(TRANSPORT_TAG, "Forwarding STT transcript over Bluetooth, status=${btSender.state.value.status}")
                                btSender.send(entry.result.text)
                            }
                        }
                    }
                }
                logged = transcript.size
            }
        }
    }

    private fun speakNewMessages(messagesFlow: Flow<List<String>>) {
        viewModelScope.launch {
            var spoken = 0
            messagesFlow.distinctUntilChanged().collect { messages ->
                if (messages.size < spoken) spoken = 0 // received list cleared
                messages.drop(spoken).forEach { tts.speak(it, alert = alertMode.value) }
                spoken = messages.size
            }
        }
    }

    override fun onCleared() {
        controller.close()
        sender.close()
        receiver.stop()
        btSender.close()
        btReceiver.stop()
        tts.shutdown()
    }

    private companion object {
        const val TAG = "SpeechViewModel"
        const val LINK_TAG = "TcpTextLink"
        const val TRANSPORT_TAG = "TextTransport"

        // Enable with `adb shell setprop log.tag.VadDiag DEBUG` (checked at each
        // Start); off by default, so normal runs log nothing extra.
        const val VAD_DIAG_TAG = "VadDiag"

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
        const val MODEL_FILE_NAME = "indicconformer_hi.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"

        fun createRecognizer(application: Application): SpeechRecognizer {
            val dir = application.getExternalFilesDir(null)
                ?: throw IllegalStateException("App external files directory is unavailable")
            val modelFile = File(dir, MODEL_FILE_NAME)
            val startNs = System.nanoTime()
            val recognizer = IndicConformerRecognizer(modelFile, File(dir, TOKENS_FILE_NAME))
            Log.i(
                TAG,
                "Model loaded: file=${modelFile.name} bytes=${modelFile.length()} " +
                    "loadTimeMs=${(System.nanoTime() - startNs) / 1_000_000}",
            )
            return recognizer
        }
    }
}

enum class Transport { WIFI, BLUETOOTH }
