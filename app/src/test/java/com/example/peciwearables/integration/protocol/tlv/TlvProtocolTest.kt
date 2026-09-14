package com.example.peciwearables.integration.protocol.tlv

import org.junit.Assert.*
import org.junit.Test


class TlvProtocolTest {



    @Test
    fun `TxRxMessageType ordinals match SDK`() {
        // Amostra de pontos críticos do mapeamento
        assertEquals(0, TxRxMessageType.IS_CHARGING.ordinal)
        assertEquals(2, TxRxMessageType.GET_MTU.ordinal)
        assertEquals(6, TxRxMessageType.GET_TYPE.ordinal)
        assertEquals(10, TxRxMessageType.GET_SENSOR_CONFIGURATION.ordinal)
        assertEquals(13, TxRxMessageType.GET_SENSOR_SCALARS.ordinal)
        assertEquals(14, TxRxMessageType.SENSOR_DATA.ordinal)
        assertEquals(15, TxRxMessageType.GET_VIBRATION_LOCATIONS.ordinal)
        assertEquals(17, TxRxMessageType.GET_FILE_TYPES.ordinal)
        assertEquals(30, TxRxMessageType.GET_TFLITE_NAME.ordinal)
        assertEquals(46, TxRxMessageType.IS_WIFI_AVAILABLE.ordinal)
        assertEquals(47, TxRxMessageType.GET_WIFI_SSID.ordinal)
        assertEquals(48, TxRxMessageType.SET_WIFI_SSID.ordinal)
        assertEquals(50, TxRxMessageType.SET_WIFI_PASSWORD.ordinal)
        assertEquals(52, TxRxMessageType.SET_WIFI_CONNECTION_ENABLED.ordinal)
        assertEquals(54, TxRxMessageType.IP_ADDRESS.ordinal)
        assertEquals(56, TxRxMessageType.CAMERA_STATUS.ordinal)
        assertEquals(57, TxRxMessageType.CAMERA_COMMAND.ordinal)
        assertEquals(60, TxRxMessageType.CAMERA_DATA.ordinal)
        assertEquals(61, TxRxMessageType.MICROPHONE_STATUS.ordinal)
        assertEquals(62, TxRxMessageType.MICROPHONE_COMMAND.ordinal)
        assertEquals(65, TxRxMessageType.MICROPHONE_DATA.ordinal)
        assertEquals(66, TxRxMessageType.IS_DISPLAY_AVAILABLE.ordinal)
        assertEquals(76, TxRxMessageType.SPRITE_SHEET_INDEX.ordinal)
    }

    @Test
    fun `TxRxMessageType has exactly 77 values`() {
        assertEquals(77, TxRxMessageType.entries.size)
    }

    @Test
    fun `TxRxMessageType fromOrdinal round-trips`() {
        for (type in TxRxMessageType.entries) {
            assertEquals(type, TxRxMessageType.fromOrdinal(type.ordinal))
        }
    }

    @Test
    fun `TxRxMessageType fromOrdinal returns null for invalid`() {
        assertNull(TxRxMessageType.fromOrdinal(77))
        assertNull(TxRxMessageType.fromOrdinal(255))
        assertNull(TxRxMessageType.fromOrdinal(-1))
    }



    @Test
    fun `SocketMessageType ordinals match SDK`() {
        assertEquals(0, SocketMessageType.PING.ordinal)
        assertEquals(1, SocketMessageType.PONG.ordinal)
        assertEquals(2, SocketMessageType.SET_REMOTE_RECEIVE_PORT.ordinal)
        assertEquals(3, SocketMessageType.BATTERY_LEVEL.ordinal)
        assertEquals(4, SocketMessageType.DEVICE_INFORMATION.ordinal)
        assertEquals(5, SocketMessageType.MESSAGE.ordinal)
    }

    @Test
    fun `SocketMessageType has exactly 6 values`() {
        assertEquals(6, SocketMessageType.entries.size)
    }


    @Test
    fun `encode empty-data message produces 3 bytes`() {
        // GET_TYPE = ordinal 6, no data
        val buf = TlvWriter.encode(TxRxMessageType.GET_TYPE)
        assertEquals(3, buf.size)
        assertEquals(6, buf[0].toInt() and 0xFF)   // type
        assertEquals(0, buf[1].toInt() and 0xFF)   // length low
        assertEquals(0, buf[2].toInt() and 0xFF)   // length high
    }

