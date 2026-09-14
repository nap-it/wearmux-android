package com.example.peciwearables.integration.safety

import com.example.peciwearables.Detection
import com.example.peciwearables.integration.sensors.PhoneGpsLocation

/** UC1.3 legacy local — sobrevive só para os testes; produção valida na cloud. */
@Deprecated("UC1.3 is validated in the cloud (fusion_engine). Kept only for legacy tests.")
class CrossingValidator(
    private val minConfidence: Float = 0.45f,
    /** GPS mais antigo que isto é considerado stale → "wait". */
    private val maxGpsAgeMs: Long = 5_000L,
    private val zoneTracker: CrossingZoneTracker = CrossingZoneTracker(),
) {

    fun validate(
        zones: List<CrossingZone>,
        gps: PhoneGpsLocation?,
        latestDetections: List<Detection>,
        lookedBothWays: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): CrossingValidationDecision {
        if (gps == null) return CrossingValidationDecision.Wait("no GPS")
        val ageMs = nowMs - gps.timestampMs
        if (ageMs > maxGpsAgeMs) {
            return CrossingValidationDecision.Wait("stale GPS (${ageMs / 1000}s)")
        }
        val zone = zoneTracker.zoneContaining(zones, gps)
            ?: return CrossingValidationDecision.Wait("you are not on a crosswalk")

        val vehicle = latestDetections.firstOrNull {
            it.confidence >= minConfidence && it.label.lowercase() in VEHICLE_LABELS
        }
        if (vehicle != null) {
            return CrossingValidationDecision.Wait(
                "${vehicle.label} detected (${"%.0f".format(vehicle.confidence * 100)}%)"
            )
        }

        // **Estrito**: exige rotação confirmada para AMBOS os lados em
        // direcções opostas (vê HeadRotationDetector.lookedBothWays).
        if (!lookedBothWays) {
            return CrossingValidationDecision.Wait("look both ways first")
        }

        return CrossingValidationDecision.Ok(zone.name)
    }

    companion object {
        private val VEHICLE_LABELS = setOf(
            "car", "truck", "bus", "motorcycle", "bicycle",
            // YOLO COCO 80 também pode usar abreviaturas
            "motorbike",
        )
    }
}
