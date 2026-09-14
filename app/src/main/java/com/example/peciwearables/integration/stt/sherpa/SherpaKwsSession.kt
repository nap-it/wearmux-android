package com.example.peciwearables.integration.stt.sherpa

import java.nio.ByteBuffer
import java.nio.ByteOrder


class SherpaKwsSession(
    private val sendAudio: (ByteArray) -> Unit,
    private val chunkSamples: Int = 4096,
) {
    private val accumulator = ArrayList<Short>(chunkSamples * 2)
    private val lock = Any()

    /** Timestamp (ms) em que o primeiro chunk foi enviado para o servidor KWS.
     *  Zero enquanto nenhum chunk foi enviado na utterance actual. */
    @Volatile var firstChunkSentMs: Long = 0L
        private set

    init {
        require(chunkSamples > 0) { "chunkSamples must be > 0" }
    }

    fun feedAudio(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val toSend = mutableListOf<ByteArray>()
        synchronized(lock) {
            pcm.forEach { accumulator.add(it) }
            while (accumulator.size >= chunkSamples) {
                val chunk = ShortArray(chunkSamples) { i -> accumulator[i] }
                accumulator.subList(0, chunkSamples).clear()
                toSend.add(toFloat32Le(chunk))
            }
        }
        toSend.forEach { bytes ->
            if (firstChunkSentMs == 0L) {
                firstChunkSentMs = System.currentTimeMillis()
            }
            sendAudio(bytes)
        }
    }

    /** Repõe o timer entre utterances — chamar após cada keyword detectada. */
    fun resetChunkTimer() { firstChunkSentMs = 0L }

    fun flush() {
        val toSend: ByteArray
        synchronized(lock) {
            if (accumulator.isEmpty()) return
            toSend = toFloat32Le(ShortArray(accumulator.size) { i -> accumulator[i] })
            accumulator.clear()
        }
        sendAudio(toSend)
    }

    private fun toFloat32Le(pcm: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { buf.putFloat(it / 32768f) }
        return buf.array()
    }
}
