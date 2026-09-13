package com.itantra.speechengine.stt

/**
 * Terminal Stage 2 output: recognized text for one [com.itantra.speechengine.segmentation.SpeechSegment].
 * Carries no transport/UI concern by design, mirroring `SpeechSegment`'s own
 * boundary (see `architecture.md`).
 */
data class SpeechRecognitionResult(
    val text: String,
    val language: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val inferenceTimeMs: Long,
)
