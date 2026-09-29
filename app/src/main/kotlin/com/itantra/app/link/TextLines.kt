package com.itantra.app.link

import java.io.BufferedReader
import java.util.Locale

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
    /** Sender's location (SOS only), or null if none was available. Never invented. */
    val location: GeoFix? = null,
    /** When the sender created the message, epoch ms (SOS only). */
    val sentAtMs: Long? = null,
)

/** A real location fix: WGS84 degrees, when it was taken (epoch ms, if known) and its accuracy in metres (if known). */
data class GeoFix(val lat: Double, val lon: Double, val fixTimeMs: Long? = null, val accuracyM: Int? = null) {
    val isValid: Boolean get() = lat in -90.0..90.0 && lon in -180.0..180.0

    /** "19.076000,72.877700": always with '.' decimals, whatever the device locale. */
    val latLon: String get() = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
}

/**
 * Wire format: one UTF-8 line per message:
 *   "[TYPE] text"                      (Hindi, no id: the original format)
 *   "[TYPE|lang|id=xxxxxxxx] text"     e.g. "[ALERT|as|id=1a2b3c4d] নমস্কাৰ"
 * TYPE is NORMAL, ALERT or SOS. An SOS may add its location and time:
 *   "[SOS|hi|id=xxxxxxxx|loc=19.076000,72.877700|acc=15|fix=<ms>|t=<ms>] text"
 * Unknown metadata parts are ignored (so older receivers still read these lines).
 * A line that does not match is a NORMAL Hindi message whose text is the whole line.
 */
object TextLines {
    private val HEADER = Regex("""^\[(ALERT|NORMAL|SOS)(\|[^\]]*)?\] (.*)$""")

    /** The line for [message], or null if its text is blank. */
    fun encode(message: TextMessage): String? {
        val text = toLine(message.text) ?: return null
        val meta = buildList {
            val extra = message.location?.isValid == true || message.sentAtMs != null
            if (message.language != Language.HI || message.id != null || extra) add(message.language.code)
            message.id?.let { add("id=$it") }
            message.location?.takeIf { it.isValid }?.let { loc ->
                add("loc=${loc.latLon}")
                loc.accuracyM?.let { add("acc=$it") }
                loc.fixTimeMs?.let { add("fix=$it") }
            }
            message.sentAtMs?.let { add("t=$it") }
        }
        return "[${(listOf(message.type.name) + meta).joinToString("|")}] $text"
    }

    fun decode(line: String): TextMessage {
        val match = HEADER.matchEntire(line) ?: return TextMessage(MessageType.NORMAL, line.trim())
        var language = Language.HI
        var id: String? = null
        var latLon: Pair<Double, Double>? = null
        var accuracy: Int? = null
        var fixTime: Long? = null
        var sentAt: Long? = null
        match.groupValues[2].split('|').filter { it.isNotEmpty() }.forEach { part ->
            when {
                part.startsWith("id=") -> id = part.removePrefix("id=").ifEmpty { null }
                part.startsWith("loc=") -> latLon = part.removePrefix("loc=").split(',').let { xy ->
                    val lat = xy.getOrNull(0)?.toDoubleOrNull()
                    val lon = xy.getOrNull(1)?.toDoubleOrNull()
                    if (xy.size == 2 && lat != null && lon != null) lat to lon else null
                }
                part.startsWith("acc=") -> accuracy = part.removePrefix("acc=").toIntOrNull()
                part.startsWith("fix=") -> fixTime = part.removePrefix("fix=").toLongOrNull()
                part.startsWith("t=") -> sentAt = part.removePrefix("t=").toLongOrNull()
                else -> Language.fromCode(part)?.let { language = it }
            }
        }
        // A malformed or out-of-range location is dropped, never guessed.
        val location = latLon?.let { (lat, lon) -> GeoFix(lat, lon, fixTime, accuracy) }?.takeIf { it.isValid }
        return TextMessage(MessageType.valueOf(match.groupValues[1]), match.groupValues[3].trim(), language, id, location, sentAt)
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
