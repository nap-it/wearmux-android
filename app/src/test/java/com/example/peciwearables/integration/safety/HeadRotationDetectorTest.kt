package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.protocol.GlassesImuSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadRotationDetectorTest {

    /** Constrói uma amostra com apenas gyroZ definido (em deg/s). */
    private fun imu(gzDegS: Float, mx: Float = 0f, my: Float = 0f): GlassesImuSample =
        GlassesImuSample(
            ax = 0f, ay = 0f, az = 9.81f,
            gx = 0f, gy = 0f, gz = gzDegS,
            mx = mx, my = my, mz = 0f,
        )

    @Test
    fun `parado nao dispara nada`() {
        val d = HeadRotationDetector(windowMs = 2_000)
        var t = 0L
        repeat(50) {
            d.onSample(imu(0.5f), t); t += 40
        }
        assertFalse(d.rotatedRecently())
        assertFalse(d.lookedBothWays())
    }

    @Test
    fun `tremor a dizer 'nao' nao passa por lookedBothWays`() {
        // Sacudidela curta: oscila ±20°/s a 2 Hz durante 1s — integral pequena.
        val d = HeadRotationDetector(windowMs = 2_000)
        var t = 0L
        for (i in 0 until 25) {
            val sign = if (i % 2 == 0) 20f else -20f
            d.onSample(imu(sign), t); t += 40
        }
        // Pode passar `rotatedRecently` se houver acumulação, mas não os 2 lados.
        assertFalse("Tremor não deve passar lookedBothWays", d.lookedBothWays())
    }

    @Test
    fun `olhou esquerda e depois direita dispara lookedBothWays`() {
        val d = HeadRotationDetector(windowMs = 3_000)
        var t = 0L
        // 1s a +40°/s (esquerda) → +40°
        repeat(25) { d.onSample(imu(40f), t); t += 40 }
        // 1s a -80°/s (volta + direita) → -40° relativo ao início
        repeat(25) { d.onSample(imu(-80f), t); t += 40 }
        // Confirma min ≤ -22 e max ≥ +22
        assertTrue("Spread suficiente", d.spreadDeg() >= 44f)
        assertTrue("lookedBothWays deve disparar", d.lookedBothWays())
    }

    @Test
    fun `olhar so para um lado nao dispara lookedBothWays`() {
        val d = HeadRotationDetector(windowMs = 3_000)
        var t = 0L
        // 1s a +60°/s — só esquerda, não regressa
        repeat(25) { d.onSample(imu(60f), t); t += 40 }
        assertTrue("rotatedRecently sim (rodou)", d.rotatedRecently())
        assertFalse("lookedBothWays não (só um lado)", d.lookedBothWays())
    }

    @Test
    fun `janela expira e reset apos windowMs`() {
        val d = HeadRotationDetector(windowMs = 1_000)
        var t = 0L
        // Burst que vai a +30°, depois desce a -30° (= +30 → 0 → -30 → 0).
        // Resultado dentro da janela: min ≈ -30°, max ≈ +30°.
        repeat(10) { d.onSample(imu(100f), t); t += 30 }      // +30°
        repeat(20) { d.onSample(imu(-100f), t); t += 30 }     // → -30°
        assertTrue("min ≤ -22 e max ≥ +22 esperado", d.lookedBothWays())
        // Salta para fora da janela — primeira amostra > windowMs faz reset.
        t += 2_000
        d.onSample(imu(0f), t)
        assertFalse("janela velha caducou", d.lookedBothWays())
    }
}
