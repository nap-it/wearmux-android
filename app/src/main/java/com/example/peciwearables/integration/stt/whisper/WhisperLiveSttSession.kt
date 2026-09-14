// =============================================================================
// LEGACY — WhisperLive STT Session
// =============================================================================
// Acumula PCM ShortArray e converte para Float32 LE para envio via WebSocket
// ao servidor WhisperLive. Substituído por SherpaKwsSession para KWS.
// Mantido para referência futura. NÃO instanciado atualmente.
// =============================================================================
package com.example.peciwearables.integration.stt.whisper

import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperLiveSttSession(
    private val sendAudio: (ByteArray) -> Unit,
    private val chunkSamples: Int = 4096,
) {
    private val accumulator = ArrayList<Short>(chunkSamples * 2)
    private val lock = Any()

    init {
        require(chunkSamples > 0) { "chunkSamples must be > 0" }
    }

    @Synchronized
    fun feedAudio(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val toSend = mutableListOf<ByteArray>()
        synchronized(lock) {
            pcm.forEach { accumulator.add(it) }
            while (accumulator.size >= chunkSamples) {
                val chunk = ShortArray(chunkSamples) { i -> accumulator[i] }
                accumulator.subList(0, chunkSamples).clear()
                toSend.add(toFloat32Bytes(chunk))
            }
        }
        toSend.forEach { sendAudio(it) }
    }

    @Synchronized
    fun flush() {
        val toSend: ByteArray
        synchronized(lock) {
            if (accumulator.isEmpty()) return
            toSend = toFloat32Bytes(ShortArray(accumulator.size) { i -> accumulator[i] })
            accumulator.clear()
        }
        sendAudio(toSend)
    }

    private fun toFloat32Bytes(pcm: ShortArray): ByteArray {
        val buffer = ByteBuffer
            .allocate(pcm.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { sample ->
            buffer.putFloat(sample / 32768f)
        }
        return buffer.array()
    }
}
