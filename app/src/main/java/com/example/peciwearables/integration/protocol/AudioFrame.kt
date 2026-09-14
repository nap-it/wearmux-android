package com.example.peciwearables.integration.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Payload de áudio (msgType = 1).
 *
 * Layout:
 *  codec           1B  1=Opus, 2=µ-law
 *  frameDurMs      1B  20, 40, 60…
 *  sampleRateHz    2B  16000
 *  channels        1B  1
 *  reserved        1B  alinhamento
 *  audioBytes      NB  bytes do frame codificado
 */
data class AudioFrame(
    val codec: Int,
    val frameDurMs: Int,
    val sampleRateHz: Int,
    val channels: Int,
    val audioBytes: ByteArray
) {
    companion object {
        const val CODEC_OPUS = 1
        const val CODEC_MULAW = 2
        const val CODEC_PCM16 = 3
        private const val SUB_HEADER_SIZE = 6

        fun parse(payload: ByteArray): AudioFrame {
            require(payload.size >= SUB_HEADER_SIZE) { "Audio payload too small" }

            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

            val codec = bb.get().toInt() and 0xFF
            val frameDurMs = bb.get().toInt() and 0xFF
            val sampleRateHz = bb.short.toInt() and 0xFFFF
            val channels = bb.get().toInt() and 0xFF
            bb.get() // reserved

            val audioBytes = ByteArray(payload.size - SUB_HEADER_SIZE)
            bb.get(audioBytes)

            return AudioFrame(codec, frameDurMs, sampleRateHz, channels, audioBytes)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return codec == other.codec && frameDurMs == other.frameDurMs &&
                sampleRateHz == other.sampleRateHz && channels == other.channels &&
                audioBytes.contentEquals(other.audioBytes)
    }

    override fun hashCode(): Int {
        var result = codec
        result = 31 * result + frameDurMs
        result = 31 * result + sampleRateHz
        result = 31 * result + channels
        result = 31 * result + audioBytes.contentHashCode()
        return result
    }
}
