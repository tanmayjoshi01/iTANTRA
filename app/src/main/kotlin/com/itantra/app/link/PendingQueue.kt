package com.itantra.app.link

import java.io.File
import java.io.IOException

/**
 * Outgoing messages that could not be sent because the receiver was not
 * connected. Kept in order and persisted to [file] (one wire line per message),
 * so they survive an app restart. Delivered on the next connection.
 *
 * This is store-and-forward on the SENDER: a receiver that is powered off gets
 * nothing until it is back on and reconnected. Not thread-safe; used from the
 * main thread.
 */
class PendingQueue(private val file: File?) {
    private val lines = ArrayList<String>()

    init {
        try {
            file?.takeIf { it.exists() }?.readLines(Charsets.UTF_8)?.filter { it.isNotBlank() }?.let(lines::addAll)
        } catch (_: IOException) {
        }
    }

    val messages: List<TextMessage> get() = lines.map(TextLines::decode)

    fun add(line: String) {
        lines += line
        persist()
    }

    /** Removes all pending lines, oldest first, and passes each to [send]. */
    fun drain(send: (String) -> Unit) {
        if (lines.isEmpty()) return
        val toSend = lines.toList()
        lines.clear()
        persist()
        toSend.forEach(send)
    }

    private fun persist() {
        try {
            file?.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"), Charsets.UTF_8)
        } catch (_: IOException) {
        }
    }
}
