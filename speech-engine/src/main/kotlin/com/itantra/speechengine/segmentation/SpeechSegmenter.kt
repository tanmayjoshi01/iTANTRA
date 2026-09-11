package com.itantra.speechengine.segmentation

import com.itantra.speechengine.audio.AudioFrame
import com.itantra.speechengine.vad.VadState
import com.itantra.speechengine.vad.VoiceActivityDetector

/**
 * Converts a continuous stream of [AudioFrame]s into discrete
 * [SpeechSegment]s by driving them through a [VoiceActivityDetector] and
 * applying a silence/max-duration policy on top of its output.
 *
 * Usage: call [processFrame] once per captured (or synthetic) audio frame,
 * in capture order. It returns a finalized [SpeechSegment] exactly on the
 * frame that completes one (silence timeout, or max-duration cutoff), and
 * `null` on every other frame. Not thread-safe; drive from a single
 * processing thread/coroutine, matching [VoiceActivityDetector]'s own
 * contract. This class assumes it exclusively owns the [VoiceActivityDetector]
 * instance passed to it (in particular, [reset] resets the VAD too).
 *
 * Audio is only buffered while a segment is actively being collected, plus
 * a small [SegmenterConfig.preRollFrameCount]-sized ring buffer while idle
 * — silence between utterances beyond that is not retained, so memory use
 * is bounded regardless of how long the input stream runs.
 */
class SpeechSegmenter(
    private val vad: VoiceActivityDetector,
    private val config: SegmenterConfig = SegmenterConfig(),
) {
    private val bufferedFrames = ArrayDeque<AudioFrame>()
    private val preRollBuffer = ArrayDeque<AudioFrame>()
    private var collecting = false
    private var silenceAccumMs = 0L
    private var collectedDurationMs = 0L

    /**
     * Processes one frame. Returns a finalized [SpeechSegment] if this frame
     * completed one, otherwise `null`. A segment shorter than
     * [SegmenterConfig.minSegmentDurationMs] is discarded silently (this
     * call still returns `null` for it) rather than emitted.
     */
    fun processFrame(frame: AudioFrame): SpeechSegment? {
        val vadState = vad.processFrame(frame)
        val isSpeechLike = vadState == VadState.SPEECH_START || vadState == VadState.SPEECH

        if (!collecting) {
            if (!isSpeechLike) {
                pushPreRoll(frame)
                return null
            }
            beginSegment()
        }

        bufferedFrames.addLast(frame)
        collectedDurationMs += frame.durationMs

        if (isSpeechLike) {
            silenceAccumMs = 0L
        } else {
            silenceAccumMs += frame.durationMs
        }

        val shouldFinalize = silenceAccumMs >= config.silenceDurationMs ||
            collectedDurationMs >= config.maxSegmentDurationMs

        return if (shouldFinalize) finalizeSegment() else null
    }

    /**
     * Discards any in-progress segment and any buffered pre-roll audio, and
     * resets both this segmenter's and the underlying VAD's internal state.
     * Use this to abandon the current utterance (cancellation) or to force
     * a clean starting state; safe to call at any time, collecting or not.
     */
    fun reset() {
        bufferedFrames.clear()
        preRollBuffer.clear()
        collecting = false
        silenceAccumMs = 0L
        collectedDurationMs = 0L
        vad.reset()
    }

    private fun pushPreRoll(frame: AudioFrame) {
        if (config.preRollFrameCount == 0) return
        if (preRollBuffer.size >= config.preRollFrameCount) {
            preRollBuffer.removeFirst()
        }
        preRollBuffer.addLast(frame)
    }

    private fun beginSegment() {
        collecting = true
        bufferedFrames.clear()
        bufferedFrames.addAll(preRollBuffer) // recover the candidate frames VAD needed to confirm speech start
        preRollBuffer.clear()
        collectedDurationMs = bufferedFrames.sumOf { it.durationMs }
        silenceAccumMs = 0L
    }

    private fun finalizeSegment(): SpeechSegment? {
        val framesToEmit = bufferedFrames.toList()
        val finalDurationMs = collectedDurationMs
        val startTimestampMs = framesToEmit.first().timestampMs
        val endTimestampMs = framesToEmit.last().timestampMs
        val audioConfig = framesToEmit.first().config

        collecting = false
        bufferedFrames.clear()
        silenceAccumMs = 0L
        collectedDurationMs = 0L

        if (finalDurationMs < config.minSegmentDurationMs) return null

        return SpeechSegment(
            samples = mergeSamples(framesToEmit),
            config = audioConfig,
            startTimestampMs = startTimestampMs,
            endTimestampMs = endTimestampMs,
        )
    }

    private fun mergeSamples(frames: List<AudioFrame>): ShortArray {
        val total = frames.sumOf { it.samples.size }
        val result = ShortArray(total)
        var offset = 0
        for (f in frames) {
            System.arraycopy(f.samples, 0, result, offset, f.samples.size)
            offset += f.samples.size
        }
        return result
    }
}
