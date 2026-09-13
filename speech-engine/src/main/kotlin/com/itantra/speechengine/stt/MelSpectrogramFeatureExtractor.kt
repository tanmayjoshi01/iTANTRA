package com.itantra.speechengine.stt

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Converts 16 kHz mono PCM audio into the log-mel feature representation
 * expected by the validated Hindi IndicConformer ONNX model
 * (`indicconformer_stt_hi_hybrid_rnnt_large`), reproducing
 * `nemo.collections.asr.parts.preprocessing.features.FilterbankFeatures` /
 * `AudioToMelSpectrogramPreprocessor` as it was actually instantiated for
 * this checkpoint (verified against the loaded model's live attributes and
 * against PyTorch's own `torch.stft` documentation, not assumed from class
 * defaults or convention):
 *
 * - Pre-emphasis: `y[0]=x[0]`, `y[n]=x[n]-0.97*x[n-1]`.
 * - STFT: n_fft=512, win_length=400 (25ms; the model's YAML specifies
 *   `window_size: 0.025` seconds, which the wrapper converts to samples -
 *   the raw `FilterbankFeatures` constructor default of 320 samples does
 *   NOT apply here and was confirmed wrong against the live model before
 *   this was written), hop_length=160 (10ms), Hann window
 *   (`torch.hann_window(400, periodic=False)`, symmetric).
 * - Per `torch.stft`'s own documented behavior: the input is reflect-padded
 *   by `n_fft/2=256` samples on each side (`center=True`), and the
 *   400-sample window is zero-padded symmetrically to 512 samples (56
 *   zeros, 400 window values, 56 zeros) before being applied to each
 *   512-sample analysis frame - not left- or right-aligned.
 * - Power spectrum: `re^2+im^2` (equivalent to NeMo's `sqrt(re^2+im^2)`
 *   then `mag_power=2.0`).
 * - Mel filterbank: the exact 80x257 Slaney-normalized matrix
 *   (`librosa.filters.mel(sr=16000, n_fft=512, n_mels=80, fmin=0,
 *   fmax=8000, norm="slaney")`) as actually held by the validated model,
 *   exported once as a precomputed constant rather than reimplemented here
 *   - mel-scale/triangular-filter/Slaney-normalization construction is a
 *   real bug-risk surface this avoids entirely by using the model's own
 *   values bit-for-bit.
 * - Log: `ln(x + 2^-24)` (`log_zero_guard_type="add"`).
 * - Per-feature normalization: per mel-bin mean/std across the whole
 *   utterance, using PyTorch's `.std()` default (N-1, Bessel-corrected,
 *   not population std), `x_std += 1e-5` before dividing.
 * - Dither (1e-5 in the model config) is NOT applied: NeMo only dithers in
 *   training mode, and the loaded checkpoint's `preprocessor.training` was
 *   confirmed `False`.
 *
 * Output layout matches the ONNX graph's `audio_signal` input exactly:
 * (melBins=80, frames), row-major, frames = `1 + numSamples/160` (integer
 * division) - the same formula NeMo's own `FilterbankFeatures.get_seq_len`
 * uses.
 */
class MelSpectrogramFeatureExtractor {

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val N_FFT = 512
        const val WIN_LENGTH = 400
        const val HOP_LENGTH = 160
        const val MEL_BINS = 80
        const val FREQ_BINS = N_FFT / 2 + 1 // 257
        private const val PREEMPH = 0.97f
        private const val LOG_ZERO_GUARD = 5.9604645e-8f // 2^-24
        private const val NORMALIZE_EPS = 1e-5f
        private const val REFLECT_PAD = N_FFT / 2 // 256

        private val window: FloatArray by lazy { loadFloatResource("hann_window_400.f32", WIN_LENGTH) }
        private val melFilterbank: FloatArray by lazy {
            loadFloatResource("mel_filterbank_80x257.f32", MEL_BINS * FREQ_BINS)
        }

        private fun loadFloatResource(name: String, expectedCount: Int): FloatArray {
            val stream = MelSpectrogramFeatureExtractor::class.java.getResourceAsStream(name)
                ?: throw IllegalStateException("Missing bundled resource: $name")
            val bytes = stream.use { it.readBytes() }
            require(bytes.size == expectedCount * 4) {
                "$name: expected ${expectedCount * 4} bytes, got ${bytes.size}"
            }
            val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return FloatArray(expectedCount) { buf.float }
        }

