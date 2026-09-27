package com.itantra.app.speech

import com.itantra.speechengine.audio.AudioFrame
import com.itantra.speechengine.vad.EnergyZcrVoiceActivityDetector
import com.itantra.speechengine.vad.VadState
import com.itantra.speechengine.vad.VoiceActivityDetector

/**
 * Diagnostic pass-through around [EnergyZcrVoiceActivityDetector]: classification
 * is unchanged (every call is delegated); it only reports what the VAD saw.
 *
 * Per window of [framesPerWindow] frames (1 s at 20 ms frames) it logs one line
 * with RMS energy and ZCR percentiles, the number of speech-candidate frames and
 * the longest run of consecutive candidates. Each SPEECH_START / SPEECH_END
 * transition is logged as it happens. Runs on the capture thread, like the VAD.
 */
class VadDiagnostics(
    private val vad: EnergyZcrVoiceActivityDetector,
    private val log: (String) -> Unit,
    private val framesPerWindow: Int = 50,
) : VoiceActivityDetector {
    private val energies = DoubleArray(framesPerWindow)
    private val zcrs = DoubleArray(framesPerWindow)
    private var count = 0
    private var candidates = 0
    private var run = 0
    private var maxRun = 0
    private var frameIndex = 0L

    override fun processFrame(frame: AudioFrame): VadState {
        val state = vad.processFrame(frame)
        energies[count] = vad.lastFrameEnergy
        zcrs[count] = vad.lastFrameZcr
        if (vad.lastFrameIsCandidate) {
            candidates++
            run++
            if (run > maxRun) maxRun = run
        } else {
            run = 0
        }
        if (state == VadState.SPEECH_START || state == VadState.SPEECH_END) {
            log("frame=$frameIndex $state energy=${fmt(vad.lastFrameEnergy)} zcr=${fmt(vad.lastFrameZcr)}")
        }
        frameIndex++
        count++
        if (count == framesPerWindow) flushWindow()
        return state
    }

    override fun reset() {
        vad.reset()
        count = 0
        candidates = 0
        run = 0
        maxRun = 0
    }

    private fun flushWindow() {
        val e = energies.sortedArray()
        val z = zcrs.sortedArray()
        log(
            "window frames=${frameIndex - count}..${frameIndex - 1} " +
                "energy p10=${fmt(e.pct(10))} p50=${fmt(e.pct(50))} p90=${fmt(e.pct(90))} max=${fmt(e.last())} " +
                "zcr p10=${fmt(z.pct(10))} p50=${fmt(z.pct(50))} p90=${fmt(z.pct(90))} " +
                "candidates=$candidates maxCandidateRun=$maxRun",
        )
        count = 0
        candidates = 0
        maxRun = run
    }

    private fun DoubleArray.pct(p: Int) = this[((size - 1) * p) / 100]

    private fun fmt(v: Double) = "%.4f".format(v)
}
