package com.example.peciwearables.integration.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.core.app.NotificationCompat
import com.example.peciwearables.R
import kotlin.math.PI
import kotlin.math.sin


object NotificationSounder {

    private const val CHANNEL_ID = "peci_signal"
    private const val CHANNEL_NAME = "Sinais PECI"
    private const val SAMPLE_RATE = 44100
    private const val NOTIFICATION_BASE_ID = 1500

    private var notifSeq = 0

    fun play(
        context: Context,
        title: String = "Sinal PECI",
        body: String = "Tom de teste",
        freqHz: Int = 880,
        durationMs: Int = 350,
        showNotification: Boolean = true,
    ) {
        ensureChannel(context)

        // 1. Toca o tom directamente como NOTIFICATION usage para sair
        //    pelo canal de volume "Notifications" (e pelos fones BT se
        //    estiverem ligados). Daemon thread → não bloqueia process exit.
        Thread { playToneNotificationUsage(freqHz, durationMs) }
            .apply { isDaemon = true }
            .start()

        // 2. (Opcional) dispara uma notificação visual.
        if (showNotification) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(false)
                    .build()
                nm.notify(NOTIFICATION_BASE_ID + (notifSeq++ and 0x7F), notif)
            } catch (_: Exception) {
                // Permissão POST_NOTIFICATIONS pode estar negada — ignorar.
            }
        }
    }

    private fun playToneNotificationUsage(freqHz: Int, durationMs: Int) {
        val freq = freqHz.coerceIn(40, 16000)
        val dur = durationMs.coerceIn(50, 5000)
        val sampleCount = SAMPLE_RATE * dur / 1000
        val ramp = (SAMPLE_RATE * 5 / 1000).coerceAtLeast(1)

        val samples = ShortArray(sampleCount)
        for (i in samples.indices) {
            val env = when {
                i < ramp -> i.toFloat() / ramp
                i > sampleCount - ramp -> (sampleCount - i).toFloat() / ramp
                else -> 1f
            }
            samples[i] = (sin(2.0 * PI * freq * i / SAMPLE_RATE) * env * 0.85f * Short.MAX_VALUE).toInt().toShort()
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
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
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
            try { Thread.sleep(dur.toLong() + 50) } catch (_: InterruptedException) {}
        } catch (_: Exception) {
            /* fail-silent */
        } finally {
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
        }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        description = "Sons e sinalizações do PECI Wearables"
                        // Som default — o áudio explícito sai por AudioTrack
                        // com USAGE_NOTIFICATION (controlamos o que toca).
                    }
            )
        }
    }
}
