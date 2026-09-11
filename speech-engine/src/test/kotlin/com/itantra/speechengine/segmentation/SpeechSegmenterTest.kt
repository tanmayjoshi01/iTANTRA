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

    // preRollFrameCount = 0 in most tests below to keep frame-count arithmetic
    // simple and explicit; pre-roll behavior itself has a dedicated test.
    private fun newSegmenter(
        segmenterConfig: SegmenterConfig = SegmenterConfig(
            silenceDurationMs = 100,
            maxSegmentDurationMs = 400,
            minSegmentDurationMs = 0,
            preRollFrameCount = 0,
        ),
    ) = SpeechSegmenter(EnergyZcrVoiceActivityDetector(vadConfig), segmenterConfig)

    private fun speech(n: Int) = List(n) { TestAudioSignals.speechLikeFrame(audioConfig) }
    private fun silence(n: Int) = List(n) { TestAudioSignals.silenceFrame(audioConfig) }

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

        // silenceDurationMs=100ms => 5 frames of 20ms silence needed to finalize.
        val results = silence(5).map { segmenter.processFrame(it) }
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
        val silentFrames = silence(5)

        speechFrames.forEach { segmenter.processFrame(it) }
        val results = silentFrames.map { segmenter.processFrame(it) }
        val segment = results.last()
        assertNotNull(segment)

        // With preRollFrameCount=0, collection begins only once the VAD
        // confirms SPEECH_START, i.e. after (speechStartFrameCount - 1)
        // leading candidate frames have already been discarded.
        val collectedSpeechFrames = speechFrames.size - (vadConfig.speechStartFrameCount - 1)
        val expectedFrameCount = collectedSpeechFrames + silentFrames.size
        assertEquals(expectedFrameCount * audioConfig.samplesPerFrame, segment!!.samples.size)
    }

    @Test
    fun `multiple speech bursts produce independent segments`() {
        val segmenter = newSegmenter()

        speech(6).forEach { segmenter.processFrame(it) }
        val firstSegment = silence(5).map { segmenter.processFrame(it) }.last()
        assertNotNull("first utterance should finalize", firstSegment)

        // Extra quiet frames between utterances must not themselves start a segment.
        silence(3).forEach { assertNull(segmenter.processFrame(it)) }

        speech(6).forEach { segmenter.processFrame(it) }
        val secondSegment = silence(5).map { segmenter.processFrame(it) }.last()
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
        val results = silence(5).map { segmenter.processFrame(it) }
        assertNotNull("segmenter should detect a fresh utterance after reset", results.last())
    }

    @Test
    fun `empty frames interleaved in the stream do not crash or corrupt segmentation`() {
        val segmenter = newSegmenter()
        segmenter.processFrame(TestAudioSignals.emptyFrame(audioConfig))
        speech(6).forEach { segmenter.processFrame(it) }
        segmenter.processFrame(TestAudioSignals.emptyFrame(audioConfig))
        val results = silence(5).map { segmenter.processFrame(it) }
        assertNotNull(results.last())
    }

    @Test
    fun `segments shorter than the minimum duration are discarded`() {
        val segmenter = SpeechSegmenter(
            EnergyZcrVoiceActivityDetector(vadConfig),
            SegmenterConfig(silenceDurationMs = 100, maxSegmentDurationMs = 1000, minSegmentDurationMs = 300, preRollFrameCount = 0),
        )
        // A brief 2-frame speech candidate (VAD confirms on the 2nd) followed
        // by enough silence to trigger finalize: total collected duration
        // stays well under the 300ms minimum, so it must be discarded.
        speech(2).forEach { segmenter.processFrame(it) }
        val results = silence(5).map { segmenter.processFrame(it) }
        assertTrue("brief blip should be discarded, not emitted as a segment", results.all { it == null })
    }

    @Test
    fun `pre-roll frames captured before confirmed speech start are included in the segment`() {
        val preRollCount = 3
        val segmenter = SpeechSegmenter(
            EnergyZcrVoiceActivityDetector(vadConfig),
            SegmenterConfig(silenceDurationMs = 100, maxSegmentDurationMs = 400, minSegmentDurationMs = 0, preRollFrameCount = preRollCount),
        )

        // Enough leading silence to fully populate the pre-roll ring buffer.
        silence(preRollCount + 2).forEach { segmenter.processFrame(it) }

        val speechFrames = speech(6)
        speechFrames.forEach { segmenter.processFrame(it) }
        val silentFrames = silence(5)
        val segment = silentFrames.map { segmenter.processFrame(it) }.last()
        assertNotNull(segment)

        val collectedSpeechFrames = speechFrames.size - (vadConfig.speechStartFrameCount - 1)
        val expectedFrameCount = preRollCount + collectedSpeechFrames + silentFrames.size
        assertEquals(expectedFrameCount * audioConfig.samplesPerFrame, segment!!.samples.size)
    }
}
