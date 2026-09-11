package com.itantra.speechengine.vad

import com.itantra.speechengine.audio.AudioFrame

/**
 * Classifies successive [AudioFrame]s into a speech/silence state.
 *
 * Implementations are stateful (they track hysteresis across calls to
 * [processFrame]) but must not perform I/O or depend on Android framework
 * classes, so they remain unit-testable on the JVM with synthetic PCM data.
 * Frames must be supplied in capture order; implementations are not
 * required to be thread-safe, and callers must serialize access from a
 * single processing thread/coroutine.
 */
interface VoiceActivityDetector {
    /** Processes one audio frame and returns the resulting [VadState]. */
    fun processFrame(frame: AudioFrame): VadState

    /** Resets internal hysteresis state back to [VadState.SILENCE], discarding history. */
    fun reset()
}