        // Symmetric zero-padding of the 400-sample window into a 512-sample
        // frame: 56 zeros, then the window, then 56 zeros - per torch.stft's
        // documented win_length<n_fft behavior, not left/right-aligned.
        private val paddedWindow: FloatArray by lazy {
            val left = (N_FFT - WIN_LENGTH) / 2
            FloatArray(N_FFT).also { padded ->
                for (i in 0 until WIN_LENGTH) padded[left + i] = window[i]
            }
        }
    }

    /** samples: raw PCM16 in [-32768,32767]. Returns (melBins x frames), row-major. */
    fun extract(samples: ShortArray): FloatArray {
        val n = samples.size
        require(n > REFLECT_PAD) { "Input too short (${n} samples) for reflect padding of $REFLECT_PAD" }

        val x = FloatArray(n) { samples[it] / 32768.0f }

        // Pre-emphasis.
        val y = FloatArray(n)
        y[0] = x[0]
        for (i in 1 until n) y[i] = x[i] - PREEMPH * x[i - 1]

        // Reflect-pad by n_fft/2 on each side, matching torch.stft(center=True,
        // pad_mode="reflect"): reflected samples exclude the edge sample itself.
        val padded = FloatArray(n + 2 * REFLECT_PAD)
        for (i in 0 until REFLECT_PAD) padded[i] = y[REFLECT_PAD - i]
        System.arraycopy(y, 0, padded, REFLECT_PAD, n)
        for (i in 0 until REFLECT_PAD) padded[REFLECT_PAD + n + i] = y[n - 2 - i]

        val numFrames = 1 + n / HOP_LENGTH

        val power = Array(FREQ_BINS) { FloatArray(numFrames) }
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        for (m in 0 until numFrames) {
            val start = m * HOP_LENGTH
            for (k in 0 until N_FFT) {
                re[k] = padded[start + k] * paddedWindow[k]
                im[k] = 0f
            }
            Fft.forwardInPlace(re, im)
            for (k in 0 until FREQ_BINS) {
                power[k][m] = re[k] * re[k] + im[k] * im[k]
            }
        }

        // Mel filterbank: (MEL_BINS x FREQ_BINS) @ (FREQ_BINS x numFrames).
        val mel = Array(MEL_BINS) { FloatArray(numFrames) }
        for (mb in 0 until MEL_BINS) {
            val rowBase = mb * FREQ_BINS
            for (m in 0 until numFrames) {
                var sum = 0f
                for (f in 0 until FREQ_BINS) sum += melFilterbank[rowBase + f] * power[f][m]
                mel[mb][m] = ln(sum + LOG_ZERO_GUARD)
            }
        }

        // Per-feature normalization: per mel-bin mean/std over the whole
        // utterance, PyTorch .std() default (N-1, Bessel-corrected).
        val out = FloatArray(MEL_BINS * numFrames)
        for (mb in 0 until MEL_BINS) {
            var sum = 0.0
            for (m in 0 until numFrames) sum += mel[mb][m]
            val mean = (sum / numFrames).toFloat()

            var sqSum = 0.0
            for (m in 0 until numFrames) {
                val d = mel[mb][m] - mean
                sqSum += d.toDouble() * d.toDouble()
            }
            val variance = if (numFrames > 1) sqSum / (numFrames - 1) else 0.0
            val std = sqrt(variance).toFloat() + NORMALIZE_EPS

            val base = mb * numFrames
            for (m in 0 until numFrames) {
                out[base + m] = (mel[mb][m] - mean) / std
            }
        }
        return out
    }

    fun frameCount(numSamples: Int): Int = 1 + numSamples / HOP_LENGTH
}

/** Minimal iterative radix-2 decimation-in-time Cooley-Tukey FFT, in place, power-of-2 sizes only. */
internal object Fft {
    fun forwardInPlace(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be a power of 2, got $n" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        var len = 2
        while (len <= n) {
            val half = len / 2
            val theta = -2.0 * Math.PI / len
            val wRe = Math.cos(theta)
            val wIm = Math.sin(theta)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until half) {
                    val evenIdx = start + k
                    val oddIdx = start + k + half
                    val oddReVal = re[oddIdx] * curRe - im[oddIdx] * curIm
                    val oddImVal = re[oddIdx] * curIm + im[oddIdx] * curRe
                    re[oddIdx] = (re[evenIdx] - oddReVal).toFloat()
                    im[oddIdx] = (im[evenIdx] - oddImVal).toFloat()
                    re[evenIdx] = (re[evenIdx] + oddReVal).toFloat()
                    im[evenIdx] = (im[evenIdx] + oddImVal).toFloat()
                    val nextRe = curRe * wRe - curIm * wIm
                    val nextIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                    curIm = nextIm
                }
                start += len
            }
            len = len shl 1
        }
    }
}
