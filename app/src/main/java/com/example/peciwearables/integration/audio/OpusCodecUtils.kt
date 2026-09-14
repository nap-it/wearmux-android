package com.example.peciwearables.integration.audio

/** Helpers para inspeccionar pacotes Opus a 48 kHz sem descodificar. */
object OpusCodecUtils {

    /** Devolve o nº de samples a 48 kHz contidos num pacote Opus completo. */
    fun estimateSamples48k(packet: ByteArray): Int {
        if (packet.isEmpty()) return 960
        val toc = packet[0].toInt() and 0xFF
        val config = (toc ushr 3) and 0x1F
        val code = toc and 0x03

        val samplesPerFrame = when (config) {
            in 0..11 -> when (config and 0x03) {
                0 -> 480
                1 -> 960
                2 -> 1920
                else -> 2880
            }
            in 12..15 -> if ((config and 0x01) == 0) 480 else 960
            in 16..31 -> when (config and 0x03) {
                0 -> 120
                1 -> 240
                2 -> 480
                else -> 960
            }
            else -> 960
        }
        val frameCount = when (code) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size < 2) 1 else ((packet[1].toInt() and 0xFF) ushr 2).coerceAtLeast(1)
        }
        return (samplesPerFrame * frameCount).coerceIn(120, 5760)
    }
}
