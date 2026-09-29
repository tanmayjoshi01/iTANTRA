package com.itantra.app.link

import com.itantra.app.link.Translation.Reply
import com.itantra.app.link.Translation.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Receiver translation routing (one model: Hindi → Odia), the node protocol, and the node client. */
class TranslationTest {

    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        server?.close()
    }

    @Test
    fun onlyHindiToOdia_isTranslated() {
        assertEquals(setOf(Language.HI to Language.OR), Translation.DIRECTIONS)
        assertEquals(listOf(Language.OR), Translation.TARGETS)
        assertEquals(Route.Translate(Language.HI, Language.OR), Translation.route(Language.HI, Language.OR))
    }

    @Test
    fun directionsWithoutAModel_areUnsupported_notFaked() {
        val unsupported = listOf(
            Language.HI to Language.AS,
            Language.AS to Language.HI,
            Language.AS to Language.OR,
            Language.OR to Language.HI,
            Language.OR to Language.AS,
        )
        unsupported.forEach { (src, tgt) -> assertEquals(Route.Unsupported(src, tgt), Translation.route(src, tgt)) }
    }

    @Test
    fun sameLanguage_andOriginal_arePassthrough() {
        Language.entries.forEach { l ->
            assertEquals(Route.Original, Translation.route(l, l))
            assertEquals(Route.Original, Translation.route(l, null))
        }
    }

    @Test
    fun routing_dependsOnlyOnLanguage_soNormalAlertSosKeepTheirType() {
        MessageType.entries.forEach { type ->
            val m = TextLines.decode(TextLines.encode(TextMessage(type, "मेरा नाम पारस है", Language.HI, "id$type"))!!)
            assertEquals(type, m.type)
            assertEquals(Route.Translate(Language.HI, Language.OR), Translation.route(m.language, Language.OR))
        }
    }

    @Test
    fun malformedOrUnknownLanguage_fallsBackToHindiSource() {
        val m = TextLines.decode("[ALERT|xx|id=1] मदद चाहिए") // unknown code: treated as Hindi, as before
        assertEquals(Language.HI, m.language)
        assertEquals(Route.Translate(Language.HI, Language.OR), Translation.route(m.language, Language.OR))
    }

    @Test
    fun request_isOneLine_andEmptyTextIsNotSent() {
        assertEquals("hi\tor\tएक दो तीन", Translation.request("एक\tदो\nतीन", Language.HI, Language.OR))
        assertNull(Translation.request("  \n ", Language.HI, Language.OR))
    }

    @Test
    fun replies_parse_andFailuresNeverThrow() {
        assertEquals(Reply.Ok("ମୋ ନାମ ପାରସ", 420), Translation.parseReply("OK\t420\tମୋ ନାମ ପାରସ"))
        assertEquals(Reply.Failed("unsupported direction hi-as"), Translation.parseReply("ERR\tunsupported direction hi-as"))
        assertTrue(Translation.parseReply(null) is Reply.Failed)
        assertTrue(Translation.parseReply("") is Reply.Failed)
        assertTrue(Translation.parseReply("OK\t12\t  ") is Reply.Failed) // empty translation is not a translation
        assertTrue(Translation.parseReply("garbage") is Reply.Failed)
    }

    /** A fake node on localhost that answers each line with [reply]. */
    private fun fakeNode(reply: (String) -> String): Int {
        val s = ServerSocket(0).also { server = it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (_: Exception) {
                    break
                }
                client.use {
                    val line = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8)).readLine() ?: return@use
                    it.getOutputStream().write((reply(line) + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        }
        return s.localPort
    }

    @Test
    fun client_sendsRequest_andReturnsTranslation() {
        var received: String? = null
        val port = fakeNode { received = it; if (it == "HEALTH") "HEALTH\thi-or" else "OK\t300\tନମସ୍କାର" }
        val client = NodeTranslator("127.0.0.1", port)
        assertEquals(Reply.Ok("ନମସ୍କାର", 300), client.translate("नमस्ते", Language.HI, Language.OR))
        assertEquals("hi\tor\tनमस्ते", received)
        assertTrue(client.healthy(Language.HI, Language.OR))
        assertFalse(client.healthy(Language.HI, Language.AS))
    }

    @Test
    fun client_reportsNodeErrors_andUnreachableNode_withoutThrowing() {
        val port = fakeNode { "ERR\ttranslation failed: RuntimeError" }
        assertEquals(Reply.Failed("translation failed: RuntimeError"), NodeTranslator("127.0.0.1", port).translate("x", Language.HI, Language.OR))

        val closed = ServerSocket(0).run { localPort.also { close() } }
        val down = NodeTranslator("127.0.0.1", closed, connectTimeoutMs = 500)
        val reply = down.translate("नमस्ते", Language.HI, Language.OR)
        assertTrue(reply is Reply.Failed && "unreachable" in reply.reason)
        assertFalse(down.healthy(Language.HI, Language.OR))
        assertTrue(down.translate("  ", Language.HI, Language.OR) is Reply.Failed)
    }
}
