package com.itantra.speechengine.audio

/**
 * Immutable description of the PCM audio format used throughout the speech
 * engine pipeline: capture, VAD, and segmentation all operate on frames
 * described by one of these.
 *
 * The defaults below are a Phase 1 engineering baseline for offline speech
 * processing on Android, not a benchmarked-optimal configuration. In
 * particular [sampleRateHz] should be re-evaluated once a specific offline
 * STT model is selected in a later phase: ASR models are typically trained
 * against a fixed sample rate, and mismatched input generally requires
 * resampling or degrades recognition accuracy. See the project README,
 * Benchmarking Plan section.
 *
 * This class has no dependency on the Android framework so it can be used
 * and unit-tested identically on the JVM and on-device.
 */
data class AudioConfig(
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16,
    val frameDurationMs: Int = DEFAULT_FRAME_DURATION_MS,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
        require(channelCount == 1 || channelCount == 2) {
            "channelCount must be 1 (mono) or 2 (stereo), was $channelCount"
        }
        require(bitsPerSample == 16) { "Only 16-bit PCM is currently supported, was $bitsPerSample" }
        require(frameDurationMs > 0) { "frameDurationMs must be positive, was $frameDurationMs" }
    }

    /** Number of PCM samples (per channel) contained in one frame of [frameDurationMs]. */
    val samplesPerFrame: Int
        get() = sampleRateHz * frameDurationMs / 1000

    /** Number of bytes in one frame, accounting for [channelCount] and [bitsPerSample]. */
    val bytesPerFrame: Int
        get() = samplesPerFrame * channelCount * (bitsPerSample / 8)

    companion object {
        /**
         * 16 kHz mono is a common baseline sample rate for offline speech
         * recognition (many Kaldi- and Whisper-derived ASR models expect
         * 16 kHz mono input). Not yet benchmarked against a specific model
         * selected for this project.
         */
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000

        /**
         * 20 ms is a conventional speech-frame size (also used by, e.g.,
         * WebRTC's own VAD) balancing VAD/segmentation responsiveness
         * against per-frame processing overhead. Not yet benchmarked for
         * this project's specific VAD implementation.
         */
        const val DEFAULT_FRAME_DURATION_MS = 20
    }
}
