package com.itantra.app.speech

import com.itantra.speechengine.audio.AudioCaptureException
import com.itantra.speechengine.audio.AudioConfig
import com.itantra.speechengine.audio.AudioFrame
import com.itantra.speechengine.segmentation.SegmenterConfig
import com.itantra.speechengine.segmentation.SpeechSegment
import com.itantra.speechengine.segmentation.SpeechSegmenter
import com.itantra.speechengine.stt.SpeechRecognitionException
import com.itantra.speechengine.stt.SpeechRecognitionResult
import com.itantra.speechengine.stt.SpeechRecognizer
import com.itantra.speechengine.vad.VadState
import com.itantra.speechengine.vad.VoiceActivityDetector

/** Deterministic [SpeechRecognizer]; never touches ONNX Runtime. */
class FakeSpeechRecognizer(
    private val text: String = "नमस्ते",
    private val failure: Exception? = null,
) : SpeechRecognizer {
    val recognizedSegments = mutableListOf<SpeechSegment>()
    var released = false
        private set

    override fun recognize(segment: SpeechSegment): SpeechRecognitionResult {
        recognizedSegments += segment
        failure?.let { throw it }
        return SpeechRecognitionResult(
            text = text,
            language = "hi",
            startTimestampMs = segment.startTimestampMs,
            endTimestampMs = segment.endTimestampMs,
            inferenceTimeMs = 0L,
        )
    }

    override fun release() {
        released = true
    }

    companion object {
        fun failing() = FakeSpeechRecognizer(failure = SpeechRecognitionException.InferenceFailed("fake inference failure"))
    }
}

/** [AudioFrameSource] driven by the test: frames and errors are delivered synchronously via [emit]/[fail]. */
class FakeAudioFrameSource : AudioFrameSource {
    private var onFrame: ((AudioFrame) -> Unit)? = null
    private var onError: ((AudioCaptureException) -> Unit)? = null
    var started = false
        private set
    var stopped = false
        private set
    var released = false
        private set

    override fun start(onFrame: (AudioFrame) -> Unit, onError: (AudioCaptureException) -> Unit) {
        started = true
        this.onFrame = onFrame
        this.onError = onError
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        released = true
    }

    fun emit(frame: AudioFrame) = onFrame!!.invoke(frame)

    fun fail(error: AudioCaptureException) = onError!!.invoke(error)
}

/** Treats any frame with non-zero samples as speech, so tests control segmentation exactly. */
private class NonZeroIsSpeechVad : VoiceActivityDetector {
    override fun processFrame(frame: AudioFrame): VadState =
        if (frame.samples.any { it != 0.toShort() }) VadState.SPEECH else VadState.SILENCE

    override fun reset() = Unit
}

private val config = AudioConfig() // 16 kHz mono, 20 ms frames

/**
 * Real speech-engine segmenter with a scripted VAD: finalizes after two
 * silent frames (40 ms), keeps every segment, no pre-roll.
 */
fun testSegmenter() = SpeechSegmenter(
    NonZeroIsSpeechVad(),
    SegmenterConfig(
        silenceDurationMs = 40,
        maxSegmentDurationMs = 10_000,
        minSegmentDurationMs = 0,
        preRollFrameCount = 0,
    ),
)

fun speechFrame(timestampMs: Long) = AudioFrame(ShortArray(config.samplesPerFrame) { 1000 }, config, timestampMs)

fun silenceFrame(timestampMs: Long) = AudioFrame(ShortArray(config.samplesPerFrame), config, timestampMs)

/** Emits one utterance: two speech frames then the two silent frames that complete the segment. */
fun FakeAudioFrameSource.emitUtterance(startMs: Long = 0L) {
    emit(speechFrame(startMs))
    emit(speechFrame(startMs + 20))
    emit(silenceFrame(startMs + 40))
    emit(silenceFrame(startMs + 60))
}
