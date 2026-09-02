package com.example.peciwearables.integration.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.sqrt


class AmbientSoundClassifier(
    /** Modelo TFLite opcional (YAMNet). Quando `null`, usa heurística RMS. */
    private val yamnet: YamnetClassifier? = null,
) {

    enum class SoundCategory {
        SIREN,           // ambulância, polícia, bombeiros
        ALARM,           // alarme de incêndio, alarme casa
        DOORBELL,        // campainha
        KNOCK,           // bater à porta
        PHONE_RING,      // toque de telemóvel
        SHOUT,           // grito, voz alta
        LOUD_AMBIENT,    // genérico (heurística RMS) — fallback enquanto YAMNet não está pronto
        SILENCE,         // sem evento
    }

    data class Detection(
        val category: SoundCategory,
        val confidence: Float,
        val rmsDb: Float,
        val timestampMs: Long,
    )

    private val _detections = MutableSharedFlow<Detection>(replay = 0, extraBufferCapacity = 16)
    val detections: SharedFlow<Detection> = _detections.asSharedFlow()

    @Volatile private var lastEmitMs: Long = 0L
    private val cooldownMs = 2_500L

    /**
     * Alimenta um buffer PCM (16-bit mono). O caller deve passar janelas de
     * ~ 1 s (16 000 samples a 16 kHz) — buffers menores são acumulados
     * internamente até atingir o tamanho.
     */
    fun feedPcm(samples: ShortArray, sampleRateHz: Int) {
        if (samples.isEmpty()) return
        val now = System.currentTimeMillis()
        val rms = computeRms(samples)
        val rmsDb = 20f * kotlin.math.log10(rms.coerceAtLeast(1f))

        // Cooldown — não dispara em rajada.
        if (now - lastEmitMs < cooldownMs) return

        val (category, confidence) = classify(samples, sampleRateHz, rmsDb)
        if (category == SoundCategory.SILENCE) return

        lastEmitMs = now
        _detections.tryEmit(Detection(category, confidence, rmsDb, now))
    }

    private fun computeRms(samples: ShortArray): Float {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / samples.size).toFloat()
    }

    /**
     * Classifica via YAMNet quando disponível, caso contrário via heurística
     * RMS (som forte → `LOUD_AMBIENT`).
     */
    private fun classify(samples: ShortArray, sampleRate: Int, rmsDb: Float): Pair<SoundCategory, Float> {
        // Caminho preferido: YAMNet TFLite. Tem que receber buffer com pelo
        // menos ~0.5 s — abaixo disso a inferência é pouco fiável.
        yamnet?.let { y ->
            if (samples.size >= sampleRate / 2) {
                y.categorize(samples, sampleRate)?.let { cat -> return cat to 0.85f }
            }
        }
        // Fallback: heurística RMS — qualquer som forte vira LOUD_AMBIENT.
        val LOUD_DB = 70f
        return if (rmsDb >= LOUD_DB) {
            SoundCategory.LOUD_AMBIENT to ((rmsDb - LOUD_DB) / 30f).coerceIn(0f, 1f)
        } else {
            SoundCategory.SILENCE to 0f
        }
    }

    companion object {
        private const val TAG = "AmbientSoundClassifier"
    }
}
