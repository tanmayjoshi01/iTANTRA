package com.itantra.speechengine.vad

/**
 * Tunable parameters for [EnergyZcrVoiceActivityDetector].
 *
 * These defaults are a Phase 1 engineering baseline, chosen from general
 * speech-processing conventions rather than measurement against this
 * project's own microphone hardware, background-noise conditions, or a
 * selected STT model. They are expected to require tuning once real-device
 * recordings are benchmarked — see the project README, Benchmarking Plan
 * section. This VAD implementation is not yet validated for accuracy under
 * noise and should be treated as a placeholder sufficient to build and test
 * the segmentation pipeline against, not as a finished speech detector.
 */
data class VadConfig(
    /**
     * Minimum short-term RMS energy (normalized 0.0-1.0 against full-scale
     * 16-bit PCM) for a frame to be considered a speech candidate.
     */
    val energyThreshold: Double = DEFAULT_ENERGY_THRESHOLD,

    /**
     * Zero-crossing rate (fraction of sample-to-sample sign changes,
     * 0.0-1.0) must fall within this range for a frame to be considered a
     * speech candidate. This excludes very low-ZCR tonal/hum noise and very
     * high-ZCR broadband hiss as an additional, simple filter alongside the
     * energy check; it is not a validated speech/noise classifier.
     */
    val zeroCrossingRateRange: ClosedFloatingPointRange<Double> = DEFAULT_ZCR_MIN..DEFAULT_ZCR_MAX,

    /**
     * Number of consecutive speech-candidate frames required before
     * confirming [VadState.SPEECH_START]. This is the primary hysteresis
     * control against brief noise spikes being mistaken for speech.
     */
    val speechStartFrameCount: Int = DEFAULT_SPEECH_START_FRAME_COUNT,

    /**
     * Number of consecutive non-speech-candidate frames required before
     * confirming [VadState.SPEECH_END]. This is the primary hysteresis
     * control (a "hangover") against brief in-word pauses or fricative dips
     * being mistaken for the end of an utterance.
     */
    val speechEndFrameCount: Int = DEFAULT_SPEECH_END_FRAME_COUNT,
) {
    init {
        require(energyThreshold in 0.0..1.0) { "energyThreshold must be within 0.0..1.0, was $energyThreshold" }
        require(speechStartFrameCount > 0) { "speechStartFrameCount must be positive, was $speechStartFrameCount" }
        require(speechEndFrameCount > 0) { "speechEndFrameCount must be positive, was $speechEndFrameCount" }
    }

    companion object {
        const val DEFAULT_ENERGY_THRESHOLD = 0.02
        const val DEFAULT_ZCR_MIN = 0.01
        const val DEFAULT_ZCR_MAX = 0.45

        /** ~40 ms at the default 20 ms frame duration. */
        const val DEFAULT_SPEECH_START_FRAME_COUNT = 2

        /** ~300 ms hangover at the default 20 ms frame duration. */
        const val DEFAULT_SPEECH_END_FRAME_COUNT = 15
    }
}
