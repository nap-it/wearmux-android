package com.example.peciwearables.integration.udp

import com.example.peciwearables.integration.protocol.tlv.SocketMessageType
import com.example.peciwearables.integration.protocol.tlv.TlvParser
import com.example.peciwearables.integration.protocol.tlv.TlvWriter
import com.example.peciwearables.integration.protocol.tlv.TxRxMessageType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test


class UdpSocketHandlerTest {

    private val capturedTx = mutableListOf<ByteArray>()
    private val capturedTxRx = mutableListOf<ByteArray>()
    private var connectedCount = 0
    private var pingCount = 0

    private lateinit var handler: UdpSocketHandler

    companion object {
        const val LOCAL_PORT = 51234
    }

    @Before
    fun setup() {
        capturedTx.clear()
        capturedTxRx.clear()
        connectedCount = 0
        pingCount = 0
        handler = UdpSocketHandler(
            localPort      = LOCAL_PORT,
            onTxNeeded     = { capturedTx.add(it) },
            onConnected    = { connectedCount++ },
            onPingReceived = { pingCount++ },
            onTxRxMessage  = { capturedTxRx.add(it) },
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Constrói uma mensagem socket TLV raw. */
    private fun socketMsg(type: SocketMessageType, data: ByteArray = ByteArray(0)): ByteArray {
        val len = data.size
        val buf = ByteArray(3 + len)
        buf[0] = type.ordinal.toByte()
        buf[1] = (len and 0xFF).toByte()
        buf[2] = ((len shr 8) and 0xFF).toByte()
        data.copyInto(buf, 3)
        return buf
    }

    /** uint16 LE */
    private fun uint16LE(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    // ── Estado inicial ────────────────────────────────────────────────────────

    @Test
    fun `isConnected is false initially`() {
        assertFalse(handler.isConnected)
    }

    @Test
    fun `pingsReceived is 0 initially`() {
        assertEquals(0, handler.pingsReceived)
    }

    @Test
    fun `pongsReceived is 0 initially`() {
        assertEquals(0, handler.pongsReceived)
    }

    // ── Builders (companion, puros) ───────────────────────────────────────────

    @Test
    fun `buildHandshakeBytes type is SET_REMOTE_RECEIVE_PORT`() {
        val bytes = UdpSocketHandler.buildHandshakeBytes(LOCAL_PORT)
        assertEquals(SocketMessageType.SET_REMOTE_RECEIVE_PORT.ordinal, bytes[0].toInt() and 0xFF)
    }

    @Test
    fun `buildHandshakeBytes encodes port as uint16 LE`() {
        val port = 51234
        val bytes = UdpSocketHandler.buildHandshakeBytes(port)
        val parsed = TlvParser.parseSocketToList(bytes)
        assertEquals(1, parsed.size)
        assertEquals(SocketMessageType.SET_REMOTE_RECEIVE_PORT, parsed[0].type)
        val portBack = (parsed[0].data[0].toInt() and 0xFF) or ((parsed[0].data[1].toInt() and 0xFF) shl 8)
        assertEquals(port, portBack)
    }

    @Test
    fun `buildHandshakeBytes round-trip port 3000`() {
        val bytes = UdpSocketHandler.buildHandshakeBytes(3000)
        val parsed = TlvParser.parseSocketToList(bytes)
        val port = (parsed[0].data[0].toInt() and 0xFF) or ((parsed[0].data[1].toInt() and 0xFF) shl 8)
        assertEquals(3000, port)
    }

    @Test
    fun `buildPingBytes type is PING`() {
        val bytes = UdpSocketHandler.buildPingBytes()
        assertEquals(SocketMessageType.PING.ordinal, bytes[0].toInt() and 0xFF)
    }

    @Test
    fun `buildPingBytes payload is empty`() {
        val parsed = TlvParser.parseSocketToList(UdpSocketHandler.buildPingBytes())
        assertEquals(0, parsed[0].data.size)
    }

    @Test
    fun `buildPongBytes type is PONG`() {
        val bytes = UdpSocketHandler.buildPongBytes()
        assertEquals(SocketMessageType.PONG.ordinal, bytes[0].toInt() and 0xFF)
    }

    @Test
    fun `buildPongBytes payload is empty`() {
        val parsed = TlvParser.parseSocketToList(UdpSocketHandler.buildPongBytes())
        assertEquals(0, parsed[0].data.size)
    }

    // ── Handshake ─────────────────────────────────────────────────────────────

    @Test
    fun `SET_REMOTE_RECEIVE_PORT echo with correct port triggers onConnected`() {
        handler.onRxBytes(socketMsg(SocketMessageType.SET_REMOTE_RECEIVE_PORT, uint16LE(LOCAL_PORT)))
        assertTrue(handler.isConnected)
        assertEquals(1, connectedCount)
    }

    @Test
    fun `SET_REMOTE_RECEIVE_PORT echo with wrong port does NOT connect`() {
        handler.onRxBytes(socketMsg(SocketMessageType.SET_REMOTE_RECEIVE_PORT, uint16LE(9999)))
        assertFalse(handler.isConnected)
        assertEquals(0, connectedCount)
    }

    @Test
    fun `SET_REMOTE_RECEIVE_PORT echo with empty payload does NOT connect`() {
        handler.onRxBytes(socketMsg(SocketMessageType.SET_REMOTE_RECEIVE_PORT, ByteArray(0)))
        assertFalse(handler.isConnected)
        assertEquals(0, connectedCount)
    }

    @Test
    fun `onConnected fires exactly once even if echo received multiple times`() {
        repeat(3) {
            handler.onRxBytes(socketMsg(SocketMessageType.SET_REMOTE_RECEIVE_PORT, uint16LE(LOCAL_PORT)))
        }
        assertEquals(1, connectedCount)
    }

    @Test
    fun `handshake with all supported port values including 0xFFFF`() {
        val handler65535 = UdpSocketHandler(
            localPort      = 65535,
            onTxNeeded     = {},
            onConnected    = { connectedCount++ },
        )
        handler65535.onRxBytes(socketMsg(SocketMessageType.SET_REMOTE_RECEIVE_PORT, uint16LE(65535)))
        assertEquals(1, connectedCount)
    }

    @Test
    fun `handshake with port 1024`() {
        val h = UdpSocketHandler(localPort = 1024, onTxNeeded = {}, onConnected = { connectedCount++ })
        h.onRxBytes(socketMsg(SocketMessageType.SET_REMOTE_RECEIVE_PORT, uint16LE(1024)))
        assertTrue(h.isConnected)
    }

    // ── PING → PONG ───────────────────────────────────────────────────────────

    @Test
    fun `PING from glasses triggers onTxNeeded with PONG bytes`() {
        handler.onRxBytes(socketMsg(SocketMessageType.PING))
        assertEquals(1, capturedTx.size)
        val responseParsed = TlvParser.parseSocketToList(capturedTx[0])
        assertEquals(SocketMessageType.PONG, responseParsed[0].type)
    }

    @Test
    fun `PING increments pingsReceived`() {
        handler.onRxBytes(socketMsg(SocketMessageType.PING))
        handler.onRxBytes(socketMsg(SocketMessageType.PING))
        assertEquals(2, handler.pingsReceived)
    }

    @Test
    fun `PING triggers onPingReceived callback`() {
        handler.onRxBytes(socketMsg(SocketMessageType.PING))
        assertEquals(1, pingCount)
    }

    @Test
    fun `PING response is exactly 3 bytes (TLV empty PONG)`() {
        handler.onRxBytes(socketMsg(SocketMessageType.PING))
        assertEquals(3, capturedTx[0].size)
    }

    @Test
    fun `PONG from glasses increments pongsReceived`() {
        handler.onRxBytes(socketMsg(SocketMessageType.PONG))
        handler.onRxBytes(socketMsg(SocketMessageType.PONG))
        assertEquals(2, handler.pongsReceived)
    }

    @Test
    fun `PONG does NOT trigger onTxNeeded`() {
        handler.onRxBytes(socketMsg(SocketMessageType.PONG))
        assertEquals(0, capturedTx.size)
    }

    // ── MESSAGE routing ───────────────────────────────────────────────────────

    @Test
    fun `MESSAGE socket type routes inner bytes to onTxRxMessage`() {
        val innerTxRx = TlvWriter.encode(TxRxMessageType.CAMERA_DATA, byteArrayOf(0x01, 0x02))
        handler.onRxBytes(socketMsg(SocketMessageType.MESSAGE, innerTxRx))
        assertEquals(1, capturedTxRx.size)
    }

    @Test
    fun `MESSAGE inner bytes are the exact TxRx payload`() {
        val innerTxRx = TlvWriter.encode(TxRxMessageType.MICROPHONE_DATA, byteArrayOf(0xAA.toByte()))
        handler.onRxBytes(socketMsg(SocketMessageType.MESSAGE, innerTxRx))
        assertArrayEquals(innerTxRx, capturedTxRx[0])
    }

    @Test
    fun `MESSAGE with empty inner payload does NOT call onTxRxMessage`() {
        handler.onRxBytes(socketMsg(SocketMessageType.MESSAGE, ByteArray(0)))
        assertEquals(0, capturedTxRx.size)
    }

    @Test
    fun `MESSAGE inner bytes are parseable as TxRx TLV`() {
        val innerTxRx = TlvWriter.encode(TxRxMessageType.SENSOR_DATA, byteArrayOf(1, 2, 3, 4))
        handler.onRxBytes(socketMsg(SocketMessageType.MESSAGE, innerTxRx))
        val parsed = TlvParser.parseTxRxToList(capturedTxRx[0], 0, capturedTxRx[0].size)
        assertEquals(1, parsed.size)
        assertEquals(TxRxMessageType.SENSOR_DATA, parsed[0].type)
    }

    @Test
    fun `MESSAGE with multiple inner TxRx messages routes all bytes`() {
        val inner = TlvWriter.encode(TxRxMessageType.CAMERA_STATUS) +
                TlvWriter.encode(TxRxMessageType.MICROPHONE_STATUS)
        handler.onRxBytes(socketMsg(SocketMessageType.MESSAGE, inner))
        val parsed = TlvParser.parseTxRxToList(capturedTxRx[0], 0, capturedTxRx[0].size)
        assertEquals(2, parsed.size)
        assertEquals(TxRxMessageType.CAMERA_STATUS, parsed[0].type)
        assertEquals(TxRxMessageType.MICROPHONE_STATUS, parsed[1].type)
    }

    // ── Múltiplas mensagens socket num datagrama ──────────────────────────────

    @Test
    fun `multiple socket messages in one datagram all handled`() {
        val combined = socketMsg(SocketMessageType.PING) + socketMsg(SocketMessageType.PONG)
        handler.onRxBytes(combined)
        assertEquals(1, handler.pingsReceived)
        assertEquals(1, handler.pongsReceived)
    }

    @Test
    fun `handshake echo + PING in same datagram both handled`() {
        val combined = socketMsg(SocketMessageType.SET_REMOTE_RECEIVE_PORT, uint16LE(LOCAL_PORT)) +
                socketMsg(SocketMessageType.PING)
        handler.onRxBytes(combined)
        assertTrue(handler.isConnected)
        assertEquals(1, handler.pingsReceived)
    }

    @Test
    fun `handshake + MESSAGE in same datagram both handled`() {
        val innerTxRx = TlvWriter.encode(TxRxMessageType.CAMERA_DATA)
        val combined = socketMsg(SocketMessageType.SET_REMOTE_RECEIVE_PORT, uint16LE(LOCAL_PORT)) +
                socketMsg(SocketMessageType.MESSAGE, innerTxRx)
        handler.onRxBytes(combined)
        assertTrue(handler.isConnected)
        assertEquals(1, capturedTxRx.size)
    }

    // ── buildHandshakeBytes via TlvWriter.setRemoteReceivePort ────────────────

    @Test
    fun `TlvWriter setRemoteReceivePort matches buildHandshakeBytes`() {
        val a = UdpSocketHandler.buildHandshakeBytes(LOCAL_PORT)
        val b = TlvWriter.setRemoteReceivePort(LOCAL_PORT)
        assertArrayEquals(a, b)
    }

    @Test
    fun `TlvWriter socketPing matches buildPingBytes`() {
        assertArrayEquals(UdpSocketHandler.buildPingBytes(), TlvWriter.socketPing())
    }

    @Test
    fun `TlvWriter socketPong matches buildPongBytes`() {
        assertArrayEquals(UdpSocketHandler.buildPongBytes(), TlvWriter.socketPong())
    }

    // ── reset() ───────────────────────────────────────────────────────────────

    @Test
    fun `reset clears isConnected`() {
        handler.onRxBytes(socketMsg(SocketMessageType.SET_REMOTE_RECEIVE_PORT, uint16LE(LOCAL_PORT)))
        handler.reset()
        assertFalse(handler.isConnected)
    }

    @Test
    fun `reset clears pingsReceived`() {
        handler.onRxBytes(socketMsg(SocketMessageType.PING))
        handler.reset()
        assertEquals(0, handler.pingsReceived)
    }

    @Test
    fun `reset clears pongsReceived`() {
        handler.onRxBytes(socketMsg(SocketMessageType.PONG))
        handler.reset()
        assertEquals(0, handler.pongsReceived)
    }

    @Test
    fun `after reset handshake works again`() {
        handler.onRxBytes(socketMsg(SocketMessageType.SET_REMOTE_RECEIVE_PORT, uint16LE(LOCAL_PORT)))
        handler.reset()
        handler.onRxBytes(socketMsg(SocketMessageType.SET_REMOTE_RECEIVE_PORT, uint16LE(LOCAL_PORT)))
        assertTrue(handler.isConnected)
        assertEquals(2, connectedCount)
    }

    // ── Robustez ──────────────────────────────────────────────────────────────

    @Test
    fun `empty byte array does not crash`() {
        handler.onRxBytes(ByteArray(0))
    }

    @Test
    fun `malformed datagram does not crash`() {
        handler.onRxBytes(byteArrayOf(0xFF.toByte(), 0x00, 0x00)) // ordinal 255 = invalido
        assertEquals(1, handler.parseErrors)
    }

    @Test
    fun `truncated TLV does not crash`() {
        handler.onRxBytes(byteArrayOf(SocketMessageType.MESSAGE.ordinal.toByte(), 0x10, 0x00)) // claims 16 bytes mas tem 0
        assertEquals(1, handler.parseErrors)
        assertEquals(1, handler.parseTruncatedErrors)
    }

    @Test
    fun `datagrama com prefixo valido e cauda truncada processa prefixo e mantem sessao`() {
        val validPrefix = socketMsg(SocketMessageType.SET_REMOTE_RECEIVE_PORT, uint16LE(LOCAL_PORT))
        val truncatedTail = byteArrayOf(SocketMessageType.MESSAGE.ordinal.toByte(), 0x04, 0x00, 0x01)

        handler.onRxBytes(validPrefix + truncatedTail)

        assertTrue(handler.isConnected)
        assertEquals(1, connectedCount)
        assertEquals(1, handler.parseErrors)
        assertEquals(1, handler.parseErrorsWithValidPrefix)
        assertEquals(1, handler.parseTruncatedErrors)
    }

    @Test
    fun `reset tambem limpa contadores de parse`() {
        handler.onRxBytes(byteArrayOf(SocketMessageType.MESSAGE.ordinal.toByte(), 0x10, 0x00))
        assertEquals(1, handler.parseErrors)

        handler.reset()

        assertEquals(0, handler.parseErrors)
        assertEquals(0, handler.parseErrorsWithValidPrefix)
        assertEquals(0, handler.parseTruncatedErrors)
        assertEquals(0, handler.datagramsReceived)
    }
}
