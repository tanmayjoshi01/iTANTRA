package com.itantra.app.link

import java.util.Locale

/**
 * The three demo languages. Each maps to its own IndicConformer ONNX export
 * (only one is loaded at a time) and to the TTS locale used to speak it.
 * [sample] is a short greeting used as the default manual test message.
 */
enum class Language(val code: String, val displayName: String, val modelFile: String, val sample: String) {
    HI("hi", "Hindi", "indicconformer_hi.onnx", "नमस्ते"),
    AS("as", "Assamese", "indicconformer_as.onnx", "নমস্কাৰ"),
    OR("or", "Odia", "indicconformer_or.onnx", "ନମସ୍କାର"),
    ;

    val locale: Locale get() = Locale.forLanguageTag("$code-IN")

    /** The language's own name in its own script, for the UI. */
    val nativeName: String
        get() = when (this) {
            HI -> "हिन्दी"
            AS -> "অসমীয়া"
            OR -> "ଓଡ଼ିଆ"
        }

    /** Per-language token file if one is provided, else the shared one (verified for Hindi only). */
    val tokensFile: String get() = "tokens_$code.txt"

    companion object {
        fun fromCode(code: String): Language? = entries.firstOrNull { it.code == code }

        /**
         * The language [text] is written in, judged by its script: Devanagari is
         * Hindi, Bengali-Assamese script is Assamese, Odia script is Odia. Null
         * if it has none of these (e.g. Latin text). Used to tag typed text
         * truthfully instead of trusting the selected STT language.
         */
        fun ofScript(text: String): Language? {
            val counts = IntArray(entries.size)
            text.forEach { c ->
                when (c) {
                    in '\u0900'..'\u097F' -> counts[HI.ordinal]++
                    in '\u0980'..'\u09FF' -> counts[AS.ordinal]++
                    in '\u0B00'..'\u0B7F' -> counts[OR.ordinal]++
                }
            }
            val best = counts.indices.maxBy { counts[it] }
            return if (counts[best] == 0) null else entries[best]
        }
    }
}
