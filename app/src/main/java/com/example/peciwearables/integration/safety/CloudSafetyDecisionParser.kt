package com.example.peciwearables.integration.safety

/** Converte o payload JSON `decisions[]` do unified_server em [CloudSafetyDecision]. */
object CloudSafetyDecisionParser {

    fun parse(raw: Map<String, Any?>): CloudSafetyDecision? {
        val type = raw["type"] as? String ?: return null
        val ts = (raw["hub_timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val confidence = (raw["confidence"] as? Number)?.toFloat() ?: 1f
        val evidence = (raw["evidence"] as? List<*>).orEmpty().mapNotNull { it as? String }

        return when (type) {
            "pedestrian_approaching" -> CloudSafetyDecision.PedestrianApproaching(
                zoneName = raw.zoneName(),
                distanceMeters = evidence.extractEdgeDistance() ?: 5.0,
                timestampMs = ts,
                confidence = confidence,
                evidence = evidence,
            )
            "pedestrian_danger" -> CloudSafetyDecision.PedestrianDanger(
                zoneName = raw.zoneName(),
                timestampMs = ts,
                confidence = confidence,
                evidence = evidence,
            )
            "pedestrian_watching" -> if ("head_rotated" in evidence) {
                CloudSafetyDecision.PedestrianSafeToCross(
                    zoneName = raw.zoneName(),
                    timestampMs = ts,
                    confidence = confidence,
                    evidence = evidence,
                )
            } else null
            "crossing_clear_candidate" -> CloudSafetyDecision.CrossingClear(
                zoneName = raw.zoneName(),
                timestampMs = ts,
                confidence = confidence,
                evidence = evidence,
            )
            "crossing_blocked_vehicle_detected" -> CloudSafetyDecision.CrossingBlocked(
                reason = raw["reason"] as? String ?: "Veículo detetado",
                timestampMs = ts,
                confidence = confidence,
                evidence = evidence,
            )
            "approaching_vehicle_unaware" -> CloudSafetyDecision.VehicleApproaching(
                label = raw["label"] as? String ?: "veículo",
                depthOrDistance = (raw["distance_m"] as? Number)?.toFloat() ?: 0f,
                timestampMs = ts,
                confidence = confidence,
                evidence = evidence,
            )
            else -> null
        }
    }

    fun parseAll(raw: List<Map<String, Any?>>): List<CloudSafetyDecision> =
        raw.mapNotNull { parse(it) }

    private fun Map<String, Any?>.zoneName(): String =
        this["zone_name"] as? String ?: "passadeira"

    private fun List<String>.extractEdgeDistance(): Double? = this
        .firstOrNull { it.startsWith("edge_dist_") }
        ?.removePrefix("edge_dist_")
        ?.removeSuffix("m")
        ?.toDoubleOrNull()
}