    @Test
    fun `encode message with data`() {
        val data = byteArrayOf(0x41, 0x42, 0x43) // "ABC"
        val buf = TlvWriter.encode(TxRxMessageType.SET_WIFI_SSID, data)

        // SET_WIFI_SSID = ordinal 48
        assertEquals(6, buf.size) // 1 + 2 + 3
        assertEquals(48, buf[0].toInt() and 0xFF)
        assertEquals(3, buf[1].toInt() and 0xFF)   // length low = 3
        assertEquals(0, buf[2].toInt() and 0xFF)   // length high = 0
        assertEquals(0x41, buf[3].toInt() and 0xFF)
        assertEquals(0x42, buf[4].toInt() and 0xFF)
        assertEquals(0x43, buf[5].toInt() and 0xFF)
    }

    @Test
    fun `encode large payload length is LE 16-bit`() {
        val data = ByteArray(300) { (it % 256).toByte() }
        val buf = TlvWriter.encode(TxRxMessageType.SENSOR_DATA, data)

        assertEquals(303, buf.size) // 1 + 2 + 300
        assertEquals(14, buf[0].toInt() and 0xFF) // SENSOR_DATA ordinal
        // 300 = 0x012C → low=0x2C, high=0x01
        assertEquals(0x2C, buf[1].toInt() and 0xFF)
        assertEquals(0x01, buf[2].toInt() and 0xFF)
    }



    @Test
    fun `encodeAll concatenates multiple messages`() {
        val messages = listOf(
            TlvWriter.Message(TxRxMessageType.GET_TYPE),
            TlvWriter.Message(TxRxMessageType.GET_NAME),
            TlvWriter.Message(TxRxMessageType.IS_WIFI_AVAILABLE)
        )
        val buf = TlvWriter.encodeAll(messages)

        // 3 messages × 3 bytes each (all empty data)
        assertEquals(9, buf.size)
        assertEquals(6, buf[0].toInt() and 0xFF)  // GET_TYPE
        assertEquals(4, buf[3].toInt() and 0xFF)  // GET_NAME
        assertEquals(46, buf[6].toInt() and 0xFF) // IS_WIFI_AVAILABLE
    }



    @Test
    fun `encodePartitioned respects MTU`() {
        // 10 messages × 3 bytes each = 30 bytes total
        // MTU = 15, max payload = 12 → fits 4 messages per packet
        val messages = List(10) { TlvWriter.Message(TxRxMessageType.GET_TYPE) }
        val packets = TlvWriter.encodePartitioned(messages, mtu = 15)

        // 10 messages / 4 per packet = 3 packets (4+4+2)
        assertEquals(3, packets.size)
        assertEquals(12, packets[0].size) // 4×3
        assertEquals(12, packets[1].size) // 4×3
        assertEquals(6, packets[2].size)  // 2×3
    }

    @Test
    fun `encodePartitioned single message bigger than MTU`() {
        val bigData = ByteArray(100)
        val messages = listOf(TlvWriter.Message(TxRxMessageType.SENSOR_DATA, bigData))
        val packets = TlvWriter.encodePartitioned(messages, mtu = 50)

        // A mensagem é maior que MTU-3 = 47, mas envia-se sozinha
        assertEquals(1, packets.size)
        assertEquals(103, packets[0].size)
    }



    @Test
    fun `socketPing produces correct bytes`() {
        val buf = TlvWriter.socketPing()
        assertEquals(3, buf.size)
        assertEquals(0, buf[0].toInt() and 0xFF) // PING ordinal
    }

    @Test
    fun `socketPong produces correct bytes`() {
        val buf = TlvWriter.socketPong()
        assertEquals(3, buf.size)
        assertEquals(1, buf[0].toInt() and 0xFF) // PONG ordinal
    }

