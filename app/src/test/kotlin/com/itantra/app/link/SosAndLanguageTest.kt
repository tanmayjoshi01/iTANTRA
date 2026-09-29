package com.itantra.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SOS in the wire format, type preservation, malformed input, and script-based language tagging. */
class SosAndLanguageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun roundTrip(m: TextMessage) = TextLines.decode(TextLines.encode(m)!!)

    @Test
    fun sos_roundTrips_withLanguageAndId() {
        val sos = TextMessage(MessageType.SOS, "SOS from CPH2613", Language.OR, "5f5f5f5f")
        assertEquals("[SOS|or|id=5f5f5f5f] SOS from CPH2613", TextLines.encode(sos))
        assertEquals(sos, roundTrip(sos))
        assertEquals(TextMessage(MessageType.SOS, "मदद"), TextLines.decode("[SOS] मदद"))
    }

    @Test
    fun eachType_staysItsOwnType() {
        MessageType.entries.forEach { type ->
            listOf(Language.HI, Language.AS, Language.OR).forEach { lang ->
                val m = TextMessage(type, lang.sample, lang, "id${type.ordinal}${lang.code}")
                assertEquals(type, roundTrip(m).type)
                assertEquals(lang, roundTrip(m).language)
            }
        }
        assertEquals(MessageType.NORMAL, TextLines.decode("[NORMAL] नमस्ते").type)
        assertEquals(MessageType.ALERT, TextLines.decode("[ALERT] मदद चाहिए").type)
    }

    @Test
    fun malformedLines_doNotThrow_andFallBackToNormalText() {
        val junk = listOf("", " ", "[", "[SOS", "[SOS]", "[SOS|", "[SOS|id=] x", "[sos] x", "[SOS||||] x", "]]] [[[", "[SOS|or|id=1]x")
        junk.forEach { line ->
            val m = TextLines.decode(line) // must not throw
            if (line == "[SOS|id=] x" || line == "[SOS||||] x") {
                assertEquals(MessageType.SOS, m.type)
                assertNull(m.id)
            } else {
                assertEquals("line: '$line'", MessageType.NORMAL, m.type)
            }
        }
    }

    @Test
    fun typedText_isTaggedByItsScript() {
        assertEquals(Language.HI, Language.ofScript("नमस्ते"))
        assertEquals(Language.AS, Language.ofScript("নমস্কাৰ"))
        assertEquals(Language.OR, Language.ofScript("ନମସ୍କାର"))
        assertEquals(Language.HI, Language.ofScript("मेरा नाम Paras है"))
        assertNull(Language.ofScript("SOS 123"))
        assertNull(Language.ofScript(""))
    }

    @Test
    fun pendingSos_goesAheadOfEarlierMessages_andSurvivesRestart() {
        val file = tmp.newFile("pending.txt")
        val q = PendingQueue(file)
        q.add(TextLines.encode(TextMessage(MessageType.NORMAL, "एक", Language.HI, "1"))!!)
        q.add(TextLines.encode(TextMessage(MessageType.SOS, "SOS", Language.HI, "2"))!!, first = true)
        assertEquals(listOf(MessageType.SOS, MessageType.NORMAL), PendingQueue(file).messages.map { it.type })
    }
}
