package com.itantra.speechengine.stt

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itantra.speechengine.audio.AudioConfig
import com.itantra.speechengine.segmentation.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureNanoTime

/**
 * Stage 3B on-device validation of the full production path:
 *   raw 16 kHz mono PCM16 -> MelSpectrogramFeatureExtractor -> ONNX Runtime
 *   (FP32 IndicConformer) -> CtcGreedyDecoder -> Hindi text
 * via the real [IndicConformerRecognizer] / [SpeechRecognizer] classes -
 * not a standalone reimplementation, so a pass here exercises exactly the
 * code path a real caller would use.
 *
 * Milestone 1 (known clip): a bundled, previously-verified Hindi PCM16 wav
 * (same clip validated in Stage 1/Gate 1/Stage 3) is read, wrapped as a
 * [SpeechSegment], and recognized. Its output is compared against the
 * known-correct reference transcript.
 *
 * Milestone 2 (microphone): requires a human to actually speak into the
 * physical device's microphone during the test's recording window - this
 * cannot be synthesized. Manual inspection only, as instructed; no
 * benchmark accuracy is claimed from one utterance.
 *
 * The model file is loaded from this test app's external files directory
 * (pushed via `adb push`, not bundled as a Gradle asset - the 470 MB FP32
 * export is a validated artifact from
 * `~/itantra-stt-validation/export/indicconformer_hi.onnx`, not a
 * repository/build asset). tokens.txt is pushed the same way.
 */
@RunWith(AndroidJUnit4::class)
class IndicConformerRecognizerInstrumentedTest {

    private val filesDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!

    /**
     * Model file to load from [filesDir]. Defaults to the FP32 export; can be
     * overridden per run with `am instrument -e modelFile <name>` (see
     * speech-engine/scripts/run_stt_device_validation.sh) so the same
     * production-path milestone can be checked against the INT8 MatMul-only
     * export without duplicating this test.
     */
    private val modelFileName: String
        get() = InstrumentationRegistry.getArguments().getString("modelFile", "indicconformer_hi.onnx")

    private fun requireExternalFile(name: String): File {
        val f = File(filesDir, name)
        assertTrue(
            "Expected fixture not found at ${f.absolutePath}. Push it via " +
                "'adb push <local> ${f.absolutePath}' before running this test.",
            f.exists(),
        )
        return f
    }

    private fun readBundledPcm16MonoWav(resourceName: String): ShortArray {
        val bytes = javaClass.getResourceAsStream(resourceName)!!.use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.int == 0x46464952)
        buf.int
        require(buf.int == 0x45564157)
        var dataOffset = -1
        var dataSize = -1
        while (buf.remaining() >= 8) {
            val chunkIdPos = buf.position()
            val chunkId = buf.int
            val chunkSize = buf.int
            if (chunkId == 0x61746164) { // "data"
                dataOffset = buf.position()
                dataSize = chunkSize
                break
            } else {
                buf.position(chunkIdPos + 8 + chunkSize)
            }
        }
        require(dataOffset >= 0)
        val samples = ShortArray(dataSize / 2)
        val dataBuf = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) samples[i] = dataBuf.short
        return samples
    }

    private fun memInfo(): String {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return "totalPss=${mi.totalPss}KB"
    }

    @Test
    fun knownPcmClip_recognizesCorrectHindiText() {
        val modelFile = requireExternalFile(modelFileName)
        val tokensFile = requireExternalFile("tokens.txt")

        val samples = readBundledPcm16MonoWav("known_hindi_clip.wav")
        val reference = javaClass.getResourceAsStream("known_hindi_clip_reference.txt")!!
            .bufferedReader(Charsets.UTF_8).readText().trim()

        val durationMs = (samples.size * 1000L) / AudioConfig.DEFAULT_SAMPLE_RATE_HZ
        val segment = SpeechSegment(
            samples = samples,
            config = AudioConfig(),
            startTimestampMs = 0L,
            endTimestampMs = durationMs,
        )

        lateinit var recognizer: IndicConformerRecognizer
        val loadTimeNs = measureNanoTime {
            recognizer = IndicConformerRecognizer(modelFile, tokensFile)
        }
        val ramAfterLoad = memInfo()

        val result = recognizer.recognize(segment)
        val ramAfterInfer = memInfo()

        println(
            "REAL ANDROID MEASUREMENT [Milestone1 known-PCM] " +
                "model=$modelFileName " +
                "loadTimeMs=${loadTimeNs / 1_000_000} " +
                "inferenceTimeMs=${result.inferenceTimeMs} " +
                "audioDurationMs=$durationMs " +
                "rtf=${result.inferenceTimeMs.toDouble() / durationMs} " +
                "ramAfterLoad=$ramAfterLoad ramAfterInfer=$ramAfterInfer",
        )
        println("REAL ANDROID MEASUREMENT [Milestone1 known-PCM] recognized=\"${result.text}\"")
        println("REAL ANDROID MEASUREMENT [Milestone1 known-PCM] reference=\"$reference\"")
        println("REAL ANDROID MEASUREMENT [Milestone1 known-PCM] exactMatch=${result.text == reference}")

        recognizer.release()

        assertEquals(
            "recognized text should exactly match the known-correct reference",
            reference,
            result.text,
        )
    }

    /** Repeats recognition 3x on the same clip to check for stability/crashes, not a benchmark. */
    @Test
    fun knownPcmClip_repeatedInferenceIsStable() {
        val modelFile = requireExternalFile(modelFileName)
        val tokensFile = requireExternalFile("tokens.txt")
        val samples = readBundledPcm16MonoWav("known_hindi_clip.wav")
        val durationMs = (samples.size * 1000L) / AudioConfig.DEFAULT_SAMPLE_RATE_HZ
        val segment = SpeechSegment(samples, AudioConfig(), 0L, durationMs)

        val recognizer = IndicConformerRecognizer(modelFile, tokensFile)
        val texts = (1..3).map { recognizer.recognize(segment).text }
        recognizer.release()

        println(
            "REAL ANDROID MEASUREMENT [Milestone1 stability] model=$modelFileName " +
                "3 runs identical=${texts.toSet().size == 1}",
        )
        assertEquals("repeated inference on identical input should be deterministic", 1, texts.toSet().size)
    }
}
