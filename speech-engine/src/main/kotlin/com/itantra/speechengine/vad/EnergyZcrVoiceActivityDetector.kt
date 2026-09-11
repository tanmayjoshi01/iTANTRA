package com.itantra.speechengine.vad

import com.itantra.speechengine.audio.AudioFrame
import kotlin.math.sqrt

/**
 * Lightweight, dependency-free voice activity detector based on short-term
 * frame energy (RMS) and zero-crossing rate (ZCR), with temporal hysteresis
 * (consecutive-frame counting) to avoid rapid SPEECH/SILENCE flapping
 * around the threshold.
 *
 * This is the Phase 1 baseline VAD for iTANTRA: a genuine, audio-derived
 * classifier (not a timer or hard-coded detector), fully configurable via
 * [VadConfig], and requiring no third-party dependency or model file. It
 * has not been benchmarked against real-world background noise, multiple
 * speakers, or the eventual offline STT front end; default thresholds are
 * engineering starting points, not measured-optimal values. See the
 * project README, Benchmarking Plan section, for planned evaluation.
 *
 * Not thread-safe; intended to be driven from a single audio-processing
 * thread/coroutine in frame-arrival order.
 */
class EnergyZcrVoiceActivityDetector(
    private val config: VadConfig = VadConfig(),
) : VoiceActivityDetector {

    private var state: VadState = VadState.SILENCE
    private var consecutiveActiveFrames = 0
    private var consecutiveInactiveFrames = 0

    override fun processFrame(frame: AudioFrame): VadState {
        val candidate = isSpeechCandidate(frame)

        if (candidate) {
            consecutiveActiveFrames++
            consecutiveInactiveFrames = 0
        } else {
            consecutiveInactiveFrames++
            consecutiveActiveFrames = 0
        }

        state = when (state) {
            VadState.SILENCE, VadState.SPEECH_END ->
                if (consecutiveActiveFrames >= config.speechStartFrameCount) {
                    VadState.SPEECH_START
                } else {
                    VadState.SILENCE
                }

            VadState.SPEECH_START ->
                if (candidate) VadState.SPEECH else VadState.SILENCE

            VadState.SPEECH ->
                if (consecutiveInactiveFrames >= config.speechEndFrameCount) {
                    VadState.SPEECH_END
                } else {
                    VadState.SPEECH
                }
        }

        return state
    }

    override fun reset() {
        state = VadState.SILENCE
        consecutiveActiveFrames = 0
        consecutiveInactiveFrames = 0
    }

    private fun isSpeechCandidate(frame: AudioFrame): Boolean {
        val samples = frame.samples
        if (samples.isEmpty()) return false

        val energy = rmsEnergy(samples)
        val zcr = zeroCrossingRate(samples)

        return energy >= config.energyThreshold && zcr in config.zeroCrossingRateRange
    }

    private fun rmsEnergy(samples: ShortArray): Double {
        var sumSquares = 0.0
        for (sample in samples) {
            val normalized = sample.toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
        }
        return sqrt(sumSquares / samples.size)
    }

    private fun zeroCrossingRate(samples: ShortArray): Double {
        if (samples.size < 2) return 0.0
        var crossings = 0
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val curr = samples[i]
            if ((prev >= 0 && curr < 0) || (prev < 0 && curr >= 0)) {
                crossings++
            }
        }
        return crossings.toDouble() / (samples.size - 1)
    }
}
