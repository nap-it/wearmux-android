package com.wearmux.android.integration.modules.sensors

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MadgwickAhrsTest {

    private fun assertNear(expected: Float, actual: Float, tol: Float = 0.01f) {
        assertTrue(
            "expected $expected, got $actual (tol $tol)",
            abs(expected - actual) <= tol,
        )
    }

    @Test
    fun `fresh filter starts at identity quaternion`() {
        val filter = MadgwickAhrs()
        val q = filter.quaternion()
        assertEquals(4, q.size)
        assertNear(0f, q[0]); assertNear(0f, q[1]); assertNear(0f, q[2]); assertNear(1f, q[3])
    }

    @Test
    fun `static device converges gravity vector to measured accel direction`() {
        val filter = MadgwickAhrs(sampleFreqHz = 50f, beta = 0.8f) // high beta: fast convergence for test
        // Device lying flat, Z-up: accel reads pure gravity on Z, no mag disturbance (mag points +Y = north).
        repeat(200) {
            filter.update(
                gx = 0f, gy = 0f, gz = 0f,
                ax = 0f, ay = 0f, az = 9.80665f,
                mx = 0f, my = 1f, mz = 0f,
            )
        }
        val g = filter.gravityVector()
        assertNear(0f, g[0], 0.05f)
        assertNear(0f, g[1], 0.05f)
        assertNear(9.80665f, g[2], 0.1f)
    }

    @Test
    fun `filter never produces NaN even on the very first update from identity`() {
        val filter = MadgwickAhrs()
        filter.update(
            gx = 0.01f, gy = -0.02f, gz = 0.03f,
            ax = 0.2f, ay = 0.1f, az = 9.7f,
            mx = 12f, my = 5f, mz = -30f,
        )
        val q = filter.quaternion()
        q.forEach { assertTrue("quaternion component is NaN", !it.isNaN()) }
        val norm = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        assertNear(1f, norm, 0.01f)
    }

    @Test
    fun `near-vertical static pose does not diverge or NaN`() {
        val filter = MadgwickAhrs(sampleFreqHz = 50f, beta = 0.6f)
        // Device held vertical: gravity almost entirely on X axis (pitch ~90 deg).
        repeat(200) {
            filter.update(
                gx = 0f, gy = 0f, gz = 0f,
                ax = 9.80665f, ay = 0f, az = 0.01f,
                mx = 0f, my = 1f, mz = 0f,
            )
        }
        val q = filter.quaternion()
        q.forEach { assertTrue("quaternion component is NaN after vertical pose", !it.isNaN()) }
        val g = filter.gravityVector()
        assertNear(9.80665f, g[0], 0.15f)
        assertNear(0f, g[2], 0.15f)
    }
}
