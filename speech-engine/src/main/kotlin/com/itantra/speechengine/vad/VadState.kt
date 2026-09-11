package com.itantra.speechengine.vad

/**
 * Result of classifying one [com.itantra.speechengine.audio.AudioFrame].
 *
 * [SPEECH_START] and [SPEECH_END] are one-frame edge events: they are
 * returned exactly on the frame that confirms a transition, and are followed
 * by [SPEECH] or [SILENCE] respectively on the next frame if the condition
 * persists. Callers that only need "is this frame part of active speech"
 * should treat [SPEECH_START] and [SPEECH] as speech-active, and [SILENCE] /
 * [SPEECH_END] as not.
 */
enum class VadState {
    SILENCE,
    SPEECH_START,
    SPEECH,
    SPEECH_END,
}
