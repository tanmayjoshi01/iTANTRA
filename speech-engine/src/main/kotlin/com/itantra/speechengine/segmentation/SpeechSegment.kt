package com.itantra.speechengine.segmentation

import com.itantra.speechengine.audio.AudioConfig

/**
 * A finalized, bounded span of speech audio produced by [SpeechSegmenter]
 * once a pause (or the configured maximum duration) ends an utterance.
 *
 * This is Phase 1's terminal output and the intended integration point for
 * a future offline STT stage: [samples] plus [config] carry everything an
 * STT engine needs to know about the audio's format, and
 * [startTimestampMs]/[endTimestampMs] preserve timing for logging and
 * diagnostics without requiring the STT stage to re-derive it. No text,
 * language, or transport concern is represented here — those belong to
 * later phases.
 */
data class SpeechSegment(
    val samples: ShortArray,
    val config: AudioConfig,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
) {
    val durationMs: Long
        get() = endTimestampMs - startTimestampMs

    // ShortArray does not have structural equals()/hashCode(); see AudioFrame for the same note.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeechSegment) return false
        return samples.contentEquals(other.samples) &&
            config == other.config &&
            startTimestampMs == other.startTimestampMs &&
            endTimestampMs == other.endTimestampMs
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + config.hashCode()
        result = 31 * result + startTimestampMs.hashCode()
        result = 31 * result + endTimestampMs.hashCode()
        return result
    }
}
