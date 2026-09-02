package com.example.peciwearables.integration.safety

import org.junit.Assert.assertEquals
import org.junit.Test

class YawSpreadCalculatorTest {

    @Test
    fun `returns 0 when fewer than 2 samples`() {
        val calc = YawSpreadCalculator(windowMs = 8_000L)
        assertEquals(0f, calc.onYawSample(45f, nowMs = 1000L), 0.1f)
    }

    @Test
    fun `simple spread no wraparound`() {
        val calc = YawSpreadCalculator(windowMs = 8_000L)
        calc.onYawSample(-35f, nowMs = 1000L)
        val spread = calc.onYawSample(30f, nowMs = 1500L)
        // spread = 30 - (-35) = 65°
        assertEquals(65f, spread, 0.5f)
    }

    @Test
    fun `spread across plus-minus 180 wraparound`() {
        val calc = YawSpreadCalculator(windowMs = 8_000L)
        calc.onYawSample(170f, nowMs = 1000L)
        val spread = calc.onYawSample(-170f, nowMs = 1500L)
        // True angular distance = 20°, NOT 340°
        assertEquals(20f, spread, 0.5f)
    }

    @Test
    fun `prunes samples older than window`() {
        val calc = YawSpreadCalculator(windowMs = 8_000L)
        calc.onYawSample(-50f, nowMs = 0L)       // will be pruned
        calc.onYawSample(50f, nowMs = 1000L)     // will be pruned
        // At nowMs=9000, both above are outside the 8s window
        val spread = calc.onYawSample(5f, nowMs = 9000L)
        // Only one sample in window → spread = 0
        assertEquals(0f, spread, 0.1f)
    }

    @Test
    fun `below threshold does not exceed 60`() {
        val calc = YawSpreadCalculator(windowMs = 8_000L)
        calc.onYawSample(0f, nowMs = 1000L)
        val spread = calc.onYawSample(25f, nowMs = 1500L)
        assert(spread < 60f) { "Expected spread < 60° but got $spread" }
    }
}
