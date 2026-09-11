package com.itantra.speechengine.vad

import com.itantra.speechengine.TestAudioSignals
import com.itantra.speechengine.audio.AudioConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class EnergyZcrVoiceActivityDetectorTest {

    private val audioConfig = AudioConfig()
    private val vadConfig = VadConfig(speechStartFrameCount = 2, speechEndFrameCount = 3)

    private fun newVad() = EnergyZcrVoiceActivityDetector(vadConfig)

    @Test
    fun `initial silence stays SILENCE`() {
        val vad = newVad()
        repeat(5) {
            assertEquals(VadState.SILENCE, vad.processFrame(TestAudioSignals.silenceFrame(audioConfig)))
        }
    }

    @Test
    fun `single speech-like frame is not enough to confirm speech start`() {
        val vad = newVad()
        // speechStartFrameCount = 2, so one active frame alone must not confirm start yet.
        val state = vad.processFrame(TestAudioSignals.speechLikeFrame(audioConfig))
        assertEquals(VadState.SILENCE, state)
    }

    @Test
    fun `speech start is confirmed after configured consecutive active frames`() {
        val vad = newVad()
        vad.processFrame(TestAudioSignals.speechLikeFrame(audioConfig)) // 1st active frame
        val state = vad.processFrame(TestAudioSignals.speechLikeFrame(audioConfig)) // 2nd -> confirms
        assertEquals(VadState.SPEECH_START, state)
    }

    @Test
    fun `continuous speech stays in SPEECH state`() {
        val vad = newVad()
        repeat(vadConfig.speechStartFrameCount) { vad.processFrame(TestAudioSignals.speechLikeFrame(audioConfig)) }
        repeat(10) {
            assertEquals(VadState.SPEECH, vad.processFrame(TestAudioSignals.speechLikeFrame(audioConfig)))
        }
    }

    @Test
    fun `brief single silent frame during speech does not trigger speech end`() {
        val vad = newVad()
        repeat(vadConfig.speechStartFrameCount + 1) { vad.processFrame(TestAudioSignals.speechLikeFrame(audioConfig)) }

        // speechEndFrameCount = 3; a single silent frame must not yet end speech.
        val state = vad.processFrame(TestAudioSignals.silenceFrame(audioConfig))
        assertEquals(VadState.SPEECH, state)
    }

    @Test
    fun `speech end is confirmed after configured consecutive silent frames`() {
        val vad = newVad()
        repeat(vadConfig.speechStartFrameCount + 1) { vad.processFrame(TestAudioSignals.speechLikeFrame(audioConfig)) }

        var lastState: VadState = VadState.SPEECH
        repeat(vadConfig.speechEndFrameCount) {
            lastState = vad.processFrame(TestAudioSignals.silenceFrame(audioConfig))
        }
        assertEquals(VadState.SPEECH_END, lastState)

        // The frame after the SPEECH_END edge should settle back into SILENCE.
        assertEquals(VadState.SILENCE, vad.processFrame(TestAudioSignals.silenceFrame(audioConfig)))
    }

    @Test
    fun `empty frame is treated as inactive without throwing`() {
        val vad = newVad()
        assertEquals(VadState.SILENCE, vad.processFrame(TestAudioSignals.emptyFrame(audioConfig)))
    }

    @Test
    fun `reset returns detector to initial SILENCE state`() {
        val vad = newVad()
        repeat(vadConfig.speechStartFrameCount + 2) { vad.processFrame(TestAudioSignals.speechLikeFrame(audioConfig)) }
        vad.reset()
        assertEquals(VadState.SILENCE, vad.processFrame(TestAudioSignals.silenceFrame(audioConfig)))
    }

    @Test
    fun `speech resumes into a fresh SPEECH_START after a completed speech end`() {
        val vad = newVad()
        // First utterance.
        repeat(vadConfig.speechStartFrameCount + 1) { vad.processFrame(TestAudioSignals.speechLikeFrame(audioConfig)) }
        repeat(vadConfig.speechEndFrameCount) { vad.processFrame(TestAudioSignals.silenceFrame(audioConfig)) }
        vad.processFrame(TestAudioSignals.silenceFrame(audioConfig)) // settle to SILENCE

        // Second utterance should be detected independently.
        vad.processFrame(TestAudioSignals.speechLikeFrame(audioConfig))
        val state = vad.processFrame(TestAudioSignals.speechLikeFrame(audioConfig))
        assertEquals(VadState.SPEECH_START, state)
    }
}
