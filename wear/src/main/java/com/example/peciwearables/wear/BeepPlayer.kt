package com.example.peciwearables.wear

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin


object BeepPlayer {

    private const val TAG = "BeepPlayer"
    private const val SAMPLE_RATE = 44100


    fun playLocal(
        @Suppress("UNUSED_PARAMETER") context: Context,
        freqHz: Int,
        durationMs: Int,
        volume: Float = 1f,
    ) {
        val freq = freqHz.coerceIn(40, 8000)
        val dur = durationMs.coerceIn(10, 5000)
        val vol = volume.coerceIn(0f, 1f)

        val sampleCount = SAMPLE_RATE * dur / 1000
        val samples = ShortArray(sampleCount)
        // Envelope linear de 5 ms para evitar clicks no início e no fim.
        val ramp = (SAMPLE_RATE * 5 / 1000).coerceAtLeast(1)
        for (i in samples.indices) {
            val env = when {
                i < ramp -> i.toFloat() / ramp
                i > sampleCount - ramp -> (sampleCount - i).toFloat() / ramp
                else -> 1f
            }
            val s = sin(2.0 * PI * freq * i / SAMPLE_RATE) * vol * env * Short.MAX_VALUE
            samples[i] = s.toInt().toShort()
        }

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(samples.size * 2)

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            val owned = track
            track = null  // ownership passa para a thread
            Thread {
                try {
                    try { Thread.sleep(dur.toLong() + 50) } catch (_: InterruptedException) {}
                } finally {
                    try { owned.stop() } catch (_: Exception) {}
                    try { owned.release() } catch (_: Exception) {}
                }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.w(TAG, "playLocal falhou: ${e.message}")
            try { track?.release() } catch (_: Exception) {}
        }
    }
}
