package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PedestrianSafetyEvaluatorTest {

    private val zone = CrossingZone(
        id = "z1",
        name = "Cruzamento Norte",
        latitude = 40.6325,
        longitude = -8.6592,
        radiusMeters = 12f,
    )

    private fun gpsAt(lat: Double, lon: Double): PhoneGpsLocation =
        PhoneGpsLocation(lat, lon, 0.0, 5f, 1.4f, 0f, System.currentTimeMillis())

    @Test
    fun `sem zonas mapeadas devolve Safe sempre`() {
        val ev = PedestrianSafetyEvaluator()
        val d = ev.evaluate(
            zones = emptyList(),
            gps = gpsAt(40.6325, -8.6592),
            moving = true, headRotated = false, decelerating = false,
        )
        assertEquals(PedestrianSafetyDecision.Safe, d)
    }

    @Test
    fun `fora da zona devolve Safe`() {
        val ev = PedestrianSafetyEvaluator()
        val d = ev.evaluate(
            zones = listOf(zone),
            gps = gpsAt(40.7, -8.6),  // muito longe
            moving = true, headRotated = false, decelerating = false,
        )
        assertEquals(PedestrianSafetyDecision.Safe, d)
    }

    @Test
    fun `pessoa parada em zona devolve Watching nao Danger`() {
        val ev = PedestrianSafetyEvaluator()
        val d = ev.evaluate(
            zones = listOf(zone),
            gps = gpsAt(zone.latitude, zone.longitude),
            moving = false, headRotated = false, decelerating = false,
        )
        assertTrue(d is PedestrianSafetyDecision.Watching)
    }

    @Test
    fun `andou em zona sem olhar sem abrandar dispara Danger`() {
        val ev = PedestrianSafetyEvaluator()
        val d = ev.evaluate(
            zones = listOf(zone),
            gps = gpsAt(zone.latitude, zone.longitude),
            moving = true, headRotated = false, decelerating = false,
        )
        assertTrue("DANGER esperado", d is PedestrianSafetyDecision.Danger)
        assertEquals(zone.name, (d as PedestrianSafetyDecision.Danger).zoneName)
    }

    @Test
    fun `olhou bloqueia Danger mesmo com tudo o resto vermelho`() {
        val ev = PedestrianSafetyEvaluator()
        val d = ev.evaluate(
            zones = listOf(zone),
            gps = gpsAt(zone.latitude, zone.longitude),
            moving = true, headRotated = true, decelerating = false,
        )
        assertTrue(d is PedestrianSafetyDecision.Watching)
    }

    @Test
    fun `desaceleracao bloqueia Danger`() {
        val ev = PedestrianSafetyEvaluator()
        val d = ev.evaluate(
            zones = listOf(zone),
            gps = gpsAt(zone.latitude, zone.longitude),
            moving = true, headRotated = false, decelerating = true,
        )
        assertTrue(d is PedestrianSafetyDecision.Watching)
    }

    @Test
    fun `cooldown evita disparos consecutivos`() {
        val ev = PedestrianSafetyEvaluator(dangerCooldownMs = 60_000L)
        val now = 1_000L
        val d1 = ev.evaluate(
            zones = listOf(zone),
            gps = gpsAt(zone.latitude, zone.longitude),
            moving = true, headRotated = false, decelerating = false,
            nowMs = now,
        )
        val d2 = ev.evaluate(
            zones = listOf(zone),
            gps = gpsAt(zone.latitude, zone.longitude),
            moving = true, headRotated = false, decelerating = false,
            nowMs = now + 100,
        )
        assertTrue(d1 is PedestrianSafetyDecision.Danger)
        assertTrue("Segundo dentro do cooldown deve ser Watching", d2 is PedestrianSafetyDecision.Watching)

        val d3 = ev.evaluate(
            zones = listOf(zone),
            gps = gpsAt(zone.latitude, zone.longitude),
            moving = true, headRotated = false, decelerating = false,
            nowMs = now + 70_000,
        )
        assertTrue("Fora do cooldown deve voltar a disparar", d3 is PedestrianSafetyDecision.Danger)
    }
}
