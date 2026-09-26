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
 * - Each finished segment is handed to [recognitionDispatcher], which must
 *   run at most one task at a time: [SpeechRecognizer] does not document
 *   concurrent use, and its `recognize()` blocks for seconds.
 * - The recognizer is created lazily on that worker, since constructing it
 *   loads the model, and released there too, so it is never released while
 *   an inference is still running.
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

    private class Session(val source: AudioFrameSource, val segmenter: SpeechSegmenter) {
        @Volatile var active = true
    }

    /** Starts listening. The caller must already hold RECORD_AUDIO. No-op if already listening. */
    fun start() {
        if (currentSession.get() != null) return
        val session = Session(audioSourceFactory(), segmenterFactory())
        if (!currentSession.compareAndSet(null, session)) return
        _state.update { it.copy(isListening = true, error = null) }
        session.source.start(
            onFrame = { frame -> onFrame(session, frame) },
            onError = { error -> onCaptureError(session, error) },
        )
    }

    /**
     * Stops listening. An utterance still in progress (not yet ended by a
     * pause) is discarded; utterances already finished are still recognized.
     */
    fun stop() {
        currentSession.get()?.let { endSession(it) }
    }

    /** Reports that the user denied the microphone permission. */
    fun onPermissionDenied() {
        _state.update { it.copy(error = SpeechError.MicrophonePermissionDenied) }
    }

    /** Stops listening, drops queued recognitions, and releases the recognizer. Not reusable afterwards. */
    fun close() {
        stop()
        recognitionScope.cancel()
        // Queued behind any in-flight recognition on the same one-at-a-time dispatcher.
        CoroutineScope(recognitionDispatcher).launch {
            recognizer?.release()
            recognizer = null
        }
    }

    // Capture thread.
    private fun onFrame(session: Session, frame: AudioFrame) {
        if (!session.active) return
        val segment = session.segmenter.processFrame(frame) ?: return
        submitRecognition(segment)
    }

    // Capture thread, or the caller's thread if the source fails to start.
    private fun onCaptureError(session: Session, error: AudioCaptureException) {
        endSession(session)
        val speechError = when (error) {
            is AudioCaptureException.PermissionDenied -> SpeechError.MicrophonePermissionDenied
            else -> SpeechError.AudioCaptureFailed(error.message ?: error.javaClass.simpleName)
        }
        _state.update { it.copy(error = speechError) }
    }

    private fun endSession(session: Session) {
        if (!currentSession.compareAndSet(session, null)) return
        session.active = false
        session.source.stop()
        session.source.release()
        _state.update { it.copy(isListening = false) }
    }

    private fun submitRecognition(segment: SpeechSegment) {
        pendingRecognitions.incrementAndGet()
        publishRecognizing()
        recognitionScope.launch {
            try {
                val activeRecognizer = recognizer ?: recognizerFactory().also { recognizer = it }
                val result = activeRecognizer.recognize(segment)
                _state.update { it.copy(lastText = result.text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Model missing/unloadable or inference failure: every later
                // utterance would fail the same way, so stop listening too.
                stop()
                _state.update {
                    it.copy(error = SpeechError.RecognitionFailed(e.message ?: e.javaClass.simpleName))
                }
            } finally {
                pendingRecognitions.decrementAndGet()
                publishRecognizing()
            }
        }
    }

    private fun publishRecognizing() {
        // Read the counter inside update{} so a retried update sees the latest count.
        _state.update { it.copy(isRecognizing = pendingRecognitions.get() > 0) }
    }
}
