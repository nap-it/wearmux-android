package com.example.peciwearables.integration.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder


data class PacketHeader(
    val version: Int,
    val msgType: Int,
    val flags: Int,
    val deviceId: ULong,
    val streamId: Int,
    val seq: Long,
    val timestampUs: Long,
    val payloadLen: Int
) {
    companion object {
        const val MAGIC = "OMI1"
        const val HEADER_SIZE = 32

        const val MSG_TYPE_AUDIO = 1
        const val MSG_TYPE_IMAGE = 2
        const val MSG_TYPE_IMU = 3
        const val MSG_TYPE_CONTROL = 4

        // Flag bits
        const val FLAG_ACK_REQ = 0x0001
        const val FLAG_CRC = 0x0002
        const val FLAG_HMAC = 0x0004

        fun parse(buf: ByteArray): PacketHeader {
            require(buf.size >= HEADER_SIZE) { "Buffer too small for header: ${buf.size}" }

            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

            val magic = ByteArray(4)
            bb.get(magic)
            require(String(magic, Charsets.US_ASCII) == MAGIC) {
                "Invalid magic: ${String(magic, Charsets.US_ASCII)}"
            }

            val version = bb.get().toInt() and 0xFF
            val msgType = bb.get().toInt() and 0xFF
            val flags = bb.short.toInt() and 0xFFFF
            val deviceId = bb.long.toULong()
            val streamId = bb.short.toInt() and 0xFFFF
            val seq = bb.int.toLong() and 0xFFFF_FFFFL
            val tsUs = bb.long
            val payloadLen = bb.short.toInt() and 0xFFFF

            return PacketHeader(version, msgType, flags, deviceId, streamId, seq, tsUs, payloadLen)
        }
    }

    fun hasCrc(): Boolean = (flags and FLAG_CRC) != 0
    fun hasHmac(): Boolean = (flags and FLAG_HMAC) != 0
}
