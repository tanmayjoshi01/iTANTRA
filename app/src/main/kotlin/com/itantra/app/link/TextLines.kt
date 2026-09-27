package com.itantra.app.link

import java.io.BufferedReader

/** Wire format shared by the Bluetooth link: one UTF-8 line per message. */
object TextLines {
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
