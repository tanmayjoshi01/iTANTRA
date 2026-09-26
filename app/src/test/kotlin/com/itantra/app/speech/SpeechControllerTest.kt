package com.itantra.app.speech

import com.itantra.speechengine.audio.AudioCaptureException
import com.itantra.speechengine.stt.SpeechRecognizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechControllerTest {

    private val sources = mutableListOf<FakeAudioFrameSource>()
    private val source get() = sources.last()
    private var recognizerCreations = 0

    /**
     * The recognition worker is a StandardTestDispatcher: queued work only runs
     * when the test advances it, so "not run inside the audio callback" is
     * checked by ordering, not by timing.
     */
    private fun TestScope.controller(
        recognizer: SpeechRecognizer = FakeSpeechRecognizer(),
        recognizerFactory: () -> SpeechRecognizer = { recognizerCreations++; recognizer },
    ) = SpeechController(
        audioSourceFactory = { FakeAudioFrameSource().also { sources += it } },
        segmenterFactory = ::testSegmenter,
        recognizerFactory = recognizerFactory,
        recognitionDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun initialState_isIdle() = runTest {
        assertEquals(SpeechState(), controller().state.value)
    }

    @Test
    fun start_startsAudioSourceAndReportsListening() = runTest {
        val controller = controller()
        controller.start()

        assertTrue(source.started)
        assertTrue(controller.state.value.isListening)
        assertNull(controller.state.value.error)
    }

    @Test
    fun start_whileListening_isNoOp() = runTest {
        val controller = controller()
        controller.start()
        controller.start()

        assertEquals(1, sources.size)
    }

    @Test
    fun completedSegment_isPassedToRecognizer() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val controller = controller(recognizer)
        controller.start()

        source.emitUtterance(startMs = 1_000)
        advanceUntilIdle()

        val segment = recognizer.recognizedSegments.single()
        assertEquals(4 * 320, segment.samples.size) // 2 speech + 2 silence frames
        assertEquals(1_000L, segment.startTimestampMs)
    }

    @Test
    fun framesWithoutFinishedUtterance_doNotReachRecognizer() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val controller = controller(recognizer)
        controller.start()

        source.emit(speechFrame(0))
        source.emit(speechFrame(20))
        advanceUntilIdle()

        assertTrue(recognizer.recognizedSegments.isEmpty())
        assertEquals(0, recognizerCreations)
    }

    @Test
    fun recognition_runsOnWorker_notInsideAudioCallback() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val controller = controller(recognizer)
        controller.start()

        source.emitUtterance()

        // The callback has returned, but nothing has been recognized yet: the
        // segment is only queued on the worker.
        assertTrue(recognizer.recognizedSegments.isEmpty())
        assertEquals(0, recognizerCreations)
        assertTrue(controller.state.value.isRecognizing)
        assertTrue(controller.state.value.isListening)

        advanceUntilIdle()

        assertEquals(1, recognizer.recognizedSegments.size)
        assertFalse(controller.state.value.isRecognizing)
    }

    @Test
    fun recognizedText_reachesState_andListeningContinues() = runTest {
        val controller = controller(FakeSpeechRecognizer(text = "नमस्ते दुनिया"))
        controller.start()

        source.emitUtterance()
        advanceUntilIdle()

        assertEquals("नमस्ते दुनिया", controller.state.value.lastText)
        assertTrue(controller.state.value.isListening)
        assertNull(controller.state.value.error)
    }

    @Test
    fun severalUtterances_useOneRecognizerInstance_inOrder() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val controller = controller(recognizer)
        controller.start()

        source.emitUtterance(startMs = 0)
        source.emitUtterance(startMs = 1_000)
        advanceUntilIdle()

        assertEquals(1, recognizerCreations)
        assertEquals(listOf(0L, 1_000L), recognizer.recognizedSegments.map { it.startTimestampMs })
    }

    @Test
    fun recognitionFailure_becomesError_andStopsListening() = runTest {
        val controller = controller(FakeSpeechRecognizer.failing())
        controller.start()

        source.emitUtterance()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(SpeechError.RecognitionFailed("fake inference failure"), state.error)
        assertFalse(state.isListening)
        assertFalse(state.isRecognizing)
        assertTrue(source.stopped)
        assertTrue(source.released)
    }

    @Test
    fun recognizerCreationFailure_becomesError() = runTest {
        val controller = controller(recognizerFactory = { throw IllegalStateException("model file missing") })
        controller.start()

        source.emitUtterance()
        advanceUntilIdle()

        assertEquals(SpeechError.RecognitionFailed("model file missing"), controller.state.value.error)
        assertFalse(controller.state.value.isListening)
    }

    @Test
    fun audioCaptureError_becomesError_andReleasesSource() = runTest {
        val controller = controller()
        controller.start()

        source.fail(AudioCaptureException.ReadFailed("read error"))

        assertEquals(SpeechError.AudioCaptureFailed("read error"), controller.state.value.error)
        assertFalse(controller.state.value.isListening)
        assertTrue(source.released)
    }

    @Test
    fun capturePermissionError_mapsToPermissionDenied() = runTest {
        val controller = controller()
        controller.start()

        source.fail(AudioCaptureException.PermissionDenied())

        assertEquals(SpeechError.MicrophonePermissionDenied, controller.state.value.error)
    }

    @Test
    fun permissionDenied_isReported_andStartClearsIt() = runTest {
        val controller = controller()
        controller.onPermissionDenied()
        assertEquals(SpeechError.MicrophonePermissionDenied, controller.state.value.error)
        assertFalse(controller.state.value.isListening)

        controller.start()
        assertNull(controller.state.value.error)
    }

    @Test
    fun stop_stopsAndReleasesSource_andIgnoresLateFrames() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val controller = controller(recognizer)
        controller.start()
        val stoppedSource = source

        controller.stop()
        // AudioRecorder may still deliver a frame from an in-flight read after stop().
        stoppedSource.emitUtterance()
        advanceUntilIdle()

        assertTrue(stoppedSource.stopped)
        assertTrue(stoppedSource.released)
        assertFalse(controller.state.value.isListening)
        assertTrue(recognizer.recognizedSegments.isEmpty())
    }

    @Test
    fun start_afterStop_usesFreshAudioSource() = runTest {
        val controller = controller()
        controller.start()
        controller.stop()
        controller.start()

        assertEquals(2, sources.size)
        assertTrue(sources.last().started)
        assertTrue(controller.state.value.isListening)
    }

    @Test
    fun close_dropsQueuedRecognitions() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val controller = controller(recognizer)
        controller.start()
        source.emitUtterance() // queued on the worker, not yet run

        controller.close()
        advanceUntilIdle()

        assertTrue(recognizer.recognizedSegments.isEmpty())
    }

    @Test
    fun close_releasesRecognizerAndSource() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val controller = controller(recognizer)
        controller.start()
        source.emitUtterance()
        advanceUntilIdle()

        controller.close()
        advanceUntilIdle()

        assertTrue(recognizer.released)
        assertTrue(source.released)
        assertFalse(controller.state.value.isListening)
    }

    @Test
    fun close_withoutAnyRecognition_neverCreatesRecognizer() = runTest {
        val controller = controller()
        controller.start()

        controller.close()
        advanceUntilIdle()

        assertEquals(0, recognizerCreations)
    }
}
