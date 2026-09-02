package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingZoneTest {

    private val centro = CrossingZone("c", "Centro", 40.6325, -8.6592, 15f)

    private fun gps(lat: Double, lon: Double) =
        PhoneGpsLocation(lat, lon, 0.0, 5f, 0f, 0f, 0L)

    @Test
    fun `contains true no centro`() {
        assertTrue(centro.contains(gps(40.6325, -8.6592)))
    }

    @Test
    fun `contains false a 100m`() {
        assertTrue(!centro.contains(gps(40.6335, -8.6592)))
    }

    @Test
    fun `haversine para zero metros é zero`() {
        val d = haversineMeters(40.6325, -8.6592, 40.6325, -8.6592)
        assertEquals(0.0, d, 0.01)
    }

    @Test
    fun `haversine 1 grau de lat dá aprox 111km`() {
        val d = haversineMeters(40.0, 0.0, 41.0, 0.0)
        assertTrue("≈111000m", d in 110_000.0..112_000.0)
    }

    @Test
    fun `zoneContaining devolve a zona ou null`() {
        val tracker = CrossingZoneTracker()
        val zones = listOf(centro)
        assertNotNull(tracker.zoneContaining(zones, gps(centro.latitude, centro.longitude)))
        assertNull(tracker.zoneContaining(zones, gps(40.7, -8.5)))
    }

    @Test
    fun `nearestZone devolve a mais próxima com distância`() {
        val z2 = CrossingZone("z2", "Norte", 40.6500, -8.6592, 15f)
        val tracker = CrossingZoneTracker()
        val gpsHere = gps(40.6326, -8.6592)
        val (near, distM) = tracker.nearestZone(listOf(centro, z2), gpsHere)!!
        assertEquals(centro.id, near.id)
        assertTrue("distancia muito perto", distM < 50.0)
    }

    @Test
    fun `zoneWithinSlack devolve quando dentro do slack`() {
        val tracker = CrossingZoneTracker()
        // 10m a norte do centro — dentro do raio 15m
        val near = tracker.zoneWithinSlack(listOf(centro), gps(40.63259, -8.6592), slackMeters = 0f)
        assertNotNull(near)
        // Muito longe — fora mesmo com slack
        val far = tracker.zoneWithinSlack(listOf(centro), gps(40.7, -8.5), slackMeters = 0f)
        assertNull(far)
    }
}
