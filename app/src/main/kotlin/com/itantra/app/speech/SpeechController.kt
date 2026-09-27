package com.itantra.app.speech

import com.itantra.speechengine.audio.AudioCaptureException
import com.itantra.speechengine.audio.AudioFrame
import com.itantra.speechengine.segmentation.SpeechSegment
import com.itantra.speechengine.segmentation.SpeechSegmenter
import com.itantra.speechengine.stt.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Wires speech-engine's public pipeline (microphone -> [SpeechSegmenter] ->
 * [SpeechRecognizer]) into observable app [state]. Knows nothing about the
 * model, features, or decoding behind [SpeechRecognizer].
 *
 * Threading:
 * - [start], [stop], [close] are called from the main thread.
 * - Frames arrive on the audio source's capture thread; only the (cheap)
 *   segmenter runs there, so capture is never blocked by recognition.
 * - Everything touching the recognizer runs on [recognitionDispatcher], which
 *   must run at most one task at a time: [SpeechRecognizer] does not document
 *   concurrent use, and its `recognize()` blocks for seconds.
 * - The recognizer is created (the model loaded) on that worker as soon as
 *   listening starts, so a missing model is reported immediately and the
 *   load overlaps with the user speaking. Utterances finished during the load
 *   queue behind it. It is released on the same worker, so never while an
 *   inference is still running.
 *
 * Each [start] creates a fresh audio source and segmenter: speech-engine's
 * AudioRecorder must be released after an error, and a per-session segmenter
 * cannot receive a late frame from a previous session.
 */
class SpeechController(
    private val audioSourceFactory: () -> AudioFrameSource,
    private val segmenterFactory: () -> SpeechSegmenter,
    private val recognizerFactory: () -> SpeechRecognizer,
    private val recognitionDispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val recognitionScope = CoroutineScope(SupervisorJob() + recognitionDispatcher)
    private val pendingRecognitions = AtomicInteger(0)
    private val currentSession = AtomicReference<Session?>(null)

    // Touched only by tasks on recognitionDispatcher, which runs one at a time.
    private var recognizer: SpeechRecognizer? = null

    /**
     * [segmenter] is used from the capture thread (frames) and from the
     * caller of [stop] (flush); both hold the session's monitor, which also
     * guards [active].
     */
    private class Session(val source: AudioFrameSource, val segmenter: SpeechSegmenter) {
        var active = true
    }

    /** Starts listening. The caller must already hold RECORD_AUDIO. No-op if already listening. */
    fun start() {
        if (currentSession.get() != null) return
        val session = Session(audioSourceFactory(), segmenterFactory())
        if (!currentSession.compareAndSet(null, session)) return
        _state.update { it.copy(isListening = true, error = null) }
        preloadRecognizer()
        session.source.start(
            onFrame = { frame -> onFrame(session, frame) },
            onError = { error -> onCaptureError(session, error) },
        )
    }

    /**
     * Stops listening and recognizes the utterance spoken so far, without
     * waiting for a pause. Audio still in the platform's capture buffer when
     * Stop is pressed (at most a few frames) is not included.
     */
    fun stop() {
        currentSession.get()?.let { endSession(it, finishUtterance = true) }
    }

    /**
     * Starts a new transcript session by emptying [SpeechState.transcript].
     * The transcript is otherwise kept for the controller's lifetime, across
     * Start/Stop cycles and errors. A recognition still in flight appends to
     * the new session, so callers should clear only when idle.
     */
    fun clearTranscript() {
        _state.update { it.copy(transcript = emptyList()) }
    }

    /** Reports that the user denied the microphone permission. */
    fun onPermissionDenied() {
        _state.update { it.copy(error = SpeechError.MicrophonePermissionDenied) }
    }

    /** Stops listening, drops queued recognitions, and releases the recognizer. Not reusable afterwards. */
    fun close() {
        currentSession.get()?.let { endSession(it, finishUtterance = false) }
        recognitionScope.cancel()
        // Queued behind any in-flight task on the same one-at-a-time dispatcher.
        CoroutineScope(recognitionDispatcher).launch {
            recognizer?.release()
            recognizer = null
        }
    }

    // Capture thread.
    private fun onFrame(session: Session, frame: AudioFrame) {
        val segment = synchronized(session) {
            if (!session.active) return
            session.segmenter.processFrame(frame)
        } ?: return
        submitRecognition(segment)
    }

    // Capture thread, or the caller's thread if the source fails to start.
    private fun onCaptureError(session: Session, error: AudioCaptureException) {
        endSession(session, finishUtterance = false)
        val speechError = when (error) {
            is AudioCaptureException.PermissionDenied -> SpeechError.MicrophonePermissionDenied
            else -> SpeechError.AudioCaptureFailed(error.message ?: error.javaClass.simpleName)
        }
        _state.update { it.copy(error = speechError) }
    }

    /**
     * [finishUtterance]: recognize the utterance in progress (user stop) or
     * discard it (errors, teardown).
     */
    private fun endSession(session: Session, finishUtterance: Boolean) {
        if (!currentSession.compareAndSet(session, null)) return
        val finalSegment = synchronized(session) {
            session.active = false
            if (finishUtterance) session.segmenter.flush() else null
        }
        session.source.stop()
        session.source.release()
        _state.update { it.copy(isListening = false) }
        finalSegment?.let(::submitRecognition)
    }

    private fun preloadRecognizer() {
        recognitionScope.launch {
            if (recognizer != null) return@launch
            _state.update { it.copy(isLoadingModel = true) }
            try {
                recognizer = recognizerFactory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onRecognitionFailure(e)
            } finally {
                _state.update { it.copy(isLoadingModel = false) }
            }
        }
    }

    private fun submitRecognition(segment: SpeechSegment) {
        pendingRecognitions.incrementAndGet()
        publishRecognizing()
        recognitionScope.launch {
            try {
                // Normally created by preloadRecognizer(); created here only if that failed.
                val activeRecognizer = recognizer ?: recognizerFactory().also { recognizer = it }
                val result = activeRecognizer.recognize(segment)
                val entry = TranscriptEntry(result, audioDurationMs(segment))
                _state.update { it.copy(transcript = it.transcript + entry) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onRecognitionFailure(e)
            } finally {
                pendingRecognitions.decrementAndGet()
                publishRecognizing()
            }
        }
    }

    // Model missing/unloadable or inference failure: every later utterance
    // would fail the same way, so stop listening too (discarding the
    // utterance in progress, which could not be recognized either).
    private fun onRecognitionFailure(e: Exception) {
        currentSession.get()?.let { endSession(it, finishUtterance = false) }
        _state.update {
            it.copy(error = SpeechError.RecognitionFailed(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun audioDurationMs(segment: SpeechSegment): Long =
        segment.samples.size * 1000L / (segment.config.sampleRateHz.toLong() * segment.config.channelCount)

    private fun publishRecognizing() {
        // Read the counter inside update{} so a retried update sees the latest count.
        _state.update { it.copy(isRecognizing = pendingRecognitions.get() > 0) }
    }
}
