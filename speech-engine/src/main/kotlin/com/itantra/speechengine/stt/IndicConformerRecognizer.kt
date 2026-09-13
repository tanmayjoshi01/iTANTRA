package com.itantra.speechengine.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.itantra.speechengine.segmentation.SpeechSegment
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * [SpeechRecognizer] backed by the validated Hindi IndicConformer FP32 ONNX
 * export (`indicconformer_stt_hi_hybrid_rnnt_large`, Stage 2 Gate 1/2),
 * [MelSpectrogramFeatureExtractor], and [CtcGreedyDecoder], executed via
 * ONNX Runtime Android directly (sherpa-onnx was evaluated in Stage 3B and
 * not adopted - see the Stage 3B report for why).
 *
 * FP32 only: the dynamically-quantized INT8 export does not load on ONNX
 * Runtime Android (`ConvInteger` unimplemented - Stage 3) and must not be
 * used here.
 *
 * Model/token files are loaded from an external, caller-supplied path
 * rather than bundled into the AAR/APK: the 470 MB model is a validated
 * artifact from `~/itantra-stt-validation/export/`, not a repository or
 * build asset. Where that file should live for a real build is an open,
 * not-yet-decided question (see `architecture.md`'s "Model Files and Large
 * Assets" note) - this constructor only accepts a resolved [File].
 */
class IndicConformerRecognizer(modelFile: File, tokensFile: File) : SpeechRecognizer {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val featureExtractor = MelSpectrogramFeatureExtractor()
    private val decoder: CtcGreedyDecoder

    init {
        if (!modelFile.exists()) throw SpeechRecognitionException.ModelNotFound(modelFile.absolutePath)
        if (!tokensFile.exists()) throw SpeechRecognitionException.ModelNotFound(tokensFile.absolutePath)

        session = try {
            env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
        } catch (e: Exception) {
            throw SpeechRecognitionException.ModelLoadFailed("Failed to load ${modelFile.absolutePath}", e)
        }
        decoder = CtcGreedyDecoder(CtcGreedyDecoder.loadTokens(tokensFile.readText()))
    }

    override fun recognize(segment: SpeechSegment): SpeechRecognitionResult {
        require(segment.config.sampleRateHz == MelSpectrogramFeatureExtractor.SAMPLE_RATE_HZ) {
            "IndicConformerRecognizer requires ${MelSpectrogramFeatureExtractor.SAMPLE_RATE_HZ}Hz audio, " +
                "got ${segment.config.sampleRateHz}Hz"
        }
        require(segment.config.channelCount == 1) { "IndicConformerRecognizer requires mono audio" }

        val t0 = System.nanoTime()
        val features = featureExtractor.extract(segment.samples)
        val frames = featureExtractor.frameCount(segment.samples.size)

        val audioSignal = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(features),
            longArrayOf(1, MelSpectrogramFeatureExtractor.MEL_BINS.toLong(), frames.toLong()),
        )
        val length = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1))

        try {
            session.run(mapOf("audio_signal" to audioSignal, "length" to length)).use { result ->
                val logprobsTensor = result.get("logprobs").get() as OnnxTensor
                val shape = logprobsTensor.info.shape
                val outFrames = shape[1].toInt()
                val vocabSize = shape[2].toInt()

                val logits = FloatArray(outFrames * vocabSize)
                logprobsTensor.floatBuffer.get(logits)

                val text = decoder.decode(logits, outFrames, vocabSize)
                val inferenceTimeMs = (System.nanoTime() - t0) / 1_000_000

                return SpeechRecognitionResult(
                    text = text,
                    language = "hi",
                    startTimestampMs = segment.startTimestampMs,
                    endTimestampMs = segment.endTimestampMs,
                    inferenceTimeMs = inferenceTimeMs,
                )
            }
        } catch (e: SpeechRecognitionException) {
            throw e
        } catch (e: Exception) {
            throw SpeechRecognitionException.InferenceFailed("ONNX inference failed", e)
        } finally {
            audioSignal.close()
            length.close()
        }
    }

    override fun release() {
        session.close()
    }
}
