package com.itantra.app.link

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Receiver-side translation. There is ONE translation model (IndicTrans2
 * indic-indic 320M, on the laptop translation node) and ONE direction it is
 * used for. Other pairs are not offered: TTS support for a language is not
 * translation support.
 */
object Translation {
    val DIRECTIONS: Set<Pair<Language, Language>> = setOf(Language.HI to Language.OR)

    /** Languages the receiver can choose to hear besides "original". */
    val TARGETS: List<Language> = DIRECTIONS.map { it.second }.distinct()

    sealed interface Route {
        /** Speak the original text in its own language (no translation asked for, or same language). */
        data object Original : Route

        data class Translate(val source: Language, val target: Language) : Route

        /** A translation was asked for, but the model does not cover this pair. */
        data class Unsupported(val source: Language, val target: Language) : Route
    }

    /** What to do with a message in [source] when the receiver wants to hear [hearing] (null = original). */
    fun route(source: Language, hearing: Language?): Route = when {
        hearing == null || hearing == source -> Route.Original
        (source to hearing) in DIRECTIONS -> Route.Translate(source, hearing)
        else -> Route.Unsupported(source, hearing)
    }

    /** The request line for the node, or null if there is nothing to translate. */
    fun request(text: String, source: Language, target: Language): String? {
        val clean = text.replace(Regex("[\t\r\n]+"), " ").trim().ifEmpty { return null }
        return "${source.code}\t${target.code}\t$clean"
    }

    sealed interface Reply {
        data class Ok(val text: String, val nodeMs: Long) : Reply

        data class Failed(val reason: String) : Reply
    }

    /** Parses the node's reply line; anything unexpected is a failure, never an exception. */
    fun parseReply(line: String?): Reply {
        if (line == null) return Reply.Failed("no reply from translation node")
        val parts = line.split('\t', limit = 3)
        return when {
            parts[0] == "OK" && parts.size == 3 && parts[2].isNotBlank() ->
                Reply.Ok(parts[2].trim(), parts[1].toLongOrNull() ?: -1)
            parts[0] == "ERR" -> Reply.Failed(parts.getOrNull(1)?.ifBlank { null } ?: "translation failed")
            else -> Reply.Failed("malformed reply from translation node")
        }
    }
}

/** Translates one text; never throws. */
fun interface Translator {
    fun translate(text: String, source: Language, target: Language): Translation.Reply
}

/**
 * Client for the laptop translation node (app/scripts/translation_node.py):
 * one short TCP exchange per message on the local link. Blocking; call off
 * the main thread.
 */
class NodeTranslator(
    @Volatile var host: String = DEFAULT_HOST,
    @Volatile var port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = 1_500,
    private val readTimeoutMs: Int = 15_000,
) : Translator {

    override fun translate(text: String, source: Language, target: Language): Translation.Reply {
        val request = Translation.request(text, source, target) ?: return Translation.Reply.Failed("empty text")
        return try {
            Translation.parseReply(exchange(request))
        } catch (e: Exception) {
            Translation.Reply.Failed("translation node unreachable (${e.javaClass.simpleName})")
        }
    }

    /** True if the node answers HEALTH and offers this direction. */
    fun healthy(source: Language, target: Language): Boolean = try {
        exchange("HEALTH")?.let { it.startsWith("HEALTH\t") && "${source.code}-${target.code}" in it } == true
    } catch (_: Exception) {
        false
    }

    /** Sends one line and returns the reply line (null if the node closed without replying). */
    private fun exchange(line: String): String? = Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = readTimeoutMs
        val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
        writer.write(line + "\n")
        writer.flush()
        BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
    }

    companion object {
        /** Reached through `adb reverse tcp:8765 tcp:8765`, or change to the laptop's Wi-Fi IP. */
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8765
    }
}
