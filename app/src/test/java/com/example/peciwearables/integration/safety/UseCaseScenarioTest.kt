package com.example.peciwearables.integration.safety

import com.example.peciwearables.Detection
import com.example.peciwearables.integration.atcll.AtcllClient
import com.example.peciwearables.integration.depth.DepthResult
import com.example.peciwearables.integration.protocol.GlassesImuSample
import com.example.peciwearables.integration.protocol.ImuSample
import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test


class UseCaseScenarioTest {

    private val zoneA = CrossingZone("z1", "Av. da Liberdade", 40.6325, -8.6592, 12f)

    private fun gpsHere(speed: Float = 1.4f) =
        PhoneGpsLocation(zoneA.latitude, zoneA.longitude, 0.0, 4f, speed, 0f, System.currentTimeMillis())

    /** glassesImu sem mag (mx=my=0) → magValid=false → cross-check só por gyro. */
    private fun glassesGyro(gz: Float) =
        GlassesImuSample(0f, 0f, 9.81f, 0f, 0f, gz, 0f, 0f, 0f)



    @Test
    fun uc1_1_anda_sem_olhar_em_zona_dispara_danger() {
        // Inputs: moving=true, headRotated=false, decelerating=false, em zona.
        val d = PedestrianSafetyEvaluator().evaluate(
            zones = listOf(zoneA),
            gps = gpsHere(),
            moving = true,
            headRotated = false,
            decelerating = false,
        )
        assertTrue("Danger esperado: $d", d is PedestrianSafetyDecision.Danger)
        assertEquals("Av. da Liberdade", (d as PedestrianSafetyDecision.Danger).zoneName)
    }

    @Test
    fun uc1_1_olhou_e_desacelerou_nao_dispara() {
        val ev = PedestrianSafetyEvaluator()
        // Cenário: anda, olha, abranda — todas as protecções activas
        val withLook = ev.evaluate(listOf(zoneA), gpsHere(), true, true, false)
        val withDecel = ev.evaluate(listOf(zoneA), gpsHere(), true, false, true)
        val parado = ev.evaluate(listOf(zoneA), gpsHere(), false, false, false)
        assertTrue(withLook is PedestrianSafetyDecision.Watching)
        assertTrue(withDecel is PedestrianSafetyDecision.Watching)
        assertTrue(parado is PedestrianSafetyDecision.Watching)
    }

    @Test
    fun uc1_1_fora_de_zona_e_safe_mesmo_distraido() {
        val faraway = PhoneGpsLocation(40.7000, -8.7000, 0.0, 5f, 1.4f, 0f, 0L)
        val d = PedestrianSafetyEvaluator().evaluate(
            zones = listOf(zoneA), gps = faraway,
            moving = true, headRotated = false, decelerating = false,
        )
        assertEquals(PedestrianSafetyDecision.Safe, d)
    }



    @Test
    fun uc1_2_carro_a_aproximar_dispara_alert() {
        val ev = VehicleApproachEvaluator()
        val car = Detection("car", 0.85f, 0.30f, 0.30f, 0.70f, 0.70f)
        fun depth(v: Float) = DepthResult(FloatArray(16 * 16) { v }, 16, 16)

        // 3 frames com depth a cair de 0.55 → 0.50 → 0.42 (aproxima-se)
        ev.evaluate(listOf(car), depth(0.55f), nowMs = 0)
        ev.evaluate(listOf(car), depth(0.50f), nowMs = 300)
        val d = ev.evaluate(listOf(car), depth(0.42f), nowMs = 600)
        assertTrue("Alert esperado: $d", d is VehicleApproachEvaluator.Decision.Alert)
        assertEquals("car", (d as VehicleApproachEvaluator.Decision.Alert).label)
    }

    @Test
    fun uc1_2_carro_estacionado_perto_nao_dispara() {
        val ev = VehicleApproachEvaluator()
        val car = Detection("car", 0.85f, 0.30f, 0.30f, 0.70f, 0.70f)
        fun depth(v: Float) = DepthResult(FloatArray(16 * 16) { v }, 16, 16)
        // Depth constante a 0.35 (perto mas parado) → Watching, não Alert.
        ev.evaluate(listOf(car), depth(0.35f), nowMs = 0)
        ev.evaluate(listOf(car), depth(0.35f), nowMs = 300)
        val d = ev.evaluate(listOf(car), depth(0.35f), nowMs = 600)
        assertTrue("Watching: $d", d is VehicleApproachEvaluator.Decision.Watching)
    }



    @Test
    fun uc1_3_olhou_em_zona_sem_carro_dispara_ok() {
        // Alimenta head detector com rotação suficiente para os 2 lados
        val head = HeadRotationDetector(windowMs = 5_000)
        var t = 0L
        repeat(20) { head.onSample(glassesGyro(+80f), t); t += 25 }   // +40°
        repeat(40) { head.onSample(glassesGyro(-80f), t); t += 25 }   // → -40°

        val d = CrossingValidator().validate(
            zones = listOf(zoneA), gps = gpsHere(),
            latestDetections = emptyList(),
            lookedBothWays = head.lookedBothWays(),
        )
        assertTrue("Ok esperado: $d", d is CrossingValidationDecision.Ok)
    }

