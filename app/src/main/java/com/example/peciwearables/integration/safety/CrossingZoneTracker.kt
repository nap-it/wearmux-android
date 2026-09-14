package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.sensors.PhoneGpsLocation


class CrossingZoneTracker {

    /** Zona em que o GPS está actualmente dentro, ou `null`. */
    fun zoneContaining(zones: List<CrossingZone>, gps: PhoneGpsLocation): CrossingZone? =
        zones.firstOrNull { it.contains(gps) }

    /**
     * Zona mais próxima e respectiva distância em metros, ou `null` se a
     * lista estiver vazia.
     */
    fun nearestZone(
        zones: List<CrossingZone>,
        gps: PhoneGpsLocation,
    ): Pair<CrossingZone, Double>? {
        if (zones.isEmpty()) return null
        return zones
            .map { it to it.distanceFrom(gps) }
            .minByOrNull { it.second }
    }

    /**
     * Verifica se há uma zona dentro de `slackMeters` metros — útil para
     * "está a aproximar-se da via" sem ainda ter pisado a passadeira.
     */
    fun zoneWithinSlack(
        zones: List<CrossingZone>,
        gps: PhoneGpsLocation,
        slackMeters: Float = 5f,
    ): CrossingZone? = zones.firstOrNull {
        it.distanceFrom(gps) <= it.radiusMeters + slackMeters
    }
}
