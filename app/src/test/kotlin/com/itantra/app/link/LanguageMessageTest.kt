package com.itantra.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Language/id metadata in the wire format, duplicate handling, and the pending queue. */
class LanguageMessageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun roundTrip(m: TextMessage) = TextLines.decode(TextLines.encode(m)!!)

    @Test
    fun hindiAssameseOdia_roundTrip_withUnicodeIntact() {
        val messages = listOf(
            TextMessage(MessageType.NORMAL, "मेरा नाम पारस है", Language.HI, "a1"),
            TextMessage(MessageType.NORMAL, "মোৰ নাম পাৰছ", Language.AS, "b2"),
            TextMessage(MessageType.ALERT, "ମୋତେ ସାହାଯ୍ୟ ଦରକାର", Language.OR, "c3"),
        )
        messages.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun encodedFormat_carriesTypeLanguageAndId() {
        assertEquals("[ALERT|as|id=1a2b3c4d] নমস্কাৰ", TextLines.encode(TextMessage(MessageType.ALERT, "নমস্কাৰ", Language.AS, "1a2b3c4d")))
        assertEquals("[NORMAL|or] ନମସ୍କାର", TextLines.encode(TextMessage(MessageType.NORMAL, "ନମସ୍କାର", Language.OR)))
    }

    @Test
    fun hindiWithoutId_keepsOriginalFormat() {
        assertEquals("[ALERT] मदद चाहिए", TextLines.encode(TextMessage(MessageType.ALERT, "मदद चाहिए")))
    }

    @Test
    fun oldAndPlainLines_decodeAsBefore() {
        assertEquals(TextMessage(MessageType.ALERT, "मदद चाहिए"), TextLines.decode("[ALERT] मदद चाहिए"))
        assertEquals(TextMessage(MessageType.NORMAL, "नमस्ते"), TextLines.decode("[NORMAL] नमस्ते"))
        assertEquals(TextMessage(MessageType.NORMAL, "नमस्ते"), TextLines.decode("नमस्ते"))
    }

    @Test
    fun malformedMetadata_failsSafe() {
        // Unknown language code and junk parts are ignored: type is kept, language falls back to Hindi.
        assertEquals(TextMessage(MessageType.ALERT, "x", Language.HI, "9"), TextLines.decode("[ALERT|zz|junk|id=9] x"))
        // Unknown type: the whole line is treated as plain NORMAL text.
        assertEquals(TextMessage(MessageType.NORMAL, "[URGENT|as] x"), TextLines.decode("[URGENT|as] x"))
        // Missing space after header: plain text.
        assertEquals(TextMessage(MessageType.NORMAL, "[ALERT|as]x"), TextLines.decode("[ALERT|as]x"))
    }

    @Test
    fun duplicates_areDroppedById_inOrder() {
        val a = TextMessage(MessageType.NORMAL, "एक", Language.HI, "1")
        val b = TextMessage(MessageType.ALERT, "দুই", Language.AS, "2")
        val noId = TextMessage(MessageType.NORMAL, "तीन")
        assertEquals(listOf(a, b, noId, noId), TextLines.dropDuplicates(listOf(a, b, a, noId, b, noId)))
    }

    @Test
    fun pendingQueue_drainsInOrder_andEmpties() {
        val q = PendingQueue(tmp.newFile("pending.txt"))
        listOf("[NORMAL|hi|id=1] एक", "[ALERT|as|id=2] দুই", "[NORMAL|or|id=3] ତିନି").forEach(q::add)
        val sent = mutableListOf<String>()
        q.drain { sent += it }
        assertEquals(listOf("[NORMAL|hi|id=1] एक", "[ALERT|as|id=2] দুই", "[NORMAL|or|id=3] ତିନି"), sent)
        assertTrue(q.messages.isEmpty())
    }

    @Test
    fun pendingQueue_survivesRestart_viaFile() {
        val file = tmp.newFile("pending.txt")
        PendingQueue(file).apply {
            add("[ALERT|or|id=7] ମୋତେ ସାହାଯ୍ୟ ଦରକାର")
            add("[NORMAL|hi|id=8] नमस्ते")
        }
        val reloaded = PendingQueue(file) // simulated app restart
        assertEquals(
            listOf(
                TextMessage(MessageType.ALERT, "ମୋତେ ସାହାଯ୍ୟ ଦରକାର", Language.OR, "7"),
                TextMessage(MessageType.NORMAL, "नमस्ते", Language.HI, "8"),
            ),
            reloaded.messages,
        )
        reloaded.drain { }
        assertTrue(PendingQueue(file).messages.isEmpty())
    }
}
