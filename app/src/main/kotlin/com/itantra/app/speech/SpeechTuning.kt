package com.itantra.app.speech

import com.itantra.speechengine.segmentation.SegmenterConfig
import com.itantra.speechengine.vad.VadConfig

/**
 * The app's VAD/segmentation settings. Only two values differ from
 * speech-engine's defaults, both from the 2026-09-26 SM-T225 silence
 * measurement (61 s, nobody speaking, VadDiag logging; see current-state.md):
 *
 * - Background frames above the 0.02 RMS threshold came in runs of at most
 *   5 consecutive 20 ms frames (mostly 1-2). With the default
 *   speechStartFrameCount of 2, any 2-frame transient confirmed speech; with
 *   the default pre-roll and 600 ms silence timeout it became a
 *   6 + 30 = 36-frame (720 ms) segment of near-silence, which the recognizer
 *   read as "वाद". 6 of 9 false entries in that run were exactly that.
 *   8 frames (160 ms) is above every observed silent run.
 * - Pre-roll grows from 5 to 10 frames so the 7 frames spent confirming speech
 *   plus some lead-in are still kept, and word onsets are not clipped.
 *
 * - A second SM-T225 silence run with the above had one false entry: an 8-frame
 *   transient that confirmed SPEECH_START and dropped straight back to SILENCE
 *   (the VAD never reached SPEECH). Every false segment observed has had that
 *   shape. minSpeechDurationMs = 40 (2 frames) requires at least one SPEECH
 *   frame after confirmation. Speech that reaches SPEECH always carries the
 *   VAD's 15-frame hangover, so it is unaffected.
 *
 * The energy threshold, ZCR range, hangover and silence timeout are unchanged:
 * raising the threshold would also drop quiet speech.
 */
object SpeechTuning {
    const val SPEECH_START_FRAME_COUNT = 8
    const val PRE_ROLL_FRAME_COUNT = 10
    const val MIN_SPEECH_DURATION_MS = 40L

    val vadConfig = VadConfig(speechStartFrameCount = SPEECH_START_FRAME_COUNT)
    val segmenterConfig = SegmenterConfig(
        preRollFrameCount = PRE_ROLL_FRAME_COUNT,
        minSpeechDurationMs = MIN_SPEECH_DURATION_MS,
    )
}
