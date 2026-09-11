package com.itantra.speechengine.segmentation

import com.itantra.speechengine.TestAudioSignals
import com.itantra.speechengine.audio.AudioConfig
import com.itantra.speechengine.vad.EnergyZcrVoiceActivityDetector
import com.itantra.speechengine.vad.VadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {

    private val audioConfig = AudioConfig() // 16 kHz mono, 20 ms frames => 320 samples/frame
    private val vadConfig = VadConfig(speechStartFrameCount = 2, speechEndFrameCount = 2)
    private val defaultSegmenterConfig = SegmenterConfig(
        silenceDurationMs = 100,
        maxSegmentDurationMs = 400,
        minSegmentDurationMs = 0,
        preRollFrameCount = 0,
    )

    // preRollFrameCount = 0 by default to keep frame-count arithmetic simple
    // and explicit; pre-roll behavior itself has a dedicated test.
    private fun newSegmenter(segmenterConfig: SegmenterConfig = defaultSegmenterConfig) =
        SpeechSegmenter(EnergyZcrVoiceActivityDetector(vadConfig), segmenterConfig)

    private fun speech(n: Int) = List(n) { TestAudioSignals.speechLikeFrame(audioConfig) }
    private fun silence(n: Int) = List(n) { TestAudioSignals.silenceFrame(audioConfig) }

    /**
     * Number of trailing silence frames actually required to finalize a
     * segment under [segmenterConfig]: the VAD's own end-of-speech hangover
     * (speechEndFrameCount - 1 frames, during which it still reports
     * SPEECH and the segmenter's own silence timer does not move) plus the
     * segmenter's configured [SegmenterConfig.silenceDurationMs]. See the
     * KDoc on [SegmenterConfig.silenceDurationMs] for why these are
     * additive rather than [SegmenterConfig.silenceDurationMs] alone.
     */
    private fun silenceFramesToFinalize(segmenterConfig: SegmenterConfig = defaultSegmenterConfig): Int {
        val hangoverFrames = vadConfig.speechEndFrameCount - 1
        val ownSilenceFrames = (segmenterConfig.silenceDurationMs / audioConfig.frameDurationMs).toInt()
        return hangoverFrames + ownSilenceFrames
    }

    @Test
    fun `pure silence never produces a segment`() {
        val segmenter = newSegmenter()
        val results = silence(20).map { segmenter.processFrame(it) }
        assertTrue(results.all { it == null })
    }

    @Test
    fun `speech start begins collection without finalizing mid-utterance`() {
        val segmenter = newSegmenter()
        val results = speech(6).map { segmenter.processFrame(it) }
        assertTrue("segment must not finalize while speech is ongoing", results.all { it == null })
    }

    @Test
    fun `segment finalizes after configured silence duration`() {
        val segmenter = newSegmenter()
        speech(6).forEach { segmenter.processFrame(it) }

        val results = silence(silenceFramesToFinalize()).map { segmenter.processFrame(it) }
        assertTrue(
            "no segment should finalize before the configured silence duration elapses",
            results.dropLast(1).all { it == null },
        )
        assertNotNull(
            "segment should finalize exactly on the frame that completes the configured silence duration",
            results.last(),
        )
    }

    @Test
    fun `finalized segment sample count matches the frames actually collected`() {
        val segmenter = newSegmenter()
        val speechFrames = speech(6)
        val silentFrames = silence(silenceFramesToFinalize())

        speechFrames.forEach { segmenter.processFrame(it) }
        val results = silentFrames.map { segmenter.processFrame(it) }
        val segment = results.last()
        assertNotNull(segment)

        // With preRollFrameCount=0, collection begins only once the VAD
        // confirms SPEECH_START, i.e. after (speechStartFrameCount - 1)
        // leading candidate frames have already been discarded. Every
        // frame sent while collecting is buffered, including all trailing
        // silence frames (they are what the silence timer measures).
        val collectedSpeechFrames = speechFrames.size - (vadConfig.speechStartFrameCount - 1)
        val expectedFrameCount = collectedSpeechFrames + silentFrames.size
        assertEquals(expectedFrameCount * audioConfig.samplesPerFrame, segment!!.samples.size)
    }

    @Test
    fun `multiple speech bursts produce independent segments`() {
        val segmenter = newSegmenter()
        val silentFrames = silenceFramesToFinalize()

        speech(6).forEach { segmenter.processFrame(it) }
        val firstSegment = silence(silentFrames).map { segmenter.processFrame(it) }.last()
        assertNotNull("first utterance should finalize", firstSegment)

        // Extra quiet frames between utterances must not themselves start a segment.
        silence(3).forEach { assertNull(segmenter.processFrame(it)) }

        speech(6).forEach { segmenter.processFrame(it) }
        val secondSegment = silence(silentFrames).map { segmenter.processFrame(it) }.last()
        assertNotNull("second utterance should finalize independently", secondSegment)
    }

    @Test
    fun `continuous speech beyond max duration is force-finalized`() {
        val segmenter = newSegmenter() // maxSegmentDurationMs=400ms
        // 21 frames sent => 20 buffered (first is the pre-confirmation
        // candidate frame, discarded since preRollFrameCount=0) => exactly
        // 400ms collected on the 21st frame, reaching the cap.
        val results = speech(21).map { segmenter.processFrame(it) }
        assertTrue("no segment should finalize before the max-duration cap", results.dropLast(1).all { it == null })
        assertNotNull("segment should be force-finalized once the max-duration cap is reached", results.last())
    }

    @Test
    fun `reset discards an in-progress segment without emitting it`() {
        val segmenter = newSegmenter()
        speech(6).forEach { segmenter.processFrame(it) }
        segmenter.reset()

        // After reset, trailing silence must not finalize anything (nothing was collecting).
        val results = silence(10).map { segmenter.processFrame(it) }
        assertTrue(results.all { it == null })
    }

    @Test
    fun `segmenter remains usable for a new utterance after reset`() {
        val segmenter = newSegmenter()
        speech(6).forEach { segmenter.processFrame(it) }
        segmenter.reset()

        speech(6).forEach { segmenter.processFrame(it) }
        val results = silence(silenceFramesToFinalize()).map { segmenter.processFrame(it) }
        assertNotNull("segmenter should detect a fresh utterance after reset", results.last())
    }

    @Test
    fun `empty frames interleaved in the stream do not crash or corrupt segmentation`() {
        val segmenter = newSegmenter()
        segmenter.processFrame(TestAudioSignals.emptyFrame(audioConfig))
        speech(6).forEach { segmenter.processFrame(it) }
        segmenter.processFrame(TestAudioSignals.emptyFrame(audioConfig))
        val results = silence(silenceFramesToFinalize()).map { segmenter.processFrame(it) }
        assertNotNull(results.last())
    }

    @Test
    fun `segments shorter than the minimum duration are discarded`() {
        val config = SegmenterConfig(silenceDurationMs = 100, maxSegmentDurationMs = 1000, minSegmentDurationMs = 300, preRollFrameCount = 0)
        val segmenter = SpeechSegmenter(EnergyZcrVoiceActivityDetector(vadConfig), config)

        // A brief 2-frame speech candidate (VAD confirms on the 2nd, so
        // only 1 frame is actually buffered) followed by just enough
        // silence to reach the finalize point: total collected duration
        // stays well under the 300ms minimum, so it must be discarded
        // (not merely never reach the finalize point at all).
        speech(2).forEach { segmenter.processFrame(it) }
        val results = silence(silenceFramesToFinalize(config)).map { segmenter.processFrame(it) }
        assertTrue("brief blip should be discarded, not emitted as a segment", results.all { it == null })
    }

    @Test
    fun `pre-roll frames captured before confirmed speech start are included in the segment`() {
        val preRollCount = 3
        val config = SegmenterConfig(silenceDurationMs = 100, maxSegmentDurationMs = 400, minSegmentDurationMs = 0, preRollFrameCount = preRollCount)
        val segmenter = SpeechSegmenter(EnergyZcrVoiceActivityDetector(vadConfig), config)

        // Enough leading silence to fully populate the pre-roll ring buffer.
        silence(preRollCount + 2).forEach { segmenter.processFrame(it) }

        val speechFrames = speech(6)
        speechFrames.forEach { segmenter.processFrame(it) }
        val silentFrames = silence(silenceFramesToFinalize(config))
        val segment = silentFrames.map { segmenter.processFrame(it) }.last()
        assertNotNull(segment)

        val collectedSpeechFrames = speechFrames.size - (vadConfig.speechStartFrameCount - 1)
        val expectedFrameCount = preRollCount + collectedSpeechFrames + silentFrames.size
        assertEquals(expectedFrameCount * audioConfig.samplesPerFrame, segment!!.samples.size)
    }
}
