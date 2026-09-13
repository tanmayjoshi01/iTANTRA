package com.itantra.speechengine.stt

/**
 * Greedy CTC decoding over the IndicConformer model's flat, 5633-entry
 * vocabulary (5632 SentencePiece subword pieces spanning AI4Bharat's 22
 * aggregate-tokenizer languages, plus one CTC blank at the final index).
 *
 * No per-language masking is applied or required: feeding this model's raw
 * ONNX output for genuinely-Hindi audio through a plain flat-vocabulary
 * greedy decode already lands correctly inside Hindi's token range with no
 * cross-language leakage - this was verified directly (Stage 3) by
 * comparing against a known-correct reference transcript before this class
 * was written, not assumed.
 */
class CtcGreedyDecoder(private val tokens: List<String>) {

    val blankId: Int = tokens.size - 1

    /**
     * logits: (frames x vocabSize) row-major, as produced by the ONNX
     * model's `logprobs` output for a single utterance.
     */
    fun decode(logits: FloatArray, frames: Int, vocabSize: Int): String {
        val collapsed = mutableListOf<Int>()
        var prev = -1
        for (f in 0 until frames) {
            var bestId = 0
            var bestVal = Float.NEGATIVE_INFINITY
            val base = f * vocabSize
            for (v in 0 until vocabSize) {
                val value = logits[base + v]
                if (value > bestVal) {
                    bestVal = value
                    bestId = v
                }
            }
            if (bestId != prev && bestId != blankId) collapsed.add(bestId)
            prev = bestId
        }
        return collapsed.joinToString("") { tokens[it] }.replace("▁", " ").trim()
    }

    companion object {
        /** Parses sherpa-onnx-style `tokens.txt` ("<token> <index>" per line, in index order). */
        fun loadTokens(text: String): List<String> =
            text.lineSequence()
                .filter { it.isNotBlank() }
                .map { line ->
                    val idx = line.lastIndexOf(' ')
                    line.substring(0, idx)
                }
                .toList()
    }
}
