package com.itantra.app.speech

/**
 * Observable speech state for the UI. Listening and recognizing are
 * independent flags rather than one enum because capture keeps running while
 * earlier utterances are still being recognized, so both can be true at once.
 */
data class SpeechState(
    val isListening: Boolean = false,
    /** True while at least one finished utterance is queued for or undergoing recognition. */
    val isRecognizing: Boolean = false,
    val lastText: String? = null,
    val error: SpeechError? = null,
)

sealed interface SpeechError {
    data object MicrophonePermissionDenied : SpeechError

    data class AudioCaptureFailed(val message: String) : SpeechError

    /** Includes recognizer creation failures, e.g. the model file being absent. */
    data class RecognitionFailed(val message: String) : SpeechError
}
