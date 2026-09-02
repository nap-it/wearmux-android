package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.atcll.AtcllClient
import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtcllSafetyAlertsTest {

    private val ev = AtcllSafetyAlerts(dangerRadiusM = 80f, emergencyRadiusM = 200f)

    private fun userAt(lat: Double, lon: Double) =
        PhoneGpsLocation(lat, lon, 0.0, 5f, 0f, 0f, 0L)

    @Test
    fun `speeding muito longe nao alerta`() {
        val msg = AtcllClient.IncomingMessage.SpeedingVehicle(
            latitude = 40.7000, longitude = -8.6000,
            speedKmh = 80f, distanceMeters = 0f, timestampMs = 0L,
        )
        val r = ev.evaluate(msg, userAt(40.6325, -8.6592))
        assertNull(r)
    }

    @Test
    fun `speeding perto alerta`() {
        // 40.6325 → 40.63284 ≈ 38m (dentro do raio danger 80m)
        val msg = AtcllClient.IncomingMessage.SpeedingVehicle(
            latitude = 40.63284, longitude = -8.6592,
            speedKmh = 80f, distanceMeters = 0f, timestampMs = 0L,
        )
        val r = ev.evaluate(msg, userAt(40.6325, -8.6592))
        assertNotNull(r)
        assertTrue(r is AtcllSafetyAlerts.Alert.Speeding)
    }

    @Test
    fun `emergency tem raio maior`() {
        // 120m → fora do danger (80m) mas dentro do emergency (200m)
        val msg = AtcllClient.IncomingMessage.EmergencyVehicle(
            latitude = 40.63358, longitude = -8.6592,
            speedKmh = 100f, type = "ambulance", distanceMeters = 0f, timestampMs = 0L,
        )
        val r = ev.evaluate(msg, userAt(40.6325, -8.6592))
        assertNotNull("Veículo de emergência tem raio maior", r)
        assertTrue(r is AtcllSafetyAlerts.Alert.Emergency)
    }

    @Test
    fun `sem GPS do utilizador nao alerta`() {
        val msg = AtcllClient.IncomingMessage.SpeedingVehicle(
            40.0, -8.0, 80f, 0f, 0L,
        )
        assertNull(ev.evaluate(msg, null))
    }
}
