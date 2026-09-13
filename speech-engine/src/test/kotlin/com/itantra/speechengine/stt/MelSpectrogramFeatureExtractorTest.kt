package com.itantra.speechengine.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Milestone 1 (JVM level, before any Android involvement): does this
 * hand-written Kotlin feature extractor reproduce NeMo's own reference
 * mel-spectrogram features for a known Hindi clip, closely enough that
 * downstream CTC decoding would be unaffected? Ground truth
 * (`known_hindi_clip_reference_features_1x80x1819.f32`) was computed by
 * NeMo's actual `AudioToMelSpectrogramPreprocessor` on desktop (Stage 3)
 * for the exact same clip.
 */
class MelSpectrogramFeatureExtractorTest {

    private fun resourceBytes(name: String): ByteArray =
        javaClass.getResourceAsStream(name)!!.use { it.readBytes() }

    /** Minimal WAV (PCM16, mono) reader - enough for our own known-good test fixtures. */
    private fun readPcm16MonoWav(bytes: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.int == 0x46464952) { "not a RIFF file" } // "RIFF" little-endian
        buf.int // chunk size
        require(buf.int == 0x45564157) { "not a WAVE file" } // "WAVE"

        var dataOffset = -1
        var dataSize = -1
        while (buf.remaining() >= 8) {
            val chunkIdPos = buf.position()
            val chunkId = buf.int
            val chunkSize = buf.int
            if (chunkId == 0x20746d66) { // "fmt "
                val audioFormat = buf.short.toInt()
                val numChannels = buf.short.toInt()
                val sampleRate = buf.int
                buf.int; buf.short // byte rate, block align
                val bitsPerSample = buf.short.toInt()
                require(audioFormat == 1) { "expected PCM, got format $audioFormat" }
                require(numChannels == 1) { "expected mono, got $numChannels channels" }
                require(sampleRate == 16000) { "expected 16kHz, got $sampleRate" }
                require(bitsPerSample == 16) { "expected 16-bit, got $bitsPerSample" }
                buf.position(chunkIdPos + 8 + chunkSize)
            } else if (chunkId == 0x61746164) { // "data"
                dataOffset = buf.position()
                dataSize = chunkSize
                break
            } else {
                buf.position(chunkIdPos + 8 + chunkSize)
            }
        }
        require(dataOffset >= 0) { "no data chunk found" }
        val samples = ShortArray(dataSize / 2)
        val dataBuf = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) samples[i] = dataBuf.short
        return samples
    }

    @Test
    fun matchesNeMoReferenceFeaturesClosely() {
        val wavBytes = resourceBytes("known_hindi_clip.wav")
        val samples = readPcm16MonoWav(wavBytes)

        val extractor = MelSpectrogramFeatureExtractor()
        val actual = extractor.extract(samples)

        val refBytes = resourceBytes("known_hindi_clip_reference_features_1x80x1819.f32")
        val refBuf = ByteBuffer.wrap(refBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val expected = FloatArray(refBuf.remaining()) { refBuf.get() }

        val expectedFrames = 1819
        val actualFrames = extractor.frameCount(samples.size)
        assertEquals("frame count must match NeMo's get_seq_len formula", expectedFrames, actualFrames)
        assertEquals(MelSpectrogramFeatureExtractor.MEL_BINS * expectedFrames, expected.size)
        assertEquals(expected.size, actual.size)

        var sumAbsDiff = 0.0
        var maxAbsDiff = 0.0
        for (i in expected.indices) {
            val d = kotlin.math.abs(expected[i] - actual[i]).toDouble()
            sumAbsDiff += d
            if (d > maxAbsDiff) maxAbsDiff = d
        }
        val meanAbsDiff = sumAbsDiff / expected.size

        println("Feature comparison vs NeMo reference: meanAbsDiff=$meanAbsDiff maxAbsDiff=$maxAbsDiff " +
            "(features are per-utterance normalized, so ~unit scale; this is NOT a benchmark, it is a " +
            "correctness check against a known-good reference)")

        assertTrue("mean abs diff too high: $meanAbsDiff", meanAbsDiff < 0.05)
        assertTrue("max abs diff too high: $maxAbsDiff", maxAbsDiff < 1.0)
    }
}
