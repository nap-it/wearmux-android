package com.example.peciwearables.integration.safety

import com.example.peciwearables.Detection
import com.example.peciwearables.integration.depth.DepthResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleApproachEvaluatorTest {

    /** Constrói um depth result uniforme — todas as posições devolvem `value`. */
    private fun uniformDepth(value: Float, w: Int = 16, h: Int = 16): DepthResult =
        DepthResult(
            values = FloatArray(w * h) { value },
            width = w,
            height = h,
        )

    private fun car(conf: Float = 0.8f): Detection =
        Detection("car", conf, 0.3f, 0.3f, 0.7f, 0.7f)

    @Test
    fun `sem depth devolve Clear sempre`() {
        val ev = VehicleApproachEvaluator()
        val r = ev.evaluate(listOf(car()), depth = null)
        assertEquals(VehicleApproachEvaluator.Decision.Clear, r)
    }

    @Test
    fun `sem detecções devolve Clear`() {
        val ev = VehicleApproachEvaluator()
        val r = ev.evaluate(emptyList(), uniformDepth(0.3f))
        assertEquals(VehicleApproachEvaluator.Decision.Clear, r)
    }

    @Test
    fun `pedestre nao é veiculo`() {
        val ev = VehicleApproachEvaluator()
        val person = Detection("person", 0.9f, 0.3f, 0.3f, 0.7f, 0.7f)
        val r = ev.evaluate(listOf(person), uniformDepth(0.2f))
        assertEquals(VehicleApproachEvaluator.Decision.Clear, r)
    }

    @Test
    fun `primeiras 2 deteccoes nao chegam a Alert (mins 3 amostras)`() {
        val ev = VehicleApproachEvaluator()
        val r1 = ev.evaluate(listOf(car()), uniformDepth(0.5f), nowMs = 100)
        val r2 = ev.evaluate(listOf(car()), uniformDepth(0.4f), nowMs = 500)
        assertTrue(r1 is VehicleApproachEvaluator.Decision.Watching)
        assertTrue(r2 is VehicleApproachEvaluator.Decision.Watching)
    }

    @Test
    fun `veiculo a aproximar consistentemente dispara Alert na 3a amostra`() {
        val ev = VehicleApproachEvaluator()
        // 2 primeiras criam histórico mas não disparam (size < 3).
        val r1 = ev.evaluate(listOf(car()), uniformDepth(0.50f), nowMs = 0)
        val r2 = ev.evaluate(listOf(car()), uniformDepth(0.45f), nowMs = 300)
        // 3ª amostra: closing rate ≈0.17/s, depth=0.40 < dangerDepth (0.45) → Alert.
        val r3 = ev.evaluate(listOf(car()), uniformDepth(0.40f), nowMs = 600)
        assertTrue(r1 is VehicleApproachEvaluator.Decision.Watching)
        assertTrue(r2 is VehicleApproachEvaluator.Decision.Watching)
        assertTrue("3a amostra deve disparar Alert", r3 is VehicleApproachEvaluator.Decision.Alert)
    }

    @Test
    fun `veiculo perto mas parado nao dispara Alert`() {
        val ev = VehicleApproachEvaluator()
        // Depth constante a 0.40 (perto) mas sem closing rate
        ev.evaluate(listOf(car()), uniformDepth(0.40f), nowMs = 0)
        ev.evaluate(listOf(car()), uniformDepth(0.40f), nowMs = 300)
        ev.evaluate(listOf(car()), uniformDepth(0.40f), nowMs = 600)
        val r = ev.evaluate(listOf(car()), uniformDepth(0.40f), nowMs = 900)
        assertTrue("Sem aproximação não é Alert", r is VehicleApproachEvaluator.Decision.Watching)
    }

    @Test
    fun `veiculo a aproximar mas ainda longe nao dispara Alert`() {
        val ev = VehicleApproachEvaluator()
        ev.evaluate(listOf(car()), uniformDepth(0.90f), nowMs = 0)
        ev.evaluate(listOf(car()), uniformDepth(0.85f), nowMs = 300)
        ev.evaluate(listOf(car()), uniformDepth(0.80f), nowMs = 600)
        val r = ev.evaluate(listOf(car()), uniformDepth(0.75f), nowMs = 900)
        // depth > dangerDepth (0.45) → Watching, não Alert
        assertTrue(r is VehicleApproachEvaluator.Decision.Watching)
    }

    @Test
    fun `cooldown evita Alert consecutivo no mesmo tipo`() {
        val ev = VehicleApproachEvaluator(cooldownMs = 4_000)
        // Setup mínimo para chegar à 3ª amostra com Alert.
        ev.evaluate(listOf(car()), uniformDepth(0.50f), nowMs = 0)
        ev.evaluate(listOf(car()), uniformDepth(0.45f), nowMs = 300)
        val first = ev.evaluate(listOf(car()), uniformDepth(0.40f), nowMs = 600)
        // Imediatamente a seguir, ainda dentro do cooldown:
        val second = ev.evaluate(listOf(car()), uniformDepth(0.35f), nowMs = 900)
        assertTrue("1ª deve ser Alert", first is VehicleApproachEvaluator.Decision.Alert)
        assertTrue("2ª dentro do cooldown deve ser Watching",
            second is VehicleApproachEvaluator.Decision.Watching)
    }

    @Test
    fun `confidence baixa de veiculo é ignorada`() {
        val ev = VehicleApproachEvaluator(minConfidence = 0.40f)
        val lowConf = car(conf = 0.30f)
        val r = ev.evaluate(listOf(lowConf), uniformDepth(0.20f))
        assertEquals(VehicleApproachEvaluator.Decision.Clear, r)
    }
}
