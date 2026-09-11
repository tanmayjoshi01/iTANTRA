package com.itantra.speechengine.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests for [AudioRecorder] that require a real Android
 * runtime (and, for a meaningful result, a device or emulator with a
 * working microphone and the RECORD_AUDIO permission already granted to
 * the test APK — androidx.test grants requested manifest permissions
 * automatically on API 23+ test runners).
 *
 * These tests verify that the recorder can be configured, initialized,
 * started, and stopped, and that it delivers frames while running — they
 * do not assert anything about the semantic content of the captured audio
 * (that is the VAD/segmentation layer's concern, covered by JVM unit tests
 * in src/test).
 *
 * This module declares android.permission.RECORD_AUDIO in its manifest.
 */
@RunWith(AndroidJUnit4::class)
class AudioRecorderInstrumentedTest {

    @Test
    fun recorderInitializesStartsAndStops() {
        val recorder = AudioRecorder()
        val frameLatch = CountDownLatch(1)
        var capturedError: AudioCaptureException? = null

        try {
            recorder.start(
                onFrame = { frameLatch.countDown() },
                onError = { capturedError = it },
            )

            assertTrue("recorder should report isRecording=true after start()", recorder.isRecording)

            val receivedFrame = frameLatch.await(5, TimeUnit.SECONDS)
            assertTrue(
                "expected at least one captured frame within 5s (error: $capturedError)",
                receivedFrame,
            )
        } finally {
            recorder.stop()
            recorder.release()
        }
    }

    @Test
    fun repeatedStartStopDoesNotThrow() {
        val recorder = AudioRecorder()
        try {
            repeat(3) {
                recorder.start(onFrame = {})
                recorder.stop()
            }
        } finally {
            recorder.release()
        }
    }
}
