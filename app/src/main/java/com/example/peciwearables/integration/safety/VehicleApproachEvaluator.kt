package com.example.peciwearables.integration.safety

import com.example.peciwearables.Detection
import com.example.peciwearables.integration.depth.DepthResult


class VehicleApproachEvaluator(
    private val minConfidence: Float = 0.40f,
    /** Depth `< dangerDepth` = veículo perto (escala 0..1 normalizada). */
    private val dangerDepth: Float = 0.45f,
    /** Variação mínima por segundo de depth para considerar "aproxima-se". */
    private val minClosingRate: Float = 0.04f,
    private val cooldownMs: Long = 4_000L,
) {
    sealed interface Decision {
        data object Clear : Decision
        /** Veículo identificado mas longe / não a aproximar. */
        data class Watching(val label: String, val depth: Float) : Decision

        /** Veículo a aproximar — disparar alerta. */
        data class Alert(
            val label: String,
            val depthNow: Float,
            val closingRatePerSec: Float,
            val lookingAtIt: Boolean,
        ) : Decision
    }

    private data class Track(
        var depth: Float,
        var lastMs: Long,
        val history: ArrayDeque<Pair<Long, Float>> = ArrayDeque(),
        var lastAlertMs: Long = Long.MIN_VALUE / 2,
    )

    private val tracks = mutableMapOf<String, Track>()

    fun evaluate(
        detections: List<Detection>,
        depth: DepthResult?,
        lookingAtDetection: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): Decision {
        if (depth == null) return Decision.Clear
        // Pega no veículo mais perto (depth mais baixo) na lista.
        val vehicle = detections
            .filter { it.confidence >= minConfidence && it.label.lowercase() in VEHICLE_LABELS }
            .minByOrNull {
                val cx = (it.left + it.right) / 2f
                val cy = (it.top + it.bottom) / 2f
                depth.sampleAt(cx, cy)
            }
            ?: run {
                // Sem veículos — limpa tracks velhos (>5s).
                tracks.entries.removeAll { (_, t) -> nowMs - t.lastMs > 5_000 }
                return Decision.Clear
            }

        val cx = (vehicle.left + vehicle.right) / 2f
        val cy = (vehicle.top + vehicle.bottom) / 2f
        val dNow = depth.sampleAt(cx, cy)

        // Key inclui a posição quantizada (10 buckets em cada eixo) — separa
        // dois carros do mesmo tipo a ângulos diferentes, sem inflacionar o
        // número de tracks. Antes era só `vehicle.label`: dois carros
        // partilhavam histórico → closing rate baralhado.
        val gridX = (cx * 10f).toInt().coerceIn(0, 9)
        val gridY = (cy * 10f).toInt().coerceIn(0, 9)
        val trackKey = "${vehicle.label}@${gridX}_$gridY"
        // Limpeza preventiva também aqui — se há sempre veículos no frame,
        // o ramo "Sem veículos" nunca executa e tracks antigas acumulam.
        tracks.entries.removeAll { (_, t) -> nowMs - t.lastMs > 5_000 }

        val track = tracks.getOrPut(trackKey) { Track(depth = dNow, lastMs = nowMs) }
        track.history.addLast(nowMs to dNow)
        while (track.history.isNotEmpty() && nowMs - track.history.first().first > 2_000) {
            track.history.removeFirst()
        }
        track.depth = dNow
        track.lastMs = nowMs

        // Precisamos de pelo menos 3 medições para estimar rate confiável.
        if (track.history.size < 3) return Decision.Watching(vehicle.label, dNow)

        // Closing rate: derivada média (negativa = aproxima-se).
        val first = track.history.first()
        val last = track.history.last()
        val dt = ((last.first - first.first) / 1000f).coerceAtLeast(0.001f)
        val ratePerSec = (last.second - first.second) / dt   // <0 = a aproximar
        val closing = -ratePerSec                            // >0 = a aproximar

        val isApproaching = closing >= minClosingRate
        val isNear = dNow <= dangerDepth

        if (isApproaching && isNear && nowMs - track.lastAlertMs >= cooldownMs) {
            track.lastAlertMs = nowMs
            return Decision.Alert(
                label = vehicle.label,
                depthNow = dNow,
                closingRatePerSec = closing,
                lookingAtIt = lookingAtDetection,
            )
        }
        return Decision.Watching(vehicle.label, dNow)
    }

    fun reset() { tracks.clear() }

    companion object {
        private val VEHICLE_LABELS = setOf(
            "car", "truck", "bus", "motorcycle", "motorbike", "bicycle",
        )
    }
}
