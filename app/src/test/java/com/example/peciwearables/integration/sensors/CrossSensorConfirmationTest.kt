package com.example.peciwearables.integration.sensors

import com.example.peciwearables.integration.protocol.GlassesImuSample
import com.example.peciwearables.integration.protocol.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossSensorConfirmationTest {

    private val ev = CrossSensorConfirmation()

    @Test
    fun `phone calmo + oculos calmos = stationary`() {
        val r = ev.evaluate(
            CrossSensorConfirmation.Inputs(
                glasses = glasses(gx = 1f, gy = 1f, gz = 1f),
                phoneAx = 0.1f, phoneAy = 0.05f, phoneAz = 0.1f,
            )
        )
        assertTrue("esperava stationary, got $r", r.isStationary)
        assertFalse(r.isWalking)
        assertFalse(r.isLookingAround)
    }

    @Test
    fun `so phone calmo (sem oculos) = stationary`() {
        // Sem glasses, glassesGyroMag = NaN → cláusula calmo passa.
        val r = ev.evaluate(
            CrossSensorConfirmation.Inputs(
                phoneAx = 0.05f, phoneAy = 0.05f, phoneAz = 0.05f,
            )
        )
        assertTrue(r.isStationary)
    }

    @Test
    fun `phone activo, oculos calmos = NOT stationary`() {
        val r = ev.evaluate(
            CrossSensorConfirmation.Inputs(
                glasses = glasses(gx = 1f, gy = 1f, gz = 1f),
                phoneAx = 2f, phoneAy = 0f, phoneAz = 0f,
            )
        )
        assertFalse(r.isStationary)
    }

    @Test
    fun `walking exige phone E pulso activos`() {
        val r = ev.evaluate(
            CrossSensorConfirmation.Inputs(
                wristband = imu(ax = 250, az = 1000),     // ~3.34 m/s² > 2.2
                phoneAx = 1.5f, phoneAy = 0f, phoneAz = 0f,  // > 1.4
            )
        )
        assertTrue("esperava walking, got $r", r.isWalking)
    }

    @Test
    fun `so phone agitado (sem pulso) = NAO walking`() {
        val r = ev.evaluate(
            CrossSensorConfirmation.Inputs(
                phoneAx = 5f, phoneAy = 0f, phoneAz = 0f,
            )
        )
        assertFalse("esperava NÃO walking, got $r", r.isWalking)
    }

    @Test
    fun `so pulso agitado (sem phone) = NAO walking`() {
        val r = ev.evaluate(
            CrossSensorConfirmation.Inputs(
                wristband = imu(ax = 1000, az = 1000),    // 13.9 m/s²
            )
        )
        assertFalse(r.isWalking)
    }

    @Test
    fun `looking around dispara so com gyro Z dos oculos grande`() {
        val r = ev.evaluate(
            CrossSensorConfirmation.Inputs(
                glasses = glasses(gz = 80f),
            )
        )
        assertTrue(r.isLookingAround)
    }

    @Test
    fun `looking around NAO dispara sem oculos`() {
        val r = ev.evaluate(CrossSensorConfirmation.Inputs())
        assertFalse(r.isLookingAround)
    }

    @Test
    fun `confirmedBy conta sensores activos`() {
        val r = ev.evaluate(
            CrossSensorConfirmation.Inputs(
                glasses = glasses(),
                wristband = imu(),
                phoneAx = 0f, phoneAy = 0f, phoneAz = 0f,
            )
        )
        assertEquals(3, r.confirmedBy)
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun glasses(
        ax: Float = 0f, ay: Float = 0f, az: Float = 9.8f,
        gx: Float = 0f, gy: Float = 0f, gz: Float = 0f,
        mx: Float = 0f, my: Float = 0f, mz: Float = 0f,
    ) = GlassesImuSample(ax, ay, az, gx, gy, gz, mx, my, mz)

    private fun imu(
        ax: Int = 0, ay: Int = 0, az: Int = 0,
        gx: Int = 0, gy: Int = 0, gz: Int = 0,
        mx: Int = 0, my: Int = 0, mz: Int = 0,
    ) = ImuSample(
        ax.toShort(), ay.toShort(), az.toShort(),
        gx.toShort(), gy.toShort(), gz.toShort(),
        mx.toShort(), my.toShort(), mz.toShort(),
        ShortArray(8),
    )
}
