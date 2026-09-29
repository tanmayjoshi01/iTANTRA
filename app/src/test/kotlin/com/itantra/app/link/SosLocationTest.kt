package com.itantra.app.link

import com.itantra.app.SosLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** SOS location in the wire format, backward compatibility, and choosing a location fix. */
class SosLocationTest {

    private val fix = GeoFix(19.076, 72.8777, fixTimeMs = 1_790_000_000_000, accuracyM = 15)
    private val sos = TextMessage(MessageType.SOS, "SOS: तुरंत मदद चाहिए (CPH2613)", Language.HI, "5a5a5a5a", fix, 1_790_000_005_000)

    @Test
    fun sosWithLocation_roundTrips() {
        val line = TextLines.encode(sos)!!
        assertEquals(
            "[SOS|hi|id=5a5a5a5a|loc=19.076000,72.877700|acc=15|fix=1790000000000|t=1790000005000] SOS: तुरंत मदद चाहिए (CPH2613)",
            line,
        )
        assertEquals(sos, TextLines.decode(line))
    }

    @Test
    fun negativeCoordinates_andCommaDecimalLocale_stillEncodeWithDots() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // "%.6f" would give "19,076000" here
            val south = sos.copy(location = GeoFix(-33.8688, -151.2093))
            val line = TextLines.encode(south)!!
            assertTrue(line, "loc=-33.868800,-151.209300" in line)
            assertEquals(south, TextLines.decode(line))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun sosWithoutLocation_isStillSos_withNoInventedCoordinates() {
        val noLoc = sos.copy(location = null)
        val decoded = TextLines.decode(TextLines.encode(noLoc)!!)
        assertEquals(MessageType.SOS, decoded.type)
        assertNull(decoded.location)
        assertEquals(noLoc, decoded)
    }

    @Test
    fun malformedOrOutOfRangeLocation_isDropped_notGuessed() {
        listOf("loc=abc", "loc=91,10", "loc=10,181", "loc=1", "loc=1,2,3", "loc=NaN,1", "loc=,").forEach { bad ->
            val m = TextLines.decode("[SOS|hi|id=1|$bad|t=5] SOS")
            assertEquals(bad, MessageType.SOS, m.type)
            assertNull(bad, m.location)
            assertEquals(5L, m.sentAtMs)
        }
        assertNull(TextLines.decode("[SOS|hi|id=1|t=x] SOS").sentAtMs)
    }

    @Test
    fun existingFormats_areUnchanged() {
        assertEquals("[NORMAL] नमस्ते", TextLines.encode(TextMessage(MessageType.NORMAL, "नमस्ते")))
        assertEquals("[ALERT|or|id=1] ନମସ୍କାର", TextLines.encode(TextMessage(MessageType.ALERT, "ନମସ୍କାର", Language.OR, "1")))
        assertEquals(TextMessage(MessageType.SOS, "मदद", Language.HI, "2"), TextLines.decode("[SOS|hi|id=2] मदद"))
        // An old-style reader skips parts it does not know: the location parts are not languages.
        listOf("loc=19.0,72.8", "acc=15", "fix=1", "t=1").forEach { assertNull(Language.fromCode(it)) }
    }

    @Test
    fun locationSurvivesThePendingQueue() {
        val file = kotlin.io.path.createTempFile().toFile().also { it.deleteOnExit() }
        PendingQueue(file).add(TextLines.encode(sos)!!, first = true)
        assertEquals(listOf(sos), PendingQueue(file).messages)
    }

    @Test
    fun pickFix_prefersNewestWithinAge_andRejectsStaleOrInvalid() {
        val now = 10_000_000L
        val old = GeoFix(1.0, 1.0, now - 10 * 60_000)
        val recent = GeoFix(2.0, 2.0, now - 30_000)
        val newer = GeoFix(3.0, 3.0, now - 5_000)
        assertEquals(newer, SosLocator.pickFix(listOf(old, recent, newer), now, SosLocator.RECENT_MAX_AGE_MS))
        assertNull(SosLocator.pickFix(listOf(old), now, SosLocator.RECENT_MAX_AGE_MS))
        assertEquals(old, SosLocator.pickFix(listOf(old), now, SosLocator.FALLBACK_MAX_AGE_MS))
        assertNull(SosLocator.pickFix(listOf(GeoFix(95.0, 1.0, now)), now, SosLocator.RECENT_MAX_AGE_MS))
        assertNull(SosLocator.pickFix(listOf(GeoFix(1.0, 1.0, null)), now, SosLocator.RECENT_MAX_AGE_MS))
        assertNull(SosLocator.pickFix(listOf(GeoFix(1.0, 1.0, now + 5 * 60_000)), now, SosLocator.RECENT_MAX_AGE_MS))
        assertNull(SosLocator.pickFix(emptyList(), now, SosLocator.FALLBACK_MAX_AGE_MS))
    }
}
