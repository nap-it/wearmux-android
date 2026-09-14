package com.example.peciwearables.integration.safety

import com.example.peciwearables.Detection
import com.example.peciwearables.integration.depth.DepthResult


class CyclistVehicleAlertEvaluator(
    private val minConfidence: Float = 0.40f,
    /** Depth abaixo do qual começamos a alertar (0..1). */
    private val warnDepth: Float = 0.60f,
    /** Depth muito perto — intensidade máxima. */
    private val criticalDepth: Float = 0.30f,
    private val cooldownMs: Long = 800L,
) {
    sealed interface Decision {
        data object Clear : Decision
        data class IntensityAlert(
            val intensityPct: Int,   // 0..100
            val label: String,
            val depthNow: Float,
            val side: Side,
        ) : Decision
    }

    enum class Side { LEFT, CENTER, RIGHT }

    private var lastAlertMs: Long = Long.MIN_VALUE / 2

    fun evaluate(
        detections: List<Detection>,
        depth: DepthResult?,
        nowMs: Long = System.currentTimeMillis(),
    ): Decision {
        if (depth == null) return Decision.Clear

        val vehicle = detections
            .filter { it.confidence >= minConfidence && it.label.lowercase() in VEHICLE_LABELS }
            .minByOrNull {
                val cx = (it.left + it.right) / 2f
                val cy = (it.top + it.bottom) / 2f
                depth.sampleAt(cx, cy)
            }
            ?: return Decision.Clear

        val cx = (vehicle.left + vehicle.right) / 2f
        val cy = (vehicle.top + vehicle.bottom) / 2f
        val dNow = depth.sampleAt(cx, cy)

        if (dNow > warnDepth) return Decision.Clear
        if (nowMs - lastAlertMs < cooldownMs) return Decision.Clear
        lastAlertMs = nowMs

        // Intensidade linear: warnDepth → 0%, criticalDepth (ou menor) → 100%.
        val span = (warnDepth - criticalDepth).coerceAtLeast(0.01f)
        val raw = ((warnDepth - dNow) / span).coerceIn(0f, 1f)
        val intensity = (raw * 100).toInt()

        val side = when {
            cx < 0.33f -> Side.LEFT
            cx > 0.67f -> Side.RIGHT
            else -> Side.CENTER
        }
        return Decision.IntensityAlert(intensity, vehicle.label, dNow, side)
    }

    fun resetCooldown() { lastAlertMs = Long.MIN_VALUE / 2 }

    companion object {
        private val VEHICLE_LABELS = setOf(
            "car", "truck", "bus", "motorcycle", "motorbike", "bicycle",
        )
    }
}
