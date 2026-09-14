package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class CrossingZone(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
)

/**
 * Distância entre 2 pontos em metros (haversine, raio terrestre = 6 371 km).
 */
fun haversineMeters(
    lat1: Double, lon1: Double,
    lat2: Double, lon2: Double,
): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2).let { it * it }
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

/** Verifica se o GPS cai dentro da zona (`distância ≤ radius`). */
fun CrossingZone.contains(gps: PhoneGpsLocation): Boolean =
    haversineMeters(latitude, longitude, gps.latitude, gps.longitude) <= radiusMeters

/** Distância em metros do GPS ao centro da zona. */
fun CrossingZone.distanceFrom(gps: PhoneGpsLocation): Double =
    haversineMeters(latitude, longitude, gps.latitude, gps.longitude)
