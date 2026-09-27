package com.itantra.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    init {
        // One logcat line per new transcript entry, so device runs leave
        // measurable evidence (read by app/scripts/score_live_stt.py).
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
                }
                logged = transcript.size
            }
        }
    }

    override fun onCleared() {
        controller.close()
    }

    private companion object {
        const val TAG = "SpeechViewModel"

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
