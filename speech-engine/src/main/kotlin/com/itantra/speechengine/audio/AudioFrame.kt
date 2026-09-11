package com.itantra.speechengine.audio

/**
 * A single chunk of raw PCM audio, captured from the microphone by
 * [AudioRecorder] or produced synthetically (e.g. in tests), tagged with the
 * format it was captured in and a capture timestamp.
 *
 * [samples] holds one PCM sample per array element, 16-bit signed, using
 * Kotlin's native [Short] range, interleaved by channel if
 * [config].channelCount is greater than 1. This class has no dependency on
 * the Android framework.
 */
data class AudioFrame(
    val samples: ShortArray,
    val config: AudioConfig,
    val timestampMs: Long,
) {
    /** Duration of this frame's audio, derived from sample count and format rather than from [timestampMs]. */
    val durationMs: Long
        get() = (samples.size.toLong() * 1000L) / (config.sampleRateHz.toLong() * config.channelCount)

    // ShortArray does not have structural equals()/hashCode(); a data class
    // holding one must override both explicitly, or equality/collection
    // behavior would silently fall back to reference identity for `samples`.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return samples.contentEquals(other.samples) &&
            config == other.config &&
            timestampMs == other.timestampMs
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + config.hashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}
