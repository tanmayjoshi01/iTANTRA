package com.itantra.speechengine.stt

import com.itantra.speechengine.segmentation.SpeechSegment

/**
 * Converts a finalized [SpeechSegment] into recognized text. This is the
 * seam `architecture.md` names as Stage 2's intended integration point;
 * [IndicConformerRecognizer] is its first (Hindi-only, FP32) implementation.
 */
interface SpeechRecognizer {
    /** @throws SpeechRecognitionException on model/inference failure. */
    fun recognize(segment: SpeechSegment): SpeechRecognitionResult

    /** Releases any held native/model resources. Not reusable after this call. */
    fun release()
}
