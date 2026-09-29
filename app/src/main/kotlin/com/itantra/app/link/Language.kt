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
    }
}
