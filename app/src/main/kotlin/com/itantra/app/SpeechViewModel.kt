package com.itantra.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.itantra.app.speech.AudioRecorderFrameSource
import com.itantra.app.speech.SpeechController
import com.itantra.speechengine.segmentation.SpeechSegmenter
import com.itantra.speechengine.stt.IndicConformerRecognizer
import com.itantra.speechengine.stt.SpeechRecognizer
import com.itantra.speechengine.vad.EnergyZcrVoiceActivityDetector
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Holds the [SpeechController] across configuration changes, so a rotation
 * does not stop listening or reload the model.
 */
class SpeechViewModel(application: Application) : AndroidViewModel(application) {

    val controller = SpeechController(
        audioSourceFactory = { AudioRecorderFrameSource() },
        segmenterFactory = { SpeechSegmenter(EnergyZcrVoiceActivityDetector()) },
        recognizerFactory = { createRecognizer(application) },
        recognitionDispatcher = Dispatchers.Default.limitedParallelism(1),
    )

    override fun onCleared() {
        controller.close()
    }

    private companion object {
        // Development-only placement, the same one speech-engine's own device
        // tests use: files pushed with adb into the app's external files
        // directory. How the model reaches devices is not yet decided, and
        // nothing is bundled into the APK.
        const val MODEL_FILE_NAME = "indicconformer_hi.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"

        fun createRecognizer(application: Application): SpeechRecognizer {
            val dir = application.getExternalFilesDir(null)
                ?: throw IllegalStateException("App external files directory is unavailable")
            return IndicConformerRecognizer(File(dir, MODEL_FILE_NAME), File(dir, TOKENS_FILE_NAME))
        }
    }
}
