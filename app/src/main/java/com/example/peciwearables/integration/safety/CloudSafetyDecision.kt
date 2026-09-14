package com.example.peciwearables.integration.safety

/** Decisão de safety com origem externa ao dispositivo (cloud ou peer infrastructure). */
sealed interface CloudSafetyDecision {
    val timestampMs: Long
    val confidence: Float
    val evidence: List<String>
    val source: Source

    enum class Source { CLOUD_FUSION, ATCLL, LOCAL_FALLBACK }

    data class PedestrianApproaching(
        val zoneName: String,
        val distanceMeters: Double,
        override val timestampMs: Long,
        override val confidence: Float = 1f,
        override val evidence: List<String> = emptyList(),
        override val source: Source = Source.CLOUD_FUSION,
    ) : CloudSafetyDecision

    data class PedestrianDanger(
        val zoneName: String,
        override val timestampMs: Long,
        override val confidence: Float = 1f,
        override val evidence: List<String> = emptyList(),
        override val source: Source = Source.CLOUD_FUSION,
    ) : CloudSafetyDecision

    /** Mapeia o caso `pedestrian_watching` com evidência `head_rotated`. */
    data class PedestrianSafeToCross(
        val zoneName: String,
        override val timestampMs: Long,
        override val confidence: Float = 1f,
        override val evidence: List<String> = emptyList(),
        override val source: Source = Source.CLOUD_FUSION,
    ) : CloudSafetyDecision

    data class CrossingClear(
        val zoneName: String,
        override val timestampMs: Long,
        override val confidence: Float = 1f,
        override val evidence: List<String> = emptyList(),
        override val source: Source = Source.CLOUD_FUSION,
    ) : CloudSafetyDecision

    data class CrossingBlocked(
        val reason: String,
        override val timestampMs: Long,
        override val confidence: Float = 1f,
        override val evidence: List<String> = emptyList(),
        override val source: Source = Source.CLOUD_FUSION,
    ) : CloudSafetyDecision

    /** Cobre `approaching_vehicle_unaware` (cloud) e ATCLL/UC1.4 (peer). */
    data class VehicleApproaching(
        val label: String,
        val depthOrDistance: Float,
        val side: Side = Side.CENTER,
        val intensityPct: Int = 0,
        override val timestampMs: Long,
        override val confidence: Float = 1f,
        override val evidence: List<String> = emptyList(),
        override val source: Source = Source.CLOUD_FUSION,
    ) : CloudSafetyDecision {
        enum class Side { LEFT, CENTER, RIGHT }
    }
}
