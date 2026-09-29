package com.itantra.app.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** Real TCP over localhost: receiver and sender as used on Phone B and Phone A. */
class TcpTextLinkTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val receiver = TcpTextReceiver(scope, port = 0) // 0 = any free port
    private val sender = TcpTextSender(scope)

    @After
    fun tearDown() {
        sender.close()
        receiver.stop()
        scope.cancel()
    }

    private fun startReceiver(): Int = runBlocking {
        receiver.start()
        withTimeout(5_000) {
            (receiver.state.first { it.status is TcpTextReceiver.Status.Listening }.status as TcpTextReceiver.Status.Listening).port
        }
    }

    private fun connect(port: Int) = runBlocking {
        sender.connect("127.0.0.1", port)
        withTimeout(5_000) { sender.state.first { it.status is TcpTextSender.Status.Connected } }
    }

    private fun awaitMessages(count: Int): List<String> = runBlocking {
        withTimeout(5_000) { receiver.state.first { it.messages.size >= count }.messages }
    }

    @Test
    fun oneHindiMessage_arrivesExactly() {
        connect(startReceiver())
        sender.send("नमस्ते")
        assertEquals(listOf("नमस्ते"), awaitMessages(1))
    }

    @Test
    fun multipleMessages_arriveInOrder_andAppend() {
        connect(startReceiver())
        listOf("नमस्ते", "मेरा नाम पारस है", "आप कैसे हैं").forEach(sender::send)
        assertEquals(listOf("नमस्ते", "मेरा नाम पारस है", "आप कैसे हैं"), awaitMessages(3))
        runBlocking { withTimeout(5_000) { sender.state.first { it.sentCount == 3 } } }
    }

    @Test
    fun embeddedNewlines_doNotSplitAMessage() {
        connect(startReceiver())
        sender.send("पहली पंक्ति\nदूसरी पंक्ति")
        assertEquals(listOf("पहली पंक्ति दूसरी पंक्ति"), awaitMessages(1))
    }

    @Test
    fun receiverSurvivesClientDisconnect_andAcceptsReconnect_keepingMessages() {
        val port = startReceiver()
        connect(port)
        sender.send("पहला")
        awaitMessages(1)

        runBlocking {
            sender.disconnect()
            withTimeout(5_000) { receiver.state.first { it.status is TcpTextReceiver.Status.Listening } }
        }

        connect(port)
        sender.send("दूसरा")
        assertEquals(listOf("पहला", "दूसरा"), awaitMessages(2))
    }

    @Test
    fun connectToClosedPort_reportsFailure_withoutThrowing() {
        val closedPort = ServerSocket(0).use { it.localPort }
        runBlocking {
            sender.connect("127.0.0.1", closedPort)
            val failed = withTimeout(10_000) { sender.state.first { it.status is TcpTextSender.Status.Failed } }
            assertTrue((failed.status as TcpTextSender.Status.Failed).message.startsWith("Connection failed"))
        }
    }

    @Test
    fun sendWhileDisconnected_isNotSent_andSaysSo() {
        runBlocking {
            sender.send("कोई नहीं सुन रहा").join()
            val state = sender.state.value
            assertEquals(TcpTextSender.Status.Disconnected, state.status)
            assertEquals(0, state.sentCount)
            assertEquals("NOT sent (not connected): कोई नहीं सुन रहा", state.lastEvent)
        }
    }

    @Test
    fun successfulSend_reportsLastEvent() {
        connect(startReceiver())
        sender.send("मेरा नाम पारस है")
        awaitMessages(1)
        runBlocking {
            assertEquals("Sent: मेरा नाम पारस है", withTimeout(5_000) { sender.state.first { it.sentCount == 1 } }.lastEvent)
        }
    }

    @Test
    fun encodedAlertAndNormal_travelOverTcp_andDecode() {
        connect(startReceiver())
        TextLines.encode(TextMessage(MessageType.ALERT, "मदद चाहिए"))?.let(sender::send)
        TextLines.encode(TextMessage(MessageType.NORMAL, "नमस्ते"))?.let(sender::send)
        assertEquals(
            listOf(TextMessage(MessageType.ALERT, "मदद चाहिए"), TextMessage(MessageType.NORMAL, "नमस्ते")),
            awaitMessages(2).map(TextLines::decode),
        )
    }

    @Test
    fun sosAlertAndNormal_keepTheirTypeOverTcp() {
        connect(startReceiver())
        val sent = listOf(
            TextMessage(MessageType.NORMAL, "नमस्ते", Language.HI, "n1"),
            TextMessage(MessageType.ALERT, "मदद चाहिए", Language.HI, "a1"),
            TextMessage(MessageType.SOS, "SOS from CPH2613", Language.HI, "s1"),
        )
        sent.forEach { TextLines.encode(it)?.let(sender::send) }
        assertEquals(sent, awaitMessages(3).map(TextLines::decode))
    }
}