    @Test
    fun uc1_3_carro_na_faixa_responde_wait() {
        val car = Detection("car", 0.78f, 0.1f, 0.1f, 0.9f, 0.9f)
        val d = CrossingValidator().validate(
            zones = listOf(zoneA), gps = gpsHere(),
            latestDetections = listOf(car),
            lookedBothWays = true,
        )
        assertTrue(d is CrossingValidationDecision.Wait)
        assertTrue((d as CrossingValidationDecision.Wait).reason.contains("car"))
    }

    @Test
    fun uc1_3_nao_olhou_responde_wait() {
        val d = CrossingValidator().validate(
            zones = listOf(zoneA), gps = gpsHere(),
            latestDetections = emptyList(),
            lookedBothWays = false,
        )
        assertTrue(d is CrossingValidationDecision.Wait)
        assertTrue((d as CrossingValidationDecision.Wait).reason.contains("dois lados"))
    }



    @Test
    fun uc2_1_parado_em_zona_e_olhou_envia_vam() {
        val d = CrossingIntentionEvaluator().evaluate(
            zones = listOf(zoneA),
            gps = gpsHere(speed = 0f),
            moving = false,
            lookedBothWays = true,
        )
        assertTrue("IntentDetected: $d",
            d is CrossingIntentionEvaluator.Decision.IntentDetected)
    }

    @Test
    fun uc2_1_a_andar_nao_envia_vam() {
        val d = CrossingIntentionEvaluator().evaluate(
            zones = listOf(zoneA), gps = gpsHere(),
            moving = true,        // a andar — não é intenção, é UC1.1
            lookedBothWays = true,
        )
        assertEquals(CrossingIntentionEvaluator.Decision.Idle, d)
    }



    @Test
    fun uc3_1_carro_em_excesso_a_50m_dispara_speeding() {
        val msg = AtcllClient.IncomingMessage.SpeedingVehicle(
            latitude = zoneA.latitude + 0.00045,  // ~50m norte
            longitude = zoneA.longitude,
            speedKmh = 75f, distanceMeters = 0f, timestampMs = 0L,
        )
        val alert = AtcllSafetyAlerts(dangerRadiusM = 80f).evaluate(msg, gpsHere())
        assertNotNull("Alerta esperado a 50m", alert)
        assertTrue(alert is AtcllSafetyAlerts.Alert.Speeding)
    }

    @Test
    fun uc3_2_emergencia_a_150m_dispara_emergency() {
        val msg = AtcllClient.IncomingMessage.EmergencyVehicle(
            latitude = zoneA.latitude + 0.00135,  // ~150m
            longitude = zoneA.longitude,
            speedKmh = 90f, type = "ambulance", distanceMeters = 0f, timestampMs = 0L,
        )
        val alert = AtcllSafetyAlerts(emergencyRadiusM = 200f).evaluate(msg, gpsHere())
        assertNotNull(alert)
        assertTrue(alert is AtcllSafetyAlerts.Alert.Emergency)
        val em = alert as AtcllSafetyAlerts.Alert.Emergency
        assertEquals("ambulance", em.type)
    }

    @Test
    fun uc3_carro_longe_nao_alerta() {
        val msg = AtcllClient.IncomingMessage.SpeedingVehicle(
            latitude = 40.7000, longitude = -8.7000,
            speedKmh = 100f, distanceMeters = 0f, timestampMs = 0L,
        )
        assertEquals(null, AtcllSafetyAlerts(dangerRadiusM = 80f).evaluate(msg, gpsHere()))
    }



    @Test
    fun uc1_4_gps_rapido_com_cadencia_detecta_cycling() {
        val det = CyclistModeDetector(minCyclingSpeedMps = 2.5f, dwellMs = 8_000)
        var t = 100L  // t=0 enganaria o detector (`if (slowSinceMs == 0L)`)
        // 12 s a 4 m/s + amostras IMU oscilantes a 0.5 Hz (cadência ciclista)
        while (t < 12_100) {
            det.onGps(PhoneGpsLocation(40.0, -8.0, 0.0, 5f, 4.0f, 0f, t), nowMs = t)
            // Oscilação ax = ±3 m/s² a 0.5 Hz (period 2 s)
            val phase = 2.0 * Math.PI * 0.5 * (t / 1000.0)
            val axMg = (kotlin.math.sin(phase) * 306).toInt().toShort()
            det.onImu(
                ImuSample(axMg, 0, 1000, 0, 0, 0, 0, 0, 0, ShortArray(8)),
                t,
            )
            t += 50
        }
        val cad = det.cadenceHz()
        assertTrue("cadência ${"%.2f".format(cad)} deve estar em 0.8..1.6", cad in 0.8f..1.6f)
        assertEquals(
            "Esperado CYCLING após 12 s a 4 m/s + cadência ~1 Hz",
            CyclistModeDetector.Mode.CYCLING,
            det.currentMode(nowMs = t),
        )
    }

    @Test
    fun uc1_4_gps_lento_e_walking() {
        val det = CyclistModeDetector()
        var t = 100L
        while (t < 12_100) {
            det.onGps(PhoneGpsLocation(40.0, -8.0, 0.0, 5f, 1.2f, 0f, t), nowMs = t)
            det.onImu(ImuSample(0, 0, 1000, 0, 0, 0, 0, 0, 0, ShortArray(8)), t)
            t += 100
        }
        // < minCyclingSpeed (2.5 m/s) sustentadamente → WALKING
        assertEquals(CyclistModeDetector.Mode.WALKING, det.currentMode(nowMs = t))
    }
}
