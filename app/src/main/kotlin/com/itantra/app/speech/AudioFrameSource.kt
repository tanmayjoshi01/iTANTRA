package com.itantra.app.speech

import com.itantra.speechengine.audio.AudioCaptureException
import com.itantra.speechengine.audio.AudioFrame
import com.itantra.speechengine.audio.AudioRecorder

/**
 * The microphone as seen by [SpeechController]. Exists so the controller can
 * be unit-tested on the JVM: speech-engine's [AudioRecorder] is a final class
 * backed by Android's AudioRecord and cannot run there. Mirrors
 * [AudioRecorder]'s own start/stop/release contract; adds no behavior.
 */
interface AudioFrameSource {
    fun start(onFrame: (AudioFrame) -> Unit, onError: (AudioCaptureException) -> Unit)

    fun stop()

    fun release()
}

/** Production [AudioFrameSource]: delegates directly to speech-engine's [AudioRecorder]. */
class AudioRecorderFrameSource(
    private val recorder: AudioRecorder = AudioRecorder(),
) : AudioFrameSource {
    override fun start(onFrame: (AudioFrame) -> Unit, onError: (AudioCaptureException) -> Unit) =
        recorder.start(onFrame, onError)

    override fun stop() = recorder.stop()

    override fun release() = recorder.release()
}
