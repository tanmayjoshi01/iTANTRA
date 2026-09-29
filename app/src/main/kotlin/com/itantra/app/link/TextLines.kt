package com.itantra.app.link

import java.io.BufferedReader

/** SOS is a dedicated emergency call: the receiver shows a full-screen SOS and sounds an alarm until acknowledged. */
enum class MessageType { NORMAL, ALERT, SOS }

/**
 * One logical message, carried identically over Wi-Fi TCP and Bluetooth RFCOMM.
 * [language] is the language the text is in (the sender's STT language); [id]
 * lets the receiver drop a message it has already received.
 */
data class TextMessage(
    val type: MessageType,
    val text: String,
    val language: Language = Language.HI,
    val id: String? = null,
)

/**
 * Wire format: one UTF-8 line per message:
 *   "[TYPE] text"                      (Hindi, no id: the original format)
 *   "[TYPE|lang|id=xxxxxxxx] text"     e.g. "[ALERT|as|id=1a2b3c4d] নমস্কাৰ"
 * TYPE is NORMAL, ALERT or SOS. Unknown metadata parts are ignored. A line that does
 * not match is a NORMAL Hindi message whose text is the whole line.
 */
object TextLines {
    private val HEADER = Regex("""^\[(ALERT|NORMAL|SOS)(\|[^\]]*)?\] (.*)$""")

    /** The line for [message], or null if its text is blank. */
    fun encode(message: TextMessage): String? {
        val text = toLine(message.text) ?: return null
        val meta = buildList {
            if (message.language != Language.HI || message.id != null) add(message.language.code)
            message.id?.let { add("id=$it") }
        }
        return "[${(listOf(message.type.name) + meta).joinToString("|")}] $text"
    }

    fun decode(line: String): TextMessage {
        val match = HEADER.matchEntire(line) ?: return TextMessage(MessageType.NORMAL, line.trim())
        var language = Language.HI
        var id: String? = null
        match.groupValues[2].split('|').filter { it.isNotEmpty() }.forEach { part ->
            if (part.startsWith("id=")) {
                id = part.removePrefix("id=").ifEmpty { null }
            } else {
                Language.fromCode(part)?.let { language = it }
            }
        }
        return TextMessage(MessageType.valueOf(match.groupValues[1]), match.groupValues[3].trim(), language, id)
    }

    /** Keeps the first occurrence of each message id; messages without an id are always kept. */
    fun dropDuplicates(messages: List<TextMessage>): List<TextMessage> {
        val seen = HashSet<String>()
        return messages.filter { m -> m.id == null || seen.add(m.id) }
    }

    /** The line to send for [text] (newlines flattened, trimmed), or null if nothing to send. */
    fun toLine(text: String): String? = text.replace(Regex("[\r\n]+"), " ").trim().ifEmpty { null }

    /** Reads lines until the peer closes the stream, passing each non-blank line to [onMessage]. */
    fun readMessages(reader: BufferedReader, onMessage: (String) -> Unit) {
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isNotBlank()) onMessage(line)
        }
    }
}
