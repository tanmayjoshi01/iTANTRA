package com.itantra.speechengine.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * Stage 3 on-device feasibility validation ONLY. This is not part of the
 * production speech-engine implementation: it exists to answer, with real
 * measurements on the physical reference device, whether the Hindi
 * IndicConformer ONNX exports (Stage 2 Gate 1/2) can actually execute on
 * ARM64 Android via ONNX Runtime, and whether the FP32-vs-INT8 latency
 * relationship observed on desktop (INT8 slower) persists here.
 *
 * Model/feature/token files are NOT bundled in the test APK (they are
 * hundreds of MB and gated/derived artifacts, not repository assets). They
 * are pushed via `adb push` into this test app's own external files
 * directory before running, and read from there at runtime. If a file is
 * missing, the corresponding test fails with a message naming the exact
 * expected path, rather than silently skipping.
 *
 * The synthetic-tensor timing/RAM tests mirror Stage 2 Gate 2's desktop
 * methodology exactly (same input shape (1,80,300), same 1 warm-up + 5
 * measured runs) for a direct desktop-vs-device comparison. The real-audio
 * test uses mel features computed on desktop by NeMo's own preprocessor
 * for a known Gate-1-verified Hindi clip (see
 * ~/itantra-stt-validation/export_features_for_android.py) - on-device raw
 * PCM -> mel-feature extraction is explicitly NOT implemented here.
 */
@RunWith(AndroidJUnit4::class)
class IndicConformerOnnxDeviceValidationTest {

    private val filesDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!

    private fun requireFile(name: String): File {
        val f = File(filesDir, name)
        assertTrue(
            "Expected fixture not found at ${f.absolutePath}. Push it via " +
                "'adb push <local> ${f.absolutePath}' before running this test.",
            f.exists(),
        )
        return f
    }

    private fun memInfo(): String {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return "totalPss=${mi.totalPss}KB (nativePss=${mi.nativePss}KB, dalvikPss=${mi.dalvikPss}KB)"
    }

