package com.itantra.speechengine.stt

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itantra.speechengine.audio.AudioRecorder
import com.itantra.speechengine.segmentation.SpeechSegment
import com.itantra.speechengine.segmentation.SpeechSegmenter
import com.itantra.speechengine.vad.EnergyZcrVoiceActivityDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Stage 3B Milestone 2: the real Stage 1 pipeline
 * (AudioRecorder -> SpeechSegmenter, unmodified) feeding a real microphone
 * utterance into [IndicConformerRecognizer].
 *
 * This requires a human to actually speak into the physical device's
 * microphone during the test's recording window - that cannot be
 * synthesized, so this test waits (up to [WAIT_SECONDS]) for
 * [SpeechSegmenter] to finalize one real utterance, then recognizes it.
 * Per the gate's explicit instruction, this is manual-inspection-only: it
 * asserts only that *a* non-empty segment was captured and recognized
 * without crashing, not any particular transcription content or accuracy.
 */
@RunWith(AndroidJUnit4::class)
class MicrophoneToHindiTextInstrumentedTest {

    companion object {
        private const val WAIT_SECONDS = 25L
    }

    private val filesDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!

    private fun memInfo(): String {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return "totalPss=${mi.totalPss}KB"
    }

    @Test
    fun realMicrophoneUtterance_isSegmentedAndRecognized() {
        val modelFile = File(filesDir, "indicconformer_hi.onnx")
        val tokensFile = File(filesDir, "tokens.txt")
        assertTrue("model file missing at ${modelFile.absolutePath}", modelFile.exists())
        assertTrue("tokens file missing at ${tokensFile.absolutePath}", tokensFile.exists())

        val recorder = AudioRecorder()
        val segmenter = SpeechSegmenter(EnergyZcrVoiceActivityDetector())
        val segmentLatch = CountDownLatch(1)
        val capturedSegment = AtomicReference<SpeechSegment?>()

        try {
            recorder.start(
                onFrame = { frame ->
                    val segment = segmenter.processFrame(frame)
                    if (segment != null && capturedSegment.get() == null) {
                        capturedSegment.set(segment)
                        segmentLatch.countDown()
                    }
                },
                onError = { println("REAL ANDROID MEASUREMENT [Milestone2 mic] AudioRecorder error: $it") },
            )

            println("REAL ANDROID MEASUREMENT [Milestone2 mic] listening for up to ${WAIT_SECONDS}s - speak now")
            val gotSegment = segmentLatch.await(WAIT_SECONDS, TimeUnit.SECONDS)
            assertTrue("no speech segment captured within ${WAIT_SECONDS}s - was anything spoken into the mic?", gotSegment)
        } finally {
            recorder.stop()
            recorder.release()
        }

        val segment = capturedSegment.get()!!
        println(
            "REAL ANDROID MEASUREMENT [Milestone2 mic] captured segment durationMs=${segment.durationMs} " +
                "samples=${segment.samples.size} sampleRateHz=${segment.config.sampleRateHz}",
        )

        val recognizer = IndicConformerRecognizer(modelFile, tokensFile)
        val ramBefore = memInfo()
        val result = try {
            recognizer.recognize(segment)
        } finally {
            recognizer.release()
        }
        val ramAfter = memInfo()

        println(
            "REAL ANDROID MEASUREMENT [Milestone2 mic] inferenceTimeMs=${result.inferenceTimeMs} " +
                "audioDurationMs=${segment.durationMs} " +
                "rtf=${result.inferenceTimeMs.toDouble() / segment.durationMs} " +
                "ramBefore=$ramBefore ramAfter=$ramAfter",
        )
        println("REAL ANDROID MEASUREMENT [Milestone2 mic] RECOGNIZED TEXT: \"${result.text}\"")
        println(
            "REAL ANDROID MEASUREMENT [Milestone2 mic] NOTE: manual inspection only, per instructions - " +
                "no accuracy is asserted or claimed from this single utterance.",
        )

        assertTrue("captured segment produced no audio samples", segment.samples.isNotEmpty())
    }
}
