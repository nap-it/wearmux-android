package com.example.peciwearables.integration.ble

import com.example.peciwearables.integration.protocol.tlv.TlvParser
import com.example.peciwearables.integration.protocol.tlv.TlvWriter
import com.example.peciwearables.integration.protocol.tlv.TxRxMessageType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test


class OmiRxHandlerTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private val capturedTx = mutableListOf<ByteArray>()
    private val capturedIps = mutableListOf<String>()
    private val capturedMtus = mutableListOf<Int>()
    private val capturedCameraData = mutableListOf<Pair<Int, ByteArray>>()
    private val capturedMicrophoneConfigurations = mutableListOf<Pair<Int, Int>>()
    private val capturedCameraConfigurations = mutableListOf<ParsedCameraConfiguration>()
    private val capturedSensorConfigurations = mutableListOf<ParsedSensorConfiguration>()
    private var connectedCount = 0

    private lateinit var handler: OmiRxHandler

    @Before
    fun setup() {
        capturedTx.clear()
        capturedIps.clear()
        capturedMtus.clear()
        capturedCameraData.clear()
        capturedMicrophoneConfigurations.clear()
        capturedCameraConfigurations.clear()
        capturedSensorConfigurations.clear()
        connectedCount = 0
        handler = OmiRxHandler(
            onTxNeeded          = { capturedTx.add(it) },
            onConnected         = { connectedCount++ },
            onGlassesIpReceived = { capturedIps.add(it) },
        )
        handler.onMtu = { capturedMtus.add(it) }
        handler.onCameraData = { subType, data -> capturedCameraData.add(subType to data) }
        handler.onMicrophoneConfiguration = { sampleRateHz, bitDepth ->
            capturedMicrophoneConfigurations.add(sampleRateHz to bitDepth)
        }
        handler.onCameraConfiguration = { capturedCameraConfigurations.add(it) }
        handler.onSensorConfiguration = { capturedSensorConfigurations.add(it) }
    }

    /** Constrói bytes TLV de uma mensagem com payload booleano. */
    private fun boolMsg(type: TxRxMessageType, value: Boolean): ByteArray =
        TlvWriter.encode(type, TlvWriter.booleanPayload(value))

    /** Constrói bytes TLV de uma mensagem sem payload (GET request). */
    private fun emptyMsg(type: TxRxMessageType): ByteArray =
        TlvWriter.encode(type)

    private fun uint16LEMsg(type: TxRxMessageType, value: Int): ByteArray {
        val payload = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
        )
        return TlvWriter.encode(type, payload)
    }

    /** Envia todas as 12 respostas obrigatórias ao handler. */
    private fun sendAllRequiredResponses() {
        OmiRxHandler.REQUIRED_RESPONSE_TYPES.forEach { type ->
            handler.onRxBytes(emptyMsg(type))
        }
    }

    // ── Estado inicial ────────────────────────────────────────────────────────

    @Test
    fun `isConnected is false initially`() {
        assertFalse(handler.isConnected)
    }

    @Test
    fun `glassesIp is null initially`() {
        assertNull(handler.glassesIp)
    }

    @Test
    fun `pendingResponseTypes contains all 12 on start`() {
        assertEquals(OmiRxHandler.REQUIRED_RESPONSE_TYPES, handler.pendingResponseTypes())
    }

    // ── Tracking de respostas obrigatórias ────────────────────────────────────

    @Test
    fun `receiving a required type removes it from pending`() {
        handler.onRxBytes(emptyMsg(TxRxMessageType.GET_ID))
        assertFalse(handler.pendingResponseTypes().contains(TxRxMessageType.GET_ID))
    }

    @Test
    fun `pending decreases by one per unique required response`() {
        val before = handler.pendingResponseTypes().size
        handler.onRxBytes(emptyMsg(TxRxMessageType.GET_NAME))
        assertEquals(before - 1, handler.pendingResponseTypes().size)
    }

    @Test
    fun `receiving same required type twice only removes it once`() {
        handler.onRxBytes(emptyMsg(TxRxMessageType.GET_ID))
        handler.onRxBytes(emptyMsg(TxRxMessageType.GET_ID))
        // Deve ter apenas 11 removidos no total (não -2)
        assertEquals(OmiRxHandler.REQUIRED_RESPONSE_TYPES.size - 1, handler.pendingResponseTypes().size)
    }

    @Test
    fun `non-required type does not affect pending count`() {
        val before = handler.pendingResponseTypes().size
        // IP_ADDRESS não está na lista de required responses
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.IP_ADDRESS, byteArrayOf(192.toByte(), 168.toByte(), 1, 1)))
        assertEquals(before, handler.pendingResponseTypes().size)
    }

    // ── Handshake completo (isConnected) ──────────────────────────────────────

    @Test
    fun `isConnected becomes true only after all 12 responses received`() {
        val types = OmiRxHandler.REQUIRED_RESPONSE_TYPES.toList()

        // Enviar 11 de 12 — ainda não deve estar connected
        types.dropLast(1).forEach { handler.onRxBytes(emptyMsg(it)) }
        assertFalse(handler.isConnected)
        assertEquals(0, connectedCount)

        // Enviar o último
        handler.onRxBytes(emptyMsg(types.last()))
        assertTrue(handler.isConnected)
    }

    @Test
    fun `onConnected callback fires exactly once after all required responses`() {
        sendAllRequiredResponses()
        assertEquals(1, connectedCount)
    }

    @Test
    fun `onConnected not fired before all responses received`() {
        val types = OmiRxHandler.REQUIRED_RESPONSE_TYPES.toList()
        types.dropLast(1).forEach { handler.onRxBytes(emptyMsg(it)) }
        assertEquals(0, connectedCount)
    }

    @Test
    fun `onConnected fires only once even when more messages arrive later`() {
        sendAllRequiredResponses()
        // Mensagens adicionais após handshake completo
        handler.onRxBytes(emptyMsg(TxRxMessageType.GET_ID))
        handler.onRxBytes(emptyMsg(TxRxMessageType.IS_CHARGING))
        assertEquals(1, connectedCount)
    }

    @Test
    fun `pendingResponseTypes is empty after all responses received`() {
        sendAllRequiredResponses()
        assertTrue(handler.pendingResponseTypes().isEmpty())
    }

    // ── IS_WIFI_AVAILABLE ─────────────────────────────────────────────────────

    @Test
    fun `IS_WIFI_AVAILABLE true triggers TX with wifi info requests`() {
        handler.onRxBytes(boolMsg(TxRxMessageType.IS_WIFI_AVAILABLE, true))
        assertEquals(1, capturedTx.size)
    }

    @Test
    fun `IS_WIFI_AVAILABLE true TX contains exactly 6 messages`() {
        handler.onRxBytes(boolMsg(TxRxMessageType.IS_WIFI_AVAILABLE, true))
        val bytes = capturedTx.last()
        val parsed = TlvParser.parseTxRxToList(bytes, 0, bytes.size)
        assertEquals(6, parsed.size)
    }

    @Test
    fun `IS_WIFI_AVAILABLE true TX contains correct message types in order`() {
        handler.onRxBytes(boolMsg(TxRxMessageType.IS_WIFI_AVAILABLE, true))
        val bytes = capturedTx.last()
        val parsed = TlvParser.parseTxRxToList(bytes, 0, bytes.size)
        val expected = OmiRxHandler.WIFI_INFO_REQUEST_TYPES
        for (i in expected.indices) {
            assertEquals("Message $i", expected[i], parsed[i].type)
        }
    }

    @Test
    fun `IS_WIFI_AVAILABLE true TX first message is GET_WIFI_SSID`() {
        handler.onRxBytes(boolMsg(TxRxMessageType.IS_WIFI_AVAILABLE, true))
        val bytes = capturedTx.last()
        val parsed = TlvParser.parseTxRxToList(bytes, 0, bytes.size)
        assertEquals(TxRxMessageType.GET_WIFI_SSID, parsed[0].type)
    }

    @Test
    fun `IS_WIFI_AVAILABLE true TX last message is IS_WIFI_SECURE`() {
        handler.onRxBytes(boolMsg(TxRxMessageType.IS_WIFI_AVAILABLE, true))
        val bytes = capturedTx.last()
        val parsed = TlvParser.parseTxRxToList(bytes, 0, bytes.size)
        assertEquals(TxRxMessageType.IS_WIFI_SECURE, parsed.last().type)
    }

    @Test
    fun `IS_WIFI_AVAILABLE true TX contains IP_ADDRESS request`() {
        handler.onRxBytes(boolMsg(TxRxMessageType.IS_WIFI_AVAILABLE, true))
        val bytes = capturedTx.last()
        val parsed = TlvParser.parseTxRxToList(bytes, 0, bytes.size)
        assertTrue(parsed.any { it.type == TxRxMessageType.IP_ADDRESS })
    }

    @Test
    fun `IS_WIFI_AVAILABLE false does NOT trigger TX`() {
        handler.onRxBytes(boolMsg(TxRxMessageType.IS_WIFI_AVAILABLE, false))
        assertEquals(0, capturedTx.size)
    }

    @Test
    fun `IS_WIFI_AVAILABLE with empty payload does NOT trigger TX`() {
        handler.onRxBytes(emptyMsg(TxRxMessageType.IS_WIFI_AVAILABLE))
        assertEquals(0, capturedTx.size)
    }

    @Test
    fun `IS_WIFI_AVAILABLE still marks its required response as received`() {
        handler.onRxBytes(boolMsg(TxRxMessageType.IS_WIFI_AVAILABLE, true))
        assertFalse(handler.pendingResponseTypes().contains(TxRxMessageType.IS_WIFI_AVAILABLE))
    }

    // ── buildWifiInfoRequestBytes (companion, puro) ───────────────────────────

    @Test
    fun `buildWifiInfoRequestBytes produces valid TLV parseable bytes`() {
        val bytes = OmiRxHandler.buildWifiInfoRequestBytes()
        val parsed = TlvParser.parseTxRxToList(bytes, 0, bytes.size)
        assertEquals(6, parsed.size)
    }

    @Test
    fun `buildWifiInfoRequestBytes all messages have empty payload`() {
        val bytes = OmiRxHandler.buildWifiInfoRequestBytes()
        val parsed = TlvParser.parseTxRxToList(bytes, 0, bytes.size)
        parsed.forEach { assertEquals(0, it.data.size) }
    }

    // ── IP_ADDRESS ────────────────────────────────────────────────────────────

    @Test
    fun `IP_ADDRESS 4-byte binary parsed to dotted decimal`() {
        val ipBytes = byteArrayOf(192.toByte(), 168.toByte(), 1, 42)
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.IP_ADDRESS, ipBytes))
        assertEquals("192.168.1.42", handler.glassesIp)
    }

    @Test
    fun `IP_ADDRESS 4-byte binary triggers onGlassesIpReceived`() {
        val ipBytes = byteArrayOf(10, 0, 0, 1)
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.IP_ADDRESS, ipBytes))
        assertEquals(1, capturedIps.size)
        assertEquals("10.0.0.1", capturedIps[0])
    }

    @Test
    fun `IP_ADDRESS UTF-8 string format also works`() {
        val ip = "192.168.0.100"
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.IP_ADDRESS, ip.toByteArray(Charsets.UTF_8)))
        assertEquals(ip, handler.glassesIp)
        assertEquals(ip, capturedIps[0])
    }

    @Test
    fun `IP_ADDRESS empty payload does NOT trigger callback`() {
        handler.onRxBytes(emptyMsg(TxRxMessageType.IP_ADDRESS))
        assertEquals(0, capturedIps.size)
        assertNull(handler.glassesIp)
    }

    @Test
    fun `IP_ADDRESS malformed UTF-8 string does NOT trigger callback`() {
        val bad = "not-an-ip".toByteArray(Charsets.UTF_8)
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.IP_ADDRESS, bad))
        assertEquals(0, capturedIps.size)
        assertNull(handler.glassesIp)
    }

    @Test
    fun `IP_ADDRESS 255-range octets parsed correctly`() {
        val ip = byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 0)
        val result = OmiRxHandler.parseIpAddress(ip)
        assertEquals("255.255.255.0", result)
    }

    // ── parseIpAddress (companion, puro) ──────────────────────────────────────

    @Test
    fun `parseIpAddress empty returns null`() {
        assertNull(OmiRxHandler.parseIpAddress(ByteArray(0)))
    }

    @Test
    fun `parseIpAddress 4-byte binary returns dotted decimal`() {
        assertEquals("10.0.0.1", OmiRxHandler.parseIpAddress(byteArrayOf(10, 0, 0, 1)))
    }

    @Test
    fun `parseIpAddress valid utf8 string returns same string`() {
        val ip = "172.16.0.1"
        assertEquals(ip, OmiRxHandler.parseIpAddress(ip.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `parseIpAddress invalid utf8 string returns null`() {
        assertNull(OmiRxHandler.parseIpAddress("hostname.local".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `parseIpAddress 3-byte buffer not 4 tries utf8 fallback`() {
        // 3 bytes que não formam um IP válido em string → null
        assertNull(OmiRxHandler.parseIpAddress(byteArrayOf(1, 2, 3)))
    }

    // ── Múltiplas mensagens num único pacote ──────────────────────────────────

    @Test
    fun `GET_MTU little endian payload triggers callback`() {
        handler.onRxBytes(uint16LEMsg(TxRxMessageType.GET_MTU, 517))
        assertEquals(listOf(517), capturedMtus)
    }

    @Test
    fun `parseCameraConfiguration extracts resolution and qualityFactor`() {
        val payload = byteArrayOf(
            0, 0x80.toByte(), 0x02,
            1, 90, 0,
        )
        val parsed = OmiRxHandler.parseCameraConfiguration(payload)
        assertNotNull(parsed)
        assertEquals(640, parsed?.resolution)
        assertEquals(90, parsed?.qualityFactor)
    }

    @Test
    fun `GET_CAMERA_CONFIGURATION updates parsed camera settings`() {
        val payload = byteArrayOf(
            0, 0x80.toByte(), 0x02,
            1, 75, 0,
        )
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.GET_CAMERA_CONFIGURATION, payload))
        assertEquals(1, capturedCameraConfigurations.size)
        assertEquals(640, capturedCameraConfigurations[0].resolution)
        assertEquals(75, capturedCameraConfigurations[0].qualityFactor)
    }

    @Test
    fun `parseSensorConfiguration extracts camera and microphone rates`() {
        val payload = byteArrayOf(
            15, 20, 0,
            16, 5, 0,
        )
        val parsed = OmiRxHandler.parseSensorConfiguration(payload)
        assertNotNull(parsed)
        assertEquals(20, parsed?.cameraRateMs)
        assertEquals(5, parsed?.microphoneRateMs)
    }

    @Test
    fun `GET_SENSOR_CONFIGURATION updates parsed sensor rates`() {
        val payload = byteArrayOf(
            15, 40, 0,
            16, 10, 0,
        )
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.GET_SENSOR_CONFIGURATION, payload))
        assertEquals(1, capturedSensorConfigurations.size)
        assertEquals(40, capturedSensorConfigurations[0].cameraRateMs)
        assertEquals(10, capturedSensorConfigurations[0].microphoneRateMs)
    }

    @Test
    fun `GET_SENSOR_CONFIGURATION with camera requests required camera info`() {
        val payload = byteArrayOf(15, 20, 0)
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.GET_SENSOR_CONFIGURATION, payload))
        assertTrue(capturedTx.isNotEmpty())
        val parsed = TlvParser.parseTxRxToList(capturedTx.last(), 0, capturedTx.last().size)
        assertEquals(
            OmiRxHandler.CAMERA_INFO_REQUEST_TYPES,
            parsed.map { it.type }
        )
    }

    @Test
    fun `GET_SENSOR_CONFIGURATION with microphone requests required microphone info`() {
        val payload = byteArrayOf(16, 5, 0)
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.GET_SENSOR_CONFIGURATION, payload))
        assertTrue(capturedTx.isNotEmpty())
        val parsed = TlvParser.parseTxRxToList(capturedTx.last(), 0, capturedTx.last().size)
        assertEquals(
            OmiRxHandler.MICROPHONE_INFO_REQUEST_TYPES,
            parsed.map { it.type }
        )
    }

    @Test
    fun `multiple TLV messages in one RX packet all handled`() {
        // Concatenar GET_ID + GET_NAME num único pacote
        val combined = emptyMsg(TxRxMessageType.GET_ID) + emptyMsg(TxRxMessageType.GET_NAME)
        handler.onRxBytes(combined)
        assertFalse(handler.pendingResponseTypes().contains(TxRxMessageType.GET_ID))
        assertFalse(handler.pendingResponseTypes().contains(TxRxMessageType.GET_NAME))
    }

    @Test
    fun `all 12 required responses in one large packet triggers onConnected`() {
        val allBytes = OmiRxHandler.REQUIRED_RESPONSE_TYPES
            .fold(ByteArray(0)) { acc, type -> acc + emptyMsg(type) }
        handler.onRxBytes(allBytes)
        assertTrue(handler.isConnected)
        assertEquals(1, connectedCount)
    }

    @Test
    fun `IS_WIFI_AVAILABLE true + IP_ADDRESS in same packet both handled`() {
        val combined = boolMsg(TxRxMessageType.IS_WIFI_AVAILABLE, true) +
            TlvWriter.encode(TxRxMessageType.IP_ADDRESS, byteArrayOf(192.toByte(), 168.toByte(), 1, 10))
        handler.onRxBytes(combined)
        // WiFi info request enviado
        assertEquals(1, capturedTx.size)
        // IP recebido
        assertEquals("192.168.1.10", handler.glassesIp)
        assertEquals(1, capturedIps.size)
    }

    // ── reset() ───────────────────────────────────────────────────────────────

    @Test
    fun `reset restores pending responses to all 12`() {
        sendAllRequiredResponses()
        assertTrue(handler.isConnected)
        handler.reset()
        assertEquals(OmiRxHandler.REQUIRED_RESPONSE_TYPES, handler.pendingResponseTypes())
    }

    @Test
    fun `reset clears isConnected`() {
        sendAllRequiredResponses()
        handler.reset()
        assertFalse(handler.isConnected)
    }

    @Test
    fun `reset clears glassesIp`() {
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.IP_ADDRESS, byteArrayOf(1, 2, 3, 4)))
        handler.reset()
        assertNull(handler.glassesIp)
    }

    @Test
    fun `after reset onConnected fires again when all responses received`() {
        sendAllRequiredResponses()
        handler.reset()
        sendAllRequiredResponses()
        assertEquals(2, connectedCount)
    }

    // ── Robustez ──────────────────────────────────────────────────────────────

    @Test
    fun `empty byte array does not crash`() {
        handler.onRxBytes(ByteArray(0))
        // Sem assert — só verificar que não lança excepção
    }

    @Test
    fun `REQUIRED_RESPONSE_TYPES has exactly 12 entries`() {
        assertEquals(12, OmiRxHandler.REQUIRED_RESPONSE_TYPES.size)
    }

    @Test
    fun `WIFI_INFO_REQUEST_TYPES has exactly 6 entries`() {
        assertEquals(6, OmiRxHandler.WIFI_INFO_REQUEST_TYPES.size)
    }

    
    private fun expectedAckBytes(bytesReceived: Int): ByteArray {
        val payload = java.nio.ByteBuffer.allocate(4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(bytesReceived)
            .array()
        return TlvWriter.encode(TxRxMessageType.FILE_BYTES_TRANSFERRED, payload)
    }

    private fun uint8Msg(type: TxRxMessageType, value: Int): ByteArray =
        TlvWriter.encode(type, byteArrayOf(value.toByte()))

    private fun uint32LEMsg(type: TxRxMessageType, value: Int): ByteArray {
        val payload = java.nio.ByteBuffer.allocate(4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()
        return TlvWriter.encode(type, payload)
    }

    @Test
    fun `GET_FILE_TYPE routes to fileTransferHandler`() {
        handler.onRxBytes(uint8Msg(TxRxMessageType.GET_FILE_TYPE, 4))
        assertEquals(4, handler.fileTransferHandler.fileType)
    }

    @Test
    fun `SET_FILE_TYPE also routes to fileTransferHandler`() {
        handler.onRxBytes(uint8Msg(TxRxMessageType.SET_FILE_TYPE, 2))
        assertEquals(2, handler.fileTransferHandler.fileType)
    }

    @Test
    fun `GET_FILE_LENGTH routes to fileTransferHandler`() {
        handler.onRxBytes(uint32LEMsg(TxRxMessageType.GET_FILE_LENGTH, 1024))
        assertEquals(1024, handler.fileTransferHandler.expectedLength)
    }

    @Test
    fun `SET_FILE_LENGTH also routes to fileTransferHandler`() {
        handler.onRxBytes(uint32LEMsg(TxRxMessageType.SET_FILE_LENGTH, 512))
        assertEquals(512, handler.fileTransferHandler.expectedLength)
    }

    @Test
    fun `GET_FILE_CHECKSUM routes to fileTransferHandler`() {
        val checksum = 0xABCD1234L
        val payload = java.nio.ByteBuffer.allocate(4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(checksum.toInt())
            .array()
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.GET_FILE_CHECKSUM, payload))
        assertEquals(checksum, handler.fileTransferHandler.expectedChecksum)
    }

    @Test
    fun `GET_FILE_BLOCK sends FILE_BYTES_TRANSFERRED ACK via onTxNeeded`() {
        // Setup: inform handler of file length so it knows the total
        handler.onRxBytes(uint8Msg(TxRxMessageType.GET_FILE_TYPE, 4))
        handler.onRxBytes(uint32LEMsg(TxRxMessageType.GET_FILE_LENGTH, 100))

        val blockBytes = ByteArray(30) { it.toByte() }
        capturedTx.clear()  // ignore TX from previous steps
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.GET_FILE_BLOCK, blockBytes))

        // ACK must be sent: FILE_BYTES_TRANSFERRED with 30 (bytes received so far)
        assertTrue("ACK was not sent via onTxNeeded", capturedTx.isNotEmpty())
        assertArrayEquals(expectedAckBytes(30), capturedTx.last())
    }

    // routeCommand tests removed — OmiGlassesBleClient was rewritten for ESP32-S3 firmware protocol

    @Test
    fun `FILE_TRANSFER_STATUS idle resets fileTransferHandler`() {
        handler.onRxBytes(uint8Msg(TxRxMessageType.GET_FILE_TYPE, 4))
        handler.onRxBytes(uint32LEMsg(TxRxMessageType.GET_FILE_LENGTH, 100))
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.GET_FILE_BLOCK, ByteArray(40)))

        handler.onRxBytes(uint8Msg(TxRxMessageType.FILE_TRANSFER_STATUS, 0 /* idle */))

        assertEquals(-1, handler.fileTransferHandler.fileType)
        assertEquals(0, handler.fileTransferHandler.bytesReceived)
    }

    @Test
    fun `full file transfer end-to-end fires onFileReceived`() {
        val receivedFiles = mutableListOf<Pair<Int, ByteArray>>()
        handler.onFileReceived = { type, data -> receivedFiles.add(type to data) }

        val fileData = byteArrayOf(10, 20, 30, 40, 50)
        val crc = java.util.zip.CRC32()
        crc.update(fileData)
        val checksum = crc.value

        val checksumPayload = java.nio.ByteBuffer.allocate(4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(checksum.toInt())
            .array()

        handler.onRxBytes(uint8Msg(TxRxMessageType.GET_FILE_TYPE, 4))
        handler.onRxBytes(uint32LEMsg(TxRxMessageType.GET_FILE_LENGTH, fileData.size))
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.GET_FILE_CHECKSUM, checksumPayload))
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.GET_FILE_BLOCK, fileData))

        assertEquals(1, receivedFiles.size)
        assertEquals(4, receivedFiles[0].first)
        assertArrayEquals(fileData, receivedFiles[0].second)
    }

    @Test
    fun `reset also resets fileTransferHandler`() {
        handler.onRxBytes(uint8Msg(TxRxMessageType.GET_FILE_TYPE, 4))
        handler.onRxBytes(uint32LEMsg(TxRxMessageType.GET_FILE_LENGTH, 200))
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.GET_FILE_BLOCK, ByteArray(50)))

        handler.reset()

        assertEquals(-1, handler.fileTransferHandler.fileType)
        assertEquals(0, handler.fileTransferHandler.bytesReceived)
        assertEquals(0, handler.fileTransferHandler.expectedLength)
    }

    @Test
    fun `CAMERA_DATA framed payload with valid subType is routed`() {
        val payload = byteArrayOf(
            3, 2, 0,
            0x11, 0x22
        )

        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.CAMERA_DATA, payload))

        assertEquals(1, capturedCameraData.size)
        assertEquals(3, capturedCameraData[0].first)
        assertArrayEquals(byteArrayOf(0x11, 0x22), capturedCameraData[0].second)
    }

    @Test
    fun `GET_MICROPHONE_CONFIGURATION emits parsed profile`() {
        val payload = byteArrayOf(
            0, 0, // 8k
            1, 1, // 16-bit
        )
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.GET_MICROPHONE_CONFIGURATION, payload))
        assertEquals(1, capturedMicrophoneConfigurations.size)
        assertEquals(8_000 to 16, capturedMicrophoneConfigurations.first())
    }

    @Test
    fun `SET_MICROPHONE_CONFIGURATION emits parsed profile`() {
        val payload = byteArrayOf(
            0, 1, // 16k
            1, 0, // 8-bit
        )
        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.SET_MICROPHONE_CONFIGURATION, payload))
        assertEquals(1, capturedMicrophoneConfigurations.size)
        assertEquals(16_000 to 8, capturedMicrophoneConfigurations.first())
    }

    @Test
    fun `CAMERA_DATA framed payload split across packets is reassembled`() {
        val part1 = byteArrayOf(3)
        val part2 = byteArrayOf(
            4, 0,
            0x11, 0x22
        )
        val part3 = byteArrayOf(0x33, 0x44)

        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.CAMERA_DATA, part1))
        assertTrue(capturedCameraData.isEmpty())

        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.CAMERA_DATA, part2))
        assertTrue(capturedCameraData.isEmpty())

        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.CAMERA_DATA, part3))
        assertEquals(1, capturedCameraData.size)
        assertEquals(3, capturedCameraData[0].first)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), capturedCameraData[0].second)
    }

    @Test
    fun `CAMERA_DATA malformed payload with invalid subType is ignored`() {
        val payload = byteArrayOf(
            208.toByte(), 1, 0,
            0x55
        )

        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.CAMERA_DATA, payload))

        assertTrue(capturedCameraData.isEmpty())
    }

    @Test
    fun `CAMERA_DATA legacy payload still falls back when first byte is a valid subType`() {
        val payload = byteArrayOf(
            3,
            0x11,
            0x22,
            0x33
        )

        handler.onRxBytes(TlvWriter.encode(TxRxMessageType.CAMERA_DATA, payload))

        assertEquals(1, capturedCameraData.size)
        assertEquals(3, capturedCameraData[0].first)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), capturedCameraData[0].second)
    }
}
