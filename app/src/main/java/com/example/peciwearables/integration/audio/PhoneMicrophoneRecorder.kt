package com.example.peciwearables.integration.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean


class PhoneMicrophoneRecorder(
    private val context: Context,
    private val onPcm: (ShortArray) -> Unit,
) {
    companion object {
        private const val TAG = "PhoneMicRecorder"
        private const val SAMPLE_RATE = 16_000
        private val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_MS = 100
        private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000
    }

    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null

    fun isRunning(): Boolean = running.get()

    /** Devolve true se conseguiu arrancar (permissão + hw OK). */
    fun start(): Boolean {
        if (running.get()) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO não concedido — sem áudio do telemóvel para Whisper")
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize falhou: $minBuf")
            return false
        }
        val bufSize = (minBuf * 2).coerceAtLeast(CHUNK_SAMPLES * 4)
        val ar = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufSize,
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord ctor: $e"); return false
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord não inicializou (state=${ar.state})")
            runCatching { ar.release() }
            return false
        }
        recorder = ar
        running.set(true)
        ar.startRecording()
        thread(name = "PhoneMicRecorder", isDaemon = true) { runLoop(ar) }
        Log.i(TAG, "PhoneMic ON (16 kHz mono PCM16)")
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        Log.i(TAG, "PhoneMic OFF")
    }

    private fun runLoop(ar: AudioRecord) {
        val buf = ShortArray(CHUNK_SAMPLES)
        try {
            while (running.get()) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (!running.get()) break
                    continue
                }
                val out = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                try { onPcm(out) } catch (t: Throwable) { Log.w(TAG, "onPcm: ${t.message}") }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "loop falhou: ${t.message}")
        }
    }
}