    @Test
    fun `setRemoteReceivePort encodes port correctly`() {
        val buf = TlvWriter.setRemoteReceivePort(12345)
        assertEquals(5, buf.size) // 1 + 2 + 2
        assertEquals(2, buf[0].toInt() and 0xFF) // SET_REMOTE_RECEIVE_PORT ordinal

        // length = 2
        assertEquals(2, buf[1].toInt() and 0xFF)
        assertEquals(0, buf[2].toInt() and 0xFF)

        // 12345 = 0x3039 → low=0x39, high=0x30
        assertEquals(0x39, buf[3].toInt() and 0xFF)
        assertEquals(0x30, buf[4].toInt() and 0xFF)
    }

    @Test
    fun `wrapTxRxInSocketMessage wraps correctly`() {
        val txRx = TlvWriter.txRxGet(TxRxMessageType.GET_TYPE) // 3 bytes
        val wrapped = TlvWriter.wrapTxRxInSocketMessage(txRx)

        // Outer: type=MESSAGE(5), length=3, data=3 bytes
        assertEquals(6, wrapped.size)
        assertEquals(5, wrapped[0].toInt() and 0xFF) // MESSAGE ordinal
        assertEquals(3, wrapped[1].toInt() and 0xFF) // length low
        assertEquals(0, wrapped[2].toInt() and 0xFF) // length high
        // Inner TxRx
        assertEquals(6, wrapped[3].toInt() and 0xFF) // GET_TYPE
    }

    @Test
    fun `stringPayload encodes UTF-8`() {
        val payload = TlvWriter.stringPayload("MyWiFi")
        assertArrayEquals("MyWiFi".toByteArray(Charsets.UTF_8), payload)
    }

    @Test
    fun `booleanPayload encodes true as 1 and false as 0`() {
        assertArrayEquals(byteArrayOf(1), TlvWriter.booleanPayload(true))
        assertArrayEquals(byteArrayOf(0), TlvWriter.booleanPayload(false))
    }

    @Test
    fun `uint16Payload is little-endian`() {
        val payload = TlvWriter.uint16Payload(0x1234)
        assertEquals(2, payload.size)
        assertEquals(0x34, payload[0].toInt() and 0xFF)
        assertEquals(0x12, payload[1].toInt() and 0xFF)
    }



    @Test
    fun `parseTxRx single empty message`() {
        // GET_TYPE = ordinal 6, 0 bytes data
        val buf = byteArrayOf(6, 0, 0)
        val messages = TlvParser.parseTxRxToList(buf)

        assertEquals(1, messages.size)
        assertEquals(TxRxMessageType.GET_TYPE, messages[0].type)
        assertEquals(0, messages[0].data.size)
        assertTrue(messages[0].isLast)
    }

    @Test
    fun `parseTxRx single message with data`() {
        // IS_WIFI_AVAILABLE = ordinal 46, 1 byte data
        val buf = byteArrayOf(46, 1, 0, 1) // available = true
        val messages = TlvParser.parseTxRxToList(buf)

        assertEquals(1, messages.size)
        assertEquals(TxRxMessageType.IS_WIFI_AVAILABLE, messages[0].type)
        assertEquals(1, messages[0].data.size)
        assertEquals(1, messages[0].data[0].toInt())
    }

    @Test
    fun `parseTxRx multiple messages`() {
        // 3 messages concatenated
        val buf = byteArrayOf(
            6, 0, 0,         // GET_TYPE, 0 bytes
            4, 0, 0,         // GET_NAME, 0 bytes
            46, 1, 0, 1      // IS_WIFI_AVAILABLE, 1 byte (true)
        )
        val messages = TlvParser.parseTxRxToList(buf)

        assertEquals(3, messages.size)
        assertEquals(TxRxMessageType.GET_TYPE, messages[0].type)
        assertFalse(messages[0].isLast)
        assertEquals(TxRxMessageType.GET_NAME, messages[1].type)
        assertFalse(messages[1].isLast)
        assertEquals(TxRxMessageType.IS_WIFI_AVAILABLE, messages[2].type)
        assertTrue(messages[2].isLast)
    }

