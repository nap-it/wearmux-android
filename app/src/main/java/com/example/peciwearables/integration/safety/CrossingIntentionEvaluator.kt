package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.sensors.PhoneGpsLocation


class CrossingIntentionEvaluator(
    private val cooldownMs: Long = 8_000L,
    private val zoneTracker: CrossingZoneTracker = CrossingZoneTracker(),
) {
    sealed interface Decision {
        data object Idle : Decision
        data class IntentDetected(val zoneName: String) : Decision
    }

    private var lastIntentMs: Long = 0L

    fun evaluate(
        zones: List<CrossingZone>,
        gps: PhoneGpsLocation?,
        moving: Boolean,
        lookedBothWays: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Decision {
        if (gps == null || zones.isEmpty()) return Decision.Idle
        val zone = zoneTracker.zoneWithinSlack(zones, gps, slackMeters = 8f)
            ?: return Decision.Idle
        if (moving) return Decision.Idle
        if (!lookedBothWays) return Decision.Idle
        if (nowMs - lastIntentMs < cooldownMs) return Decision.Idle
        lastIntentMs = nowMs
        return Decision.IntentDetected(zone.name)
    }

    fun resetCooldown() { lastIntentMs = 0L }
}
