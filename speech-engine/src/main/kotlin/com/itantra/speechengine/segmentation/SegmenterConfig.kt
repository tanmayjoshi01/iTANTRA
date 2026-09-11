package com.itantra.speechengine.segmentation

/**
 * Tunable parameters controlling when [SpeechSegmenter] finalizes an
 * in-progress speech segment.
 *
 * These defaults are a Phase 1 engineering baseline for turning continuous
 * speech into sentence/utterance-sized segments; they have not been
 * benchmarked against real conversational pause patterns or a selected STT
 * model, and are expected to require tuning (see project README,
 * Benchmarking Plan section).
 */
data class SegmenterConfig(
    /**
     * Duration of continuous non-speech audio, measured from the point the
     * VAD itself first reports non-speech ([com.itantra.speechengine.vad.VadState.SPEECH_END]
     * / [com.itantra.speechengine.vad.VadState.SILENCE]), after which an
     * in-progress segment is finalized.
     *
     * This is additive with the VAD's own end-of-speech hysteresis
     * ([com.itantra.speechengine.vad.VadConfig.speechEndFrameCount]): while
     * the VAD's hangover is still bridging a dip, it keeps reporting
     * [com.itantra.speechengine.vad.VadState.SPEECH], which this segmenter
     * treats as "still speaking" and does not count toward
     * [silenceDurationMs]. The practical pause length before an utterance
     * is finalized is therefore approximately
     * `(speechEndFrameCount - 1) * frameDurationMs + silenceDurationMs`,
     * not [silenceDurationMs] alone. Tune both values together.
     */
    val silenceDurationMs: Long = DEFAULT_SILENCE_DURATION_MS,

    /**
     * Hard cap on a single segment's duration; a segment is force-finalized
     * at this length even if speech is still ongoing, so a single utterance
     * cannot grow unbounded and audio is never retained indefinitely.
     */
    val maxSegmentDurationMs: Long = DEFAULT_MAX_SEGMENT_DURATION_MS,

    /**
     * Segments shorter than this are discarded (not emitted) rather than
     * returned, filtering out brief noise bursts that briefly pass the VAD
     * so that only reasonably "meaningful" utterance-sized segments reach
     * the caller. This is a deliberate addition beyond a minimal
     * implementation; set to 0 to disable.
     */
    val minSegmentDurationMs: Long = DEFAULT_MIN_SEGMENT_DURATION_MS,

    /**
     * Number of frames immediately preceding a confirmed speech start to
     * prepend to the segment. [com.itantra.speechengine.vad.VadConfig]'s
     * own speechStartFrameCount means a real VAD implementation only
     * confirms speech a few frames after it actually began; without a
     * pre-roll, those leading frames would be silently dropped and the
     * start of the utterance would be clipped. This is a deliberate
     * addition beyond a minimal implementation; set to 0 to disable.
     */
    val preRollFrameCount: Int = DEFAULT_PRE_ROLL_FRAME_COUNT,
) {
    init {
        require(silenceDurationMs > 0) { "silenceDurationMs must be positive, was $silenceDurationMs" }
        require(maxSegmentDurationMs > silenceDurationMs) {
            "maxSegmentDurationMs ($maxSegmentDurationMs) must be greater than silenceDurationMs ($silenceDurationMs)"
        }
        require(minSegmentDurationMs >= 0) { "minSegmentDurationMs must not be negative, was $minSegmentDurationMs" }
        require(preRollFrameCount >= 0) { "preRollFrameCount must not be negative, was $preRollFrameCount" }
    }

    companion object {
        const val DEFAULT_SILENCE_DURATION_MS = 600L
        const val DEFAULT_MAX_SEGMENT_DURATION_MS = 15_000L
        const val DEFAULT_MIN_SEGMENT_DURATION_MS = 250L
        const val DEFAULT_PRE_ROLL_FRAME_COUNT = 5
    }
}