    @Test
    fun `parseTxRx message with larger data`() {
        // IP_ADDRESS = ordinal 54, data = "192.168.1.100" as UTF-8
        val ipBytes = "192.168.1.100".toByteArray(Charsets.UTF_8)
        val buf = ByteArray(1 + 2 + ipBytes.size)
        buf[0] = 54 // IP_ADDRESS
        buf[1] = (ipBytes.size and 0xFF).toByte()
        buf[2] = ((ipBytes.size shr 8) and 0xFF).toByte()
        System.arraycopy(ipBytes, 0, buf, 3, ipBytes.size)

        val messages = TlvParser.parseTxRxToList(buf)
        assertEquals(1, messages.size)
        assertEquals(TxRxMessageType.IP_ADDRESS, messages[0].type)
        assertEquals("192.168.1.100", String(messages[0].data, Charsets.UTF_8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseTxRx throws on invalid ordinal`() {
        val buf = byteArrayOf(99, 0, 0) // ordinal 99 > 76
        TlvParser.parseTxRxToList(buf)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseTxRx throws on truncated buffer`() {
        // Claims 10 bytes but only has 2
        val buf = byteArrayOf(6, 10, 0, 0x41, 0x42)
        TlvParser.parseTxRxToList(buf)
    }



    @Test
    fun `parseSocket ping message`() {
        val buf = byteArrayOf(0, 0, 0) // PING, 0 bytes
        val messages = TlvParser.parseSocketToList(buf)

        assertEquals(1, messages.size)
        assertEquals(SocketMessageType.PING, messages[0].type)
    }

    @Test
    fun `parseSocket pong message`() {
        val buf = byteArrayOf(1, 0, 0) // PONG
        val messages = TlvParser.parseSocketToList(buf)

        assertEquals(1, messages.size)
        assertEquals(SocketMessageType.PONG, messages[0].type)
    }

    @Test
    fun `parseSocket setRemoteReceivePort`() {
        // port = 12345 = 0x3039
        val buf = byteArrayOf(2, 2, 0, 0x39, 0x30)
        val messages = TlvParser.parseSocketToList(buf)

        assertEquals(1, messages.size)
        assertEquals(SocketMessageType.SET_REMOTE_RECEIVE_PORT, messages[0].type)

        val port = (messages[0].data[0].toInt() and 0xFF) or
                ((messages[0].data[1].toInt() and 0xFF) shl 8)
        assertEquals(12345, port)
    }

    @Test
    fun `parseSocket MESSAGE contains TxRx payload`() {
        // Build inner TxRx: GET_TYPE (ordinal=6, 0 bytes)
        val innerTxRx = byteArrayOf(6, 0, 0)

        // Outer socket: MESSAGE (ordinal=5), length=3
        val buf = byteArrayOf(5, 3, 0, 6, 0, 0)
        val socketMessages = TlvParser.parseSocketToList(buf)

        assertEquals(1, socketMessages.size)
        assertEquals(SocketMessageType.MESSAGE, socketMessages[0].type)

        // Parse the inner TxRx
        val txRxMessages = TlvParser.parseTxRxToList(socketMessages[0].data)
        assertEquals(1, txRxMessages.size)
        assertEquals(TxRxMessageType.GET_TYPE, txRxMessages[0].type)
    }

    @Test
    fun `parseSocketLenient processes valid prefix when datagram tail is truncated`() {
        val ping = byteArrayOf(SocketMessageType.PING.ordinal.toByte(), 0, 0)
        val truncatedTail = byteArrayOf(SocketMessageType.MESSAGE.ordinal.toByte(), 4, 0, 0x01)
        val result = TlvParser.parseSocketLenient(ping + truncatedTail)

        assertEquals(1, result.messages.size)
        assertEquals(SocketMessageType.PING, result.messages[0].type)
        assertTrue(result.hasError)
        assertEquals(3, result.bytesConsumed)
    }

    @Test
    fun `parseSocketLenient succeeds fully on complete datagram`() {
        val buf = byteArrayOf(
            SocketMessageType.PING.ordinal.toByte(), 0, 0,
            SocketMessageType.PONG.ordinal.toByte(), 0, 0
        )
        val result = TlvParser.parseSocketLenient(buf)

        assertEquals(2, result.messages.size)
        assertFalse(result.hasError)
        assertEquals(buf.size, result.bytesConsumed)
    }



    @Test
    fun `round-trip TxRx empty message`() {
        val original = TxRxMessageType.GET_SENSOR_CONFIGURATION
        val encoded = TlvWriter.txRxGet(original)
        val parsed = TlvParser.parseTxRxToList(encoded)

        assertEquals(1, parsed.size)
        assertEquals(original, parsed[0].type)
        assertEquals(0, parsed[0].data.size)
    }

    @Test
    fun `round-trip TxRx message with string data`() {
        val ssid = "MeuWiFi_5G"
        val encoded = TlvWriter.txRxSet(
            TxRxMessageType.SET_WIFI_SSID,
            TlvWriter.stringPayload(ssid)
        )
        val parsed = TlvParser.parseTxRxToList(encoded)

        assertEquals(1, parsed.size)
        assertEquals(TxRxMessageType.SET_WIFI_SSID, parsed[0].type)
        assertEquals(ssid, String(parsed[0].data, Charsets.UTF_8))
    }

    @Test
    fun `round-trip multiple TxRx messages`() {
        val messages = listOf(
            TlvWriter.Message(TxRxMessageType.IS_CHARGING),
            TlvWriter.Message(TxRxMessageType.GET_BATTERY_CURRENT),
            TlvWriter.Message(TxRxMessageType.GET_MTU),
            TlvWriter.Message(TxRxMessageType.GET_ID),
            TlvWriter.Message(TxRxMessageType.GET_NAME),
            TlvWriter.Message(TxRxMessageType.GET_TYPE),
            TlvWriter.Message(TxRxMessageType.GET_CURRENT_TIME),
            TlvWriter.Message(TxRxMessageType.GET_SENSOR_CONFIGURATION),
            TlvWriter.Message(TxRxMessageType.GET_SENSOR_SCALARS),
            TlvWriter.Message(TxRxMessageType.GET_VIBRATION_LOCATIONS),
            TlvWriter.Message(TxRxMessageType.GET_FILE_TYPES),
            TlvWriter.Message(TxRxMessageType.IS_WIFI_AVAILABLE),
        )
        val encoded = TlvWriter.encodeAll(messages)
        val parsed = TlvParser.parseTxRxToList(encoded)

        assertEquals(messages.size, parsed.size)
        for (i in messages.indices) {
            assertEquals(messages[i].type, parsed[i].type)
        }
    }

    @Test
    fun `round-trip socket ping`() {
        val encoded = TlvWriter.socketPing()
        val parsed = TlvParser.parseSocketToList(encoded)
        assertEquals(1, parsed.size)
        assertEquals(SocketMessageType.PING, parsed[0].type)
    }

    @Test
    fun `round-trip socket pong`() {
        val encoded = TlvWriter.socketPong()
        val parsed = TlvParser.parseSocketToList(encoded)
        assertEquals(1, parsed.size)
        assertEquals(SocketMessageType.PONG, parsed[0].type)
    }

    @Test
    fun `round-trip setRemoteReceivePort`() {
        val port = 54321
        val encoded = TlvWriter.setRemoteReceivePort(port)
        val parsed = TlvParser.parseSocketToList(encoded)

        assertEquals(1, parsed.size)
        assertEquals(SocketMessageType.SET_REMOTE_RECEIVE_PORT, parsed[0].type)

        val parsedPort = (parsed[0].data[0].toInt() and 0xFF) or
                ((parsed[0].data[1].toInt() and 0xFF) shl 8)
        assertEquals(port, parsedPort)
    }

    @Test
    fun `round-trip wrapped TxRx in socket message`() {
        // Build TxRx: SET_WIFI_SSID = "TestSSID"
        val ssid = "TestSSID"
        val txRx = TlvWriter.txRxSet(
            TxRxMessageType.SET_WIFI_SSID,
            TlvWriter.stringPayload(ssid)
        )

        // Wrap in socket MESSAGE
        val socketBuf = TlvWriter.wrapTxRxInSocketMessage(txRx)

        // Parse outer socket
        val socketMessages = TlvParser.parseSocketToList(socketBuf)
        assertEquals(1, socketMessages.size)
        assertEquals(SocketMessageType.MESSAGE, socketMessages[0].type)

        // Parse inner TxRx
        val txRxMessages = TlvParser.parseTxRxToList(socketMessages[0].data)
        assertEquals(1, txRxMessages.size)
        assertEquals(TxRxMessageType.SET_WIFI_SSID, txRxMessages[0].type)
        assertEquals(ssid, String(txRxMessages[0].data, Charsets.UTF_8))
    }

    @Test
    fun `round-trip RequiredInformation messages (full handshake set)`() {
        // Exactamente a lista RequiredInformationConnectionMessages do Device.ts
        val requiredMessages = listOf(
            TxRxMessageType.IS_CHARGING,
            TxRxMessageType.GET_BATTERY_CURRENT,
            TxRxMessageType.GET_ID,
            TxRxMessageType.GET_MTU,
            TxRxMessageType.GET_NAME,
            TxRxMessageType.GET_TYPE,
            TxRxMessageType.GET_CURRENT_TIME,
            TxRxMessageType.GET_SENSOR_CONFIGURATION,
            TxRxMessageType.GET_SENSOR_SCALARS,
            TxRxMessageType.GET_VIBRATION_LOCATIONS,
            TxRxMessageType.GET_FILE_TYPES,
            TxRxMessageType.IS_WIFI_AVAILABLE,
        )

        val messages = requiredMessages.map { TlvWriter.Message(it) }
        val encoded = TlvWriter.txRxMessages(messages)
        val parsed = TlvParser.parseTxRxToList(encoded)

        assertEquals(requiredMessages.size, parsed.size)
        for (i in requiredMessages.indices) {
            assertEquals(requiredMessages[i], parsed[i].type)
            assertEquals(0, parsed[i].data.size) // all are "get" with no data
        }
    }



    @Test
    fun `parseTxRx callback is invoked for each message`() {
        val buf = TlvWriter.encodeAll(
            TlvWriter.Message(TxRxMessageType.GET_TYPE),
            TlvWriter.Message(TxRxMessageType.IS_WIFI_AVAILABLE)
        )

        val received = mutableListOf<TxRxMessageType>()
        TlvParser.parseTxRx(buf) { type, _, _ ->
            received.add(type)
        }

        assertEquals(listOf(TxRxMessageType.GET_TYPE, TxRxMessageType.IS_WIFI_AVAILABLE), received)
    }


    @Test
    fun `parseTxRx with offset and length`() {
        val prefix = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) // junk
        val tlv = TlvWriter.txRxGet(TxRxMessageType.GET_NAME) // 3 bytes
        val suffix = byteArrayOf(0xDD.toByte()) // junk

        val buf = prefix + tlv + suffix

        val parsed = TlvParser.parseTxRxToList(buf, offset = 2, length = 3)
        assertEquals(1, parsed.size)
        assertEquals(TxRxMessageType.GET_NAME, parsed[0].type)
    }



    @Test
    fun `empty buffer produces no messages`() {
        val parsed = TlvParser.parseTxRxToList(byteArrayOf())
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `encodeAll empty list produces empty array`() {
        val buf = TlvWriter.encodeAll(emptyList<TlvWriter.Message<TxRxMessageType>>())
        assertEquals(0, buf.size)
    }

    @Test
    fun `wifi config full workflow encode and parse`() {
        val messages = listOf(
            TlvWriter.Message(TxRxMessageType.SET_WIFI_SSID, TlvWriter.stringPayload("MyNetwork")),
            TlvWriter.Message(TxRxMessageType.SET_WIFI_PASSWORD, TlvWriter.stringPayload("secretpass")),
            TlvWriter.Message(TxRxMessageType.SET_WIFI_CONNECTION_ENABLED, TlvWriter.booleanPayload(true)),
        )

        val encoded = TlvWriter.txRxMessages(messages)
        val parsed = TlvParser.parseTxRxToList(encoded)

        assertEquals(3, parsed.size)

        assertEquals(TxRxMessageType.SET_WIFI_SSID, parsed[0].type)
        assertEquals("MyNetwork", String(parsed[0].data, Charsets.UTF_8))

        assertEquals(TxRxMessageType.SET_WIFI_PASSWORD, parsed[1].type)
        assertEquals("secretpass", String(parsed[1].data, Charsets.UTF_8))

        assertEquals(TxRxMessageType.SET_WIFI_CONNECTION_ENABLED, parsed[2].type)
        assertEquals(1, parsed[2].data[0].toInt())
    }
}
