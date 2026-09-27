package com.itantra.app.speech

import com.itantra.speechengine.stt.SpeechRecognitionResult

/**
 * Observable speech state for the UI. Listening, loading and recognizing are
 * independent flags rather than one enum because they overlap: capture runs
 * while the model loads, and while earlier utterances are recognized.
 */
data class SpeechState(
    val isListening: Boolean = false,
    /** True while the recognizer (and its model) is being created. */
    val isLoadingModel: Boolean = false,
    /** True while at least one finished utterance is queued for or undergoing recognition. */
    val isRecognizing: Boolean = false,
    /**
     * Every recognized utterance of the session, oldest first. Kept across
     * Start/Stop cycles and errors; emptied only by [SpeechController.clearTranscript].
     */
    val transcript: List<TranscriptEntry> = emptyList(),
    val error: SpeechError? = null,
)

/** One recognized utterance. */
data class TranscriptEntry(
    val result: SpeechRecognitionResult,
    /**
     * Length of the recognized audio, from its sample count. More exact than
     * the result's timestamps, which are wall-clock frame read times.
     */
    val audioDurationMs: Long,
)

sealed interface SpeechError {
    data object MicrophonePermissionDenied : SpeechError

    data class AudioCaptureFailed(val message: String) : SpeechError

    /** Includes recognizer creation failures, e.g. the model file being absent. */
    data class RecognitionFailed(val message: String) : SpeechError
}
