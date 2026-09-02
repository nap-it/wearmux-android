package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.sensors.PhoneGpsLocation

/** UC1.1 legacy local — sobrevive só para os testes; produção avalia na cloud. */
@Deprecated("UC1.1 é avaliado na cloud (fusion_engine). Mantém-se só para testes legacy.")
class PedestrianSafetyEvaluator(
    
    private val approachMeters: Float = 12f,
    private val approachCooldownMs: Long = 20_000L,
    dangerCooldownMs: Long = 60_000L,
    private val zoneTracker: CrossingZoneTracker = CrossingZoneTracker(),
    /** Convenience alias for `dangerCooldownMs`; overrides it when provided. */
    cooldownMs: Long? = null,
) {
    private val dangerCooldownMs: Long = cooldownMs ?: dangerCooldownMs
    private var lastApproachMs: Long = Long.MIN_VALUE / 2
    private var lastDangerMs: Long = Long.MIN_VALUE / 2

   
    fun evaluate(
        zones: List<CrossingZone>,
        gps: PhoneGpsLocation?,
        moving: Boolean,
        headRotated: Boolean,
        decelerating: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): PedestrianSafetyDecision {
        if (gps == null || zones.isEmpty()) return PedestrianSafetyDecision.Safe

        // 1) Já dentro da zona (com 5 m de slack)?
        val zoneIn = zoneTracker.zoneWithinSlack(zones, gps, slackMeters = 5f)
        if (zoneIn != null) {
            if (!moving) return PedestrianSafetyDecision.Watching("parado em ${zoneIn.name}")
            if (headRotated) return PedestrianSafetyDecision.Watching("rodou a cabeça")
            if (decelerating) return PedestrianSafetyDecision.Watching("a abrandar")

            if (nowMs - lastDangerMs < dangerCooldownMs) {
                return PedestrianSafetyDecision.Watching("alerta recente — em cooldown")
            }
            lastDangerMs = nowMs
            return PedestrianSafetyDecision.Danger(
                zoneName = zoneIn.name,
                moving = true,
                headRotated = false,
                decelerating = false,
            )
        }

        // 2) Ainda fora da zona — verificar aproximação.
        val nearest = zoneTracker.nearestZone(zones, gps) ?: return PedestrianSafetyDecision.Safe
        val (zone, distMeters) = nearest
        // Distância "até à borda" (não até ao centro).
        val edgeDist = (distMeters - zone.radiusMeters).coerceAtLeast(0.0)
        if (edgeDist <= approachMeters && moving) {
            if (nowMs - lastApproachMs < approachCooldownMs) {
                return PedestrianSafetyDecision.Safe
            }
            lastApproachMs = nowMs
            return PedestrianSafetyDecision.Approaching(
                zoneName = zone.name,
                distanceMeters = edgeDist,
            )
        }
        return PedestrianSafetyDecision.Safe
    }

    fun resetCooldown() {
        lastApproachMs = Long.MIN_VALUE / 2
        lastDangerMs = Long.MIN_VALUE / 2
    }
}
