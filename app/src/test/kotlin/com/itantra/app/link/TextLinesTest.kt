package com.itantra.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The Bluetooth link's wire format: one UTF-8 line per message. */
class TextLinesTest {

    private fun roundTrip(messages: List<String>): List<String> {
        val out = ByteArrayOutputStream()
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            messages.mapNotNull(TextLines::toLine).forEach { w.write(it); w.write("\n") }
        }
        val received = mutableListOf<String>()
        TextLines.readMessages(ByteArrayInputStream(out.toByteArray()).bufferedReader(Charsets.UTF_8)) { received += it }
        return received
    }

    @Test
    fun hindiMessages_roundTripIntact_andInOrder() {
        val messages = listOf("नमस्ते", "मेरा नाम पारस है", "आप कैसे हैं")
        assertEquals(messages, roundTrip(messages))
    }

    @Test
    fun embeddedNewlines_areFlattened_notSplit() {
        assertEquals(listOf("पहली पंक्ति दूसरी पंक्ति"), roundTrip(listOf("पहली पंक्ति\r\nदूसरी पंक्ति")))
    }

    @Test
    fun blankText_isNotSent() {
        assertNull(TextLines.toLine("  \n "))
        assertEquals(listOf("है"), roundTrip(listOf("", "है", "   ")))
    }

    @Test
    fun readMessages_returnsWhenPeerCloses() {
        val received = mutableListOf<String>()
        TextLines.readMessages("एक\n\nदो".byteInputStream(Charsets.UTF_8).bufferedReader(Charsets.UTF_8)) { received += it }
        assertEquals(listOf("एक", "दो"), received) // last line without trailing newline still delivered
    }
}
