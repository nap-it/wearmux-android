package com.example.peciwearables.integration.safety

import com.example.peciwearables.Detection
import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingValidatorTest {

    private val zone = CrossingZone("z", "Passadeira", 40.6325, -8.6592, 12f)
    private val car = Detection("car", 0.78f, 0.1f, 0.1f, 0.5f, 0.5f)

    private fun gpsAt(lat: Double, lon: Double): PhoneGpsLocation =
        PhoneGpsLocation(lat, lon, 0.0, 5f, 0f, 0f, System.currentTimeMillis())

    @Test
    fun `sem GPS responde Wait com motivo`() {
        val r = CrossingValidator().validate(
            zones = listOf(zone), gps = null,
            latestDetections = emptyList(), lookedBothWays = true,
        )
        assertTrue(r is CrossingValidationDecision.Wait)
        assertEquals("sem GPS", (r as CrossingValidationDecision.Wait).reason)
    }

    @Test
    fun `fora de passadeira responde Wait`() {
        val r = CrossingValidator().validate(
            zones = listOf(zone), gps = gpsAt(40.7, -8.6),
            latestDetections = emptyList(), lookedBothWays = true,
        )
        assertTrue(r is CrossingValidationDecision.Wait)
    }

    @Test
    fun `veiculo detectado bloqueia OK`() {
        val r = CrossingValidator().validate(
            zones = listOf(zone), gps = gpsAt(zone.latitude, zone.longitude),
            latestDetections = listOf(car), lookedBothWays = true,
        )
        assertTrue(r is CrossingValidationDecision.Wait)
        assertTrue((r as CrossingValidationDecision.Wait).reason.contains("car"))
    }

    @Test
    fun `confianca baixa de veiculo nao bloqueia`() {
        val lowConfidenceCar = car.copy(confidence = 0.30f)
        val r = CrossingValidator().validate(
            zones = listOf(zone), gps = gpsAt(zone.latitude, zone.longitude),
            latestDetections = listOf(lowConfidenceCar), lookedBothWays = true,
        )
        assertTrue("Threshold default 0.45 — car @ 30% deve ser ignorado",
            r is CrossingValidationDecision.Ok)
    }

    @Test
    fun `nao olhou para os dois lados bloqueia OK`() {
        val r = CrossingValidator().validate(
            zones = listOf(zone), gps = gpsAt(zone.latitude, zone.longitude),
            latestDetections = emptyList(), lookedBothWays = false,
        )
        assertTrue(r is CrossingValidationDecision.Wait)
        assertTrue((r as CrossingValidationDecision.Wait).reason.contains("dois lados"))
    }

    @Test
    fun `tudo cumprido devolve Ok`() {
        val r = CrossingValidator().validate(
            zones = listOf(zone), gps = gpsAt(zone.latitude, zone.longitude),
            latestDetections = emptyList(), lookedBothWays = true,
        )
        assertTrue(r is CrossingValidationDecision.Ok)
        assertEquals("Passadeira", (r as CrossingValidationDecision.Ok).zoneName)
    }
}
