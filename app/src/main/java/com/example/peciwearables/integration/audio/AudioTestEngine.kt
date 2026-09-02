package com.example.peciwearables.integration.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin


object AudioTestEngine {

    private const val TAG = "AudioTestEngine"
    private const val SAMPLE_RATE = 44100

    @Volatile private var currentTrack: AudioTrack? = null

    enum class TonePreset(val freqHz: Int, val durationMs: Int, val label: String) {
        BEEP_LOW(440, 250, "440 Hz curto"),
        BEEP_MID(880, 250, "880 Hz curto"),
        BEEP_HIGH(2000, 200, "2 kHz curto"),
        TONE_LONG(1000, 1500, "1 kHz contínuo"),
        SWEEP(0, 1500, "Sweep 200→4000 Hz"),
    }

    fun playPreset(preset: TonePreset, volume: Float = 0.8f) {
        if (preset == TonePreset.SWEEP) {
            playSweep(200, 4000, preset.durationMs, volume)
        } else {
            playTone(preset.freqHz, preset.durationMs, volume)
        }
    }

    fun playTone(freqHz: Int, durationMs: Int, volume: Float = 0.8f) {
        stop()
        val freq = freqHz.coerceIn(40, 16000)
        val dur = durationMs.coerceIn(10, 10000)
        val vol = volume.coerceIn(0f, 1f)
        val sampleCount = SAMPLE_RATE * dur / 1000
        val ramp = (SAMPLE_RATE * 5 / 1000).coerceAtLeast(1)

        val samples = ShortArray(sampleCount)
        for (i in samples.indices) {
            val env = when {
                i < ramp -> i.toFloat() / ramp
                i > sampleCount - ramp -> (sampleCount - i).toFloat() / ramp
                else -> 1f
            }
            samples[i] = (sin(2.0 * PI * freq * i / SAMPLE_RATE) * vol * env * Short.MAX_VALUE).toInt().toShort()
        }
        playSamples(samples)
    }

    fun playSweep(fromHz: Int, toHz: Int, durationMs: Int, volume: Float = 0.8f) {
        stop()
        val dur = durationMs.coerceIn(50, 10000)
        val vol = volume.coerceIn(0f, 1f)
        val sampleCount = SAMPLE_RATE * dur / 1000
        val ramp = (SAMPLE_RATE * 10 / 1000).coerceAtLeast(1)
        // Sweep linear em frequência (suficiente para diagnóstico).
        val samples = ShortArray(sampleCount)
        var phase = 0.0
        for (i in samples.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val freq = fromHz + (toHz - fromHz) * (i.toDouble() / sampleCount)
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val env = when {
                i < ramp -> i.toFloat() / ramp
                i > sampleCount - ramp -> (sampleCount - i).toFloat() / ramp
                else -> 1f
            }
            samples[i] = (sin(phase) * vol * env * Short.MAX_VALUE).toInt().toShort()
        }
        playSamples(samples)
    }

    fun stop() {
        try {
            currentTrack?.let {
                it.pause()
                it.flush()
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop falhou: ${e.message}")
        }
        currentTrack = null
    }

    private fun playSamples(samples: ShortArray) {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(samples.size * 2)
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
            currentTrack = track
        } catch (e: Exception) {
            Log.w(TAG, "playSamples falhou: ${e.message}")
        }
    }

    /** Lista os destinos de áudio activos — útil para mostrar ao utilizador
     *  qual é o sink corrente (speaker / auscultadores BT / etc). */
    fun listOutputDevices(context: Context): List<String> {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .map { describeDevice(it) }
        } catch (e: Exception) {
            Log.w(TAG, "listOutputDevices falhou: ${e.message}")
            emptyList()
        }
    }

    private fun describeDevice(d: AudioDeviceInfo): String {
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Auscultadores cabo"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset cabo"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Aparelho auditivo"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Auricular"
            else -> "Tipo ${d.type}"
        }
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: ""
        return if (name.isNotBlank()) "$type — $name" else type
    }
}
