package com.itantra.speechengine

import com.itantra.speechengine.audio.AudioConfig
import com.itantra.speechengine.audio.AudioFrame
import kotlin.math.PI
import kotlin.math.sin

/**
 * Deterministic synthetic PCM signal generators used by VAD/segmentation
 * unit tests, so tests do not depend on a real microphone or a recorded
 * audio fixture and produce identical results on every run.
 */
object TestAudioSignals {

    /** A frame of digital silence (all-zero samples). */
    fun silenceFrame(config: AudioConfig, timestampMs: Long = 0L): AudioFrame =
        AudioFrame(ShortArray(config.samplesPerFrame), config, timestampMs)

    /**
     * A synthetic tone standing in for "speech-like" audio: sufficient RMS
     * energy and a zero-crossing rate within [EnergyZcrVoiceActivityDetector]'s
     * default speech band, by construction. This is not a claim that a pure
     * tone is acoustically similar to real speech — only that it reliably
     * and deterministically exercises the VAD's "is this frame active"
     * branch in tests, in place of a recorded voice sample.
     */
    fun speechLikeFrame(
        config: AudioConfig,
        timestampMs: Long = 0L,
        frequencyHz: Double = 300.0,
        amplitude: Double = 0.3,
    ): AudioFrame {
        val samples = ShortArray(config.samplesPerFrame) { i ->
            val t = i.toDouble() / config.sampleRateHz
            (amplitude * Short.MAX_VALUE * sin(2 * PI * frequencyHz * t)).toInt().toShort()
        }
        return AudioFrame(samples, config, timestampMs)
    }

    /** A frame with no samples at all, to exercise empty/invalid-frame handling. */
    fun emptyFrame(config: AudioConfig, timestampMs: Long = 0L): AudioFrame =
        AudioFrame(ShortArray(0), config, timestampMs)
}
