package com.example.peciwearables.integration.managers

import android.util.Log
import com.example.peciwearables.integration.audio.AudioPipeline
import com.example.peciwearables.integration.protocol.AudioFrame

class GlassesMicrophoneManager(
    private val audioPipeline: AudioPipeline,
    private val onLog: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "GlassesMicrophoneManager"
        private const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        private const val DEFAULT_BIT_DEPTH = 8
    }

    private var sampleRateHz = DEFAULT_SAMPLE_RATE_HZ
    private var bitDepth = DEFAULT_BIT_DEPTH
    private var codecId = AudioFrame.CODEC_MULAW
    private var codecKnown = false

    val currentSampleRateHz: Int get() = sampleRateHz
    val currentBitDepth: Int get() = bitDepth
    val currentCodecId: Int get() = codecId
    val isCodecKnown: Boolean get() = codecKnown

    fun updateConfiguration(sampleRateHz: Int, bitDepth: Int) {
        val safeSampleRate = if (sampleRateHz == 8_000) 8_000 else DEFAULT_SAMPLE_RATE_HZ
        val safeBitDepth = if (bitDepth == 16) 16 else DEFAULT_BIT_DEPTH
        this.sampleRateHz = safeSampleRate
        this.bitDepth = safeBitDepth
        // Igual ao SDK JS: bitDepth=16 → PCM16, bitDepth=8 → mulaw
        this.codecId = if (safeBitDepth == 16) AudioFrame.CODEC_PCM16 else AudioFrame.CODEC_MULAW
        val msg = "DBG updateConfiguration: ${safeSampleRate}Hz/${safeBitDepth}-bit -> codecId=${this.codecId}"
        Log.d(TAG, msg)
        onLog(msg)
    }

    fun updateCodec(id: Int) {
        this.codecId = when (id) {
            21 -> AudioFrame.CODEC_MULAW
            2 -> AudioFrame.CODEC_MULAW
            3 -> AudioFrame.CODEC_PCM16
            else -> AudioFrame.CODEC_MULAW
        }
        codecKnown = true
        audioPipeline.clearQueue()
        val msg = "DBG updateCodec: id=$id -> codecId=${this.codecId} codecKnown=true"
        Log.d(TAG, msg)
        onLog(msg)
    }

    fun resetCodecGate() {
        codecKnown = false
        audioPipeline.clearQueue()
        Log.d(TAG, "DBG resetCodecGate: codecKnown=false")
    }

    fun canDecodeToPcmForStt(): Boolean =
        codecId == AudioFrame.CODEC_PCM16 || codecId == AudioFrame.CODEC_MULAW

    fun canMonitorLocally(): Boolean =
        codecId == AudioFrame.CODEC_PCM16 || codecId == AudioFrame.CODEC_MULAW

    fun onMicrophoneData(bytes: ByteArray) {
        if (!codecKnown) {
            Log.d(TAG, "DBG drop: codec not yet known, discarding ${bytes.size}B")
            return
        }
        val pcm = decodeToPcm(bytes)
        if (pcm.isEmpty()) return
        Log.d(TAG, "DBG enqueue: ${pcm.size} samples codec=$codecId rate=${sampleRateHz}Hz")
        audioPipeline.enqueueChunk(pcm, sampleRateHz)
    }

    fun decodeToPcm(bytes: ByteArray): ShortArray = when (codecId) {
        AudioFrame.CODEC_PCM16 -> decodePcm16(bytes)
        else -> decodeMulaw(bytes)
    }

    fun onMicrophoneStatus(status: Int) {
        onLog("Microphone status: $status")
    }

    private fun decodeMulaw(encoded: ByteArray): ShortArray {
        val pcm = ShortArray(encoded.size)
        for (i in encoded.indices) {
            var mulaw = (encoded[i].toInt() and 0xFF).inv() and 0xFF
            val sign = mulaw and 0x80
            val exponent = (mulaw shr 4) and 0x07
            val mantissa = mulaw and 0x0F
            var magnitude = ((mantissa shl 1) or 0x21) shl (exponent + 2)
            magnitude -= 0x84
            pcm[i] = if (sign != 0) (-magnitude).toShort() else magnitude.toShort()
        }
        return pcm
    }

    private fun decodePcm16(encoded: ByteArray): ShortArray {
        if (encoded.isEmpty()) return ShortArray(0)
        val sampleCount = encoded.size / 2
        val pcm = ShortArray(sampleCount)
        var i = 0; var j = 0
        while (j + 1 < encoded.size) {
            val lo = encoded[j].toInt() and 0xFF
            val hi = encoded[j + 1].toInt()
            pcm[i++] = ((hi shl 8) or lo).toShort()
            j += 2
        }
        return pcm
    }
}
