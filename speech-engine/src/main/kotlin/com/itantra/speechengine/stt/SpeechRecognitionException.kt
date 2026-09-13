package com.itantra.speechengine.stt

/** Sealed failure hierarchy for [SpeechRecognizer], mirroring `AudioCaptureException`'s pattern. */
sealed class SpeechRecognitionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ModelNotFound(path: String) :
        SpeechRecognitionException("STT model file not found at $path")

    class ModelLoadFailed(message: String, cause: Throwable? = null) :
        SpeechRecognitionException(message, cause)

    class InferenceFailed(message: String, cause: Throwable? = null) :
        SpeechRecognitionException(message, cause)
}
