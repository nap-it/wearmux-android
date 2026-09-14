package com.example.peciwearables.integration.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Escreve um buffer PCM linear num ficheiro WAV (PCM=1, little-endian). */
object WavWriter {

    fun shortsToLeBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var j = 0
        for (s in samples) {
            out[j++] = (s.toInt() and 0xFF).toByte()
            out[j++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun write(
        file: File,
        pcmBytes: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ) {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val dataSize = pcmBytes.size
        val chunkSize = 36 + dataSize
        FileOutputStream(file).use { fos ->
            fos.write("RIFF".toByteArray(Charsets.US_ASCII))
            fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkSize).array())
            fos.write("WAVE".toByteArray(Charsets.US_ASCII))
            fos.write("fmt ".toByteArray(Charsets.US_ASCII))
            fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array())
            fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1).array())
            fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(channels.toShort()).array())
            fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate).array())
            fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(byteRate).array())
            fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(blockAlign.toShort()).array())
            fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(bitsPerSample.toShort()).array())
            fos.write("data".toByteArray(Charsets.US_ASCII))
            fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dataSize).array())
            fos.write(pcmBytes)
        }
    }
}
