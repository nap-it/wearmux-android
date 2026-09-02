package com.example.peciwearables.integration.audio

import android.util.Log
import com.example.peciwearables.integration.RecordedAudio
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class PcmAudioRecorder(
    private val recordingsDir: File,
    private val onPersistError: (String) -> Unit = {},
) {
    private var buffer = ByteArrayOutputStream()
    private var samples = 0L
    private var sampleRate = DEFAULT_SAMPLE_RATE
    var active: Boolean = false
        private set

    fun start(sampleRateHz: Int = DEFAULT_SAMPLE_RATE) {
        buffer = ByteArrayOutputStream()
        samples = 0L
        sampleRate = if (sampleRateHz > 0) sampleRateHz else DEFAULT_SAMPLE_RATE
        active = true
    }

    fun appendShorts(pcm: ShortArray) {
        if (!active) return
        buffer.write(WavWriter.shortsToLeBytes(pcm))
        samples += pcm.size
    }

    fun setSampleRate(sampleRateHz: Int) {
        if (sampleRateHz > 0) sampleRate = sampleRateHz
    }

    /** Persiste o buffer em WAV e devolve o descritor; null se não há nada para gravar. */
    fun persist(nextIndex: Int): RecordedAudio? {
        if (!active) return null
        active = false
        val pcmBytes = buffer.toByteArray()
        if (pcmBytes.isEmpty() || samples <= 0L) return null
        recordingsDir.mkdirs()
        val file = File(recordingsDir, "glasses_mic_${System.currentTimeMillis()}.wav")
        try {
            WavWriter.write(file, pcmBytes, sampleRate)
        } catch (e: Exception) {
            onPersistError("Erro a gravar WAV: ${e.message}")
            Log.w(TAG, "persist falhou: ${e.message}")
            return null
        }
        val durationSec = samples.toDouble() / sampleRate.toDouble()
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        buffer = ByteArrayOutputStream(); samples = 0L
        return RecordedAudio(
            filePath = file.absolutePath, timestamp = ts, index = nextIndex,
            durationSec = durationSec, sampleRateHz = sampleRate,
        )
    }

    fun discard() {
        active = false; buffer = ByteArrayOutputStream(); samples = 0L
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 16_000
        const val TAG = "PcmAudioRecorder"
    }
}
