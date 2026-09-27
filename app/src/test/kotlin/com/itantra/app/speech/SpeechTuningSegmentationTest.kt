package com.itantra.app.speech

import com.itantra.speechengine.audio.AudioConfig
import com.itantra.speechengine.audio.AudioFrame
import com.itantra.speechengine.segmentation.SegmenterConfig
import com.itantra.speechengine.segmentation.SpeechSegment
import com.itantra.speechengine.segmentation.SpeechSegmenter
import com.itantra.speechengine.vad.EnergyZcrVoiceActivityDetector
import com.itantra.speechengine.vad.VadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The real speech-engine VAD and segmenter with [SpeechTuning]'s settings, fed
 * deterministic 200 Hz tones (ZCR about 0.025, inside the VAD's ZCR range) at
 * RMS levels taken from the 2026-09-26 SM-T225 silence measurement:
 * background about 0.0046, transients 0.02-0.058 in runs of at most 5 frames.
 */
class SpeechTuningSegmentationTest {

    private val config = AudioConfig() // 16 kHz mono, 20 ms (320-sample) frames
    private var t = 0L

    private fun tone(rms: Double): AudioFrame {
        val amplitude = rms * sqrt(2.0) * Short.MAX_VALUE
        val samples = ShortArray(config.samplesPerFrame) { i ->
            (amplitude * sin(2 * PI * 200.0 * i / config.sampleRateHz)).toInt().toShort()
        }
        return AudioFrame(samples, config, t).also { t += 20 }
    }

    private fun frames(count: Int, rms: Double) = List(count) { tone(rms) }

    private val floor = 0.0046 // measured median background RMS
    private val transient = 0.03 // measured transients were 0.02-0.058
    private val normalSpeech = 0.1
    private val quietSpeech = 0.025 // just above the unchanged 0.02 threshold

    private fun tuned() = SpeechSegmenter(EnergyZcrVoiceActivityDetector(SpeechTuning.vadConfig), SpeechTuning.segmenterConfig)

    private fun SpeechSegmenter.feed(frames: List<AudioFrame>): List<SpeechSegment> = frames.mapNotNull { processFrame(it) }

    private fun SpeechSegment.frameCount() = samples.size / config.samplesPerFrame

    @Test
    fun defaultConfig_twoFrameTransient_producesThe720msFalseSegment() {
        // Regression record of the root cause, with speech-engine's defaults.
        val segmenter = SpeechSegmenter(EnergyZcrVoiceActivityDetector(VadConfig()), SegmenterConfig())
        val segments = segmenter.feed(frames(50, floor) + frames(2, transient) + frames(100, floor))

        // 5 pre-roll frames + the confirming frame + 30 silence frames (600 ms).
        assertEquals(36, segments.single().frameCount())
        assertEquals(720L, segments.single().samples.size * 1000L / config.sampleRateHz)
    }

    @Test
    fun silence_atMeasuredBackgroundLevel_producesNoSegment() {
        assertTrue(tuned().feed(frames(500, floor)).isEmpty()) // 10 s
    }

    @Test
    fun transients_upToLongestObservedRun_produceNoSegment() {
        // Runs of 1..5 above-threshold frames, as observed in silence, repeated over ~22 s.
        val input = (1..5).flatMap { run -> List(4) { frames(25, floor) + frames(run, transient) } }.flatten() +
            frames(100, floor)
        assertTrue(tuned().feed(input).isEmpty())
    }

    @Test
    fun burstLength_boundary() {
        val seven = tuned().feed(frames(50, floor) + frames(7, normalSpeech) + frames(100, floor))
        val eight = tuned().feed(frames(50, floor) + frames(8, normalSpeech) + frames(100, floor))
        val nine = tuned().feed(frames(50, floor) + frames(9, normalSpeech) + frames(100, floor))
        assertTrue("7 frames never confirm speech", seven.isEmpty())
        assertTrue("8 frames only reach SPEECH_START (the observed false-trigger shape): discarded", eight.isEmpty())
        assertEquals("9 frames reach SPEECH: kept", 1, nine.size)
    }

    @Test
    fun observedEightFrameTransient_inSilence_producesNoSegment() {
        // Second SM-T225 silence run: an 8-frame transient at energy about 0.025 started the only false segment.
        assertTrue(tuned().feed(frames(300, floor) + frames(8, 0.0253) + frames(300, floor)).isEmpty())
    }

    @Test
    fun silenceSpeechSilence_producesExactlyOneSegment_withOnsetKept() {
        val segments = tuned().feed(frames(50, floor) + frames(50, normalSpeech) + frames(100, floor))

        val segment = segments.single()
        // Speech starts at t = 50 frames x 20 ms = 1000 ms. The pre-roll keeps the
        // 7 frames the VAD spent confirming speech, so the onset is not clipped.
        assertTrue("segment starts at or before the first speech frame", segment.startTimestampMs <= 1000)
        val speechFramesInSegment = (0 until segment.frameCount()).count { f ->
            val frame = segment.samples.copyOfRange(f * config.samplesPerFrame, (f + 1) * config.samplesPerFrame)
            frame.maxOf { it.toInt() } > normalSpeech * Short.MAX_VALUE // only speech frames are that loud
        }
        assertEquals(50, speechFramesInSegment)
    }

    @Test
    fun quietSpeech_justAboveThreshold_isDetected() {
        val segments = tuned().feed(frames(50, floor) + frames(30, quietSpeech) + frames(100, floor))
        assertEquals(1, segments.size)
    }

    @Test
    fun speechFollowedBySilence_endsAfterHangoverPlusSilenceTimeout() {
        val segmenter = tuned()
        segmenter.feed(frames(50, floor) + frames(50, normalSpeech))
        // VAD hangover: 14 more frames still report SPEECH, then 600 ms (30 frames) of silence.
        val beforeEnd = segmenter.feed(frames(43, floor))
        val atEnd = segmenter.processFrame(tone(floor))
        assertTrue(beforeEnd.isEmpty())
        assertTrue(atEnd != null)
    }

    @Test
    fun twoUtterances_produceTwoSegments() {
        val segments = tuned().feed(
            frames(50, floor) + frames(40, normalSpeech) + frames(100, floor) + frames(40, normalSpeech) + frames(100, floor),
        )
        assertEquals(2, segments.size)
    }

    @Test
    fun flushDuringSpeech_emitsTheUtteranceExactlyOnce() {
        val segmenter = tuned()
        assertTrue(segmenter.feed(frames(50, floor) + frames(30, normalSpeech)).isEmpty())
        val flushed = segmenter.flush()
        assertTrue(flushed != null)
        assertNull(segmenter.flush())
    }
}
