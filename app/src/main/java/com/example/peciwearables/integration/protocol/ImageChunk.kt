package com.example.peciwearables.integration.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder


data class ImageChunk(
    val imgFormat: Int,
    val width: Int,
    val height: Int,
    val frameId: Long,
    val chunkIdx: Int,
    val chunkCount: Int,
    val totalBytes: Int,
    val chunkBytes: ByteArray
) {
    companion object {
        const val FORMAT_JPEG = 1
        const val FORMAT_RAW = 2
        private const val SUB_HEADER_SIZE = 17

        fun parse(payload: ByteArray): ImageChunk {
            require(payload.size >= SUB_HEADER_SIZE) { "Image payload too small" }

            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

            val imgFormat = bb.get().toInt() and 0xFF
            val width = bb.short.toInt() and 0xFFFF
            val height = bb.short.toInt() and 0xFFFF
            val frameId = bb.int.toLong() and 0xFFFF_FFFFL
            val chunkIdx = bb.short.toInt() and 0xFFFF
            val chunkCount = bb.short.toInt() and 0xFFFF
            val totalBytes = bb.int

            val chunkBytes = ByteArray(payload.size - SUB_HEADER_SIZE)
            bb.get(chunkBytes)

            return ImageChunk(imgFormat, width, height, frameId, chunkIdx, chunkCount, totalBytes, chunkBytes)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageChunk) return false
        return imgFormat == other.imgFormat && frameId == other.frameId &&
                chunkIdx == other.chunkIdx && chunkBytes.contentEquals(other.chunkBytes)
    }

    override fun hashCode(): Int {
        var result = imgFormat
        result = 31 * result + frameId.hashCode()
        result = 31 * result + chunkIdx
        result = 31 * result + chunkBytes.contentHashCode()
        return result
    }
}