    private fun syntheticInput(frames: Int): Pair<OnnxTensor, OnnxTensor> {
        val env = OrtEnvironment.getEnvironment()
        val rng = Random(42)
        val data = FloatArray(1 * 80 * frames) { rng.nextFloat() * 2f - 1f }
        val audioSignal = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1, 80, frames.toLong()),
        )
        val length = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(frames.toLong())),
            longArrayOf(1),
        )
        return audioSignal to length
    }

    private fun runModelTimingTest(modelFileName: String, label: String) {
        val modelFile = requireFile(modelFileName)
        val fileSizeMb = modelFile.length() / (1024.0 * 1024.0)

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()

        lateinit var session: OrtSession
        val loadTimeNs = measureNanoTime {
            session = env.createSession(modelFile.absolutePath, opts)
        }
        val ramAfterLoad = memInfo()

        val (audioSignal, length) = syntheticInput(300)
        val inputs = mapOf("audio_signal" to audioSignal, "length" to length)

        // 1 warm-up + 5 measured runs, same as desktop Gate 2 methodology.
        session.run(inputs).close()
        val times = LongArray(5)
        for (i in 0 until 5) {
            times[i] = measureNanoTime { session.run(inputs).close() }
        }
        val ramAfterInfer = memInfo()

        val meanMs = times.map { it / 1_000_000.0 }.average()
        val allMs = times.map { it / 1_000_000.0 }

        println(
            "REAL ANDROID MEASUREMENT [$label] " +
                "file=$modelFileName size=${"%.1f".format(fileSizeMb)}MB " +
                "loadTime=${"%.1f".format(loadTimeNs / 1_000_000.0)}ms " +
                "inferenceRunsMs=$allMs meanMs=${"%.1f".format(meanMs)} " +
                "ramAfterLoad=$ramAfterLoad ramAfterInfer=$ramAfterInfer",
        )

        audioSignal.close()
        length.close()
        session.close()
    }

    @Test
    fun fp32_loadsAndRunsSyntheticTensorOnDevice() {
        runModelTimingTest("indicconformer_hi.onnx", "FP32")
    }

    @Test
    fun int8_loadsAndRunsSyntheticTensorOnDevice() {
        runModelTimingTest("indicconformer_hi_int8.onnx", "INT8")
    }

    /**
     * INT8 MatMul-only export (indicconformer_hi_int8_matmul.onnx): only
     * MatMul is quantized, Conv stays FP32, so the graph has no ConvInteger
     * node. It was created as the fallback for the full INT8 export above,
     * whose ConvInteger nodes (uint8 activations x int8 weights) have no
     * kernel in onnxruntime-android 1.22.0 - [int8_loadsAndRunsSyntheticTensorOnDevice]
     * fails at session creation with ORT_NOT_IMPLEMENTED (observed on
     * device 2026-09-25). Added so one device run
     * (speech-engine/scripts/run_stt_device_validation.sh) gives a
     * three-way FP32 / INT8 / INT8 MatMul-only comparison, using the same
     * synthetic (1,80,300) input and 1 warm-up + 5 measured runs as the two
     * tests above. See docs/claude/android-stt-readiness.md.
     */
    @Test
    fun int8Matmul_loadsAndRunsSyntheticTensorOnDevice() {
        runModelTimingTest("indicconformer_hi_int8_matmul.onnx", "INT8_MATMUL_ONLY")
    }

    /**
     * Real (not synthetic) Hindi audio, executed on-device: feeds NeMo's own
     * precomputed mel features for a known Gate-1-verified clip into the
     * FP32 ONNX graph via ONNX Runtime Android, then performs CTC greedy
     * decoding (argmax per frame, collapse repeats, drop blank, map through
     * tokens.txt) entirely on-device, and compares against the reference
     * transcript. This validates ONNX execution + CTC decoding + tokenizer
     * handling on ARM64 with real speech-derived data - it does NOT
     * validate on-device raw-PCM feature extraction, which was not
     * implemented for this stage.
     */
    @Test
    fun fp32_realHindiFeatures_ctcGreedyDecode_onDevice() {
        val modelFile = requireFile("indicconformer_hi.onnx")
        val tokensFile = requireFile("tokens.txt")
        val featuresFile = requireFile("real_hindi_features_1x80x1819.f32")
        val referenceFile = requireFile("reference.txt")

        val tokens = tokensFile.readLines().map { line ->
            val idx = line.lastIndexOf(' ')
            line.substring(0, idx)
        }
        val blankId = tokens.size - 1
        println("REAL ANDROID MEASUREMENT [tokens] loaded ${tokens.size} tokens, blankId=$blankId")

        val mel = 80
        val time = 1819
        val bytes = featuresFile.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floatBuffer = bb.asFloatBuffer()
        assertTrue(
            "feature file size mismatch: expected ${1 * mel * time * 4} bytes, got ${bytes.size}",
            bytes.size == 1 * mel * time * 4,
        )

        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())

        val audioSignal = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, mel.toLong(), time.toLong()))
        val length = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(time.toLong())), longArrayOf(1))

        val inferenceTimeMs: Double
        val result: OrtSession.Result
        val t0 = System.nanoTime()
        result = session.run(mapOf("audio_signal" to audioSignal, "length" to length))
        inferenceTimeMs = (System.nanoTime() - t0) / 1_000_000.0

        val logprobsTensor = result.get("logprobs").get() as OnnxTensor
        val shape = logprobsTensor.info.shape // [1, frames, vocabSize]
        val frames = shape[1].toInt()
        val vocabSize = shape[2].toInt()
        val flat = logprobsTensor.floatBuffer

        val argmaxIds = IntArray(frames)
        for (f in 0 until frames) {
            var bestId = 0
            var bestVal = Float.NEGATIVE_INFINITY
            val base = f * vocabSize
            for (v in 0 until vocabSize) {
                val value = flat.get(base + v)
                if (value > bestVal) {
                    bestVal = value
                    bestId = v
                }
            }
            argmaxIds[f] = bestId
        }

        val collapsed = mutableListOf<Int>()
        var prev = -1
        for (id in argmaxIds) {
            if (id != prev && id != blankId) collapsed.add(id)
            prev = id
        }
        val decodedText = collapsed.joinToString("") { tokens[it] }.replace("▁", " ").trim()
        val referenceText = referenceFile.readText().trim()

        println("REAL ANDROID MEASUREMENT [FP32 real audio] frames=$frames vocabSize=$vocabSize inferenceTimeMs=${"%.1f".format(inferenceTimeMs)}")
        println("REAL ANDROID MEASUREMENT [FP32 real audio] decoded=\"$decodedText\"")
        println("REAL ANDROID MEASUREMENT [FP32 real audio] reference=\"$referenceText\"")
        println("REAL ANDROID MEASUREMENT [FP32 real audio] exactMatch=${decodedText == referenceText}")

        audioSignal.close()
        length.close()
        result.close()
        session.close()

        assertTrue("decoded text was empty", decodedText.isNotBlank())
    }
}
