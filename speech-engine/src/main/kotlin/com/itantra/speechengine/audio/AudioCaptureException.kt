package com.itantra.speechengine.audio

/** Errors that can occur while initializing or running microphone capture in [AudioRecorder]. */
sealed class AudioCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * RECORD_AUDIO permission was not granted. [AudioRecorder] does not
     * request the permission itself (that requires an Activity/Fragment
     * context); the application layer must request and obtain it before
     * calling [AudioRecorder.start].
     */
    class PermissionDenied(cause: Throwable? = null) :
        AudioCaptureException("RECORD_AUDIO permission not granted", cause)

    /**
     * The device/OS refused to initialize [android.media.AudioRecord] with
     * the requested [AudioConfig] — for example an unsupported sample
     * rate/channel/format combination on this specific hardware.
     */
    class InitializationFailed(message: String, cause: Throwable? = null) :
        AudioCaptureException(message, cause)

    /** A read from the microphone failed after recording had already started. */
    class ReadFailed(message: String, cause: Throwable? = null) :
        AudioCaptureException(message, cause)
}
