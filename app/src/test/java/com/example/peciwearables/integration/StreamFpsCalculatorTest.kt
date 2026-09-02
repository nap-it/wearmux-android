package com.example.peciwearables.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamFpsCalculatorTest {

    @Test
    fun `returns zero when fewer than two frames`() {
        assertEquals(0f, calculateRollingStreamFps(emptyList()), 0.0001f)
        assertEquals(0f, calculateRollingStreamFps(listOf(1000L)), 0.0001f)
    }

    @Test
    fun `uses elapsed time between first and last frame`() {
        val fps = calculateRollingStreamFps(listOf(0L, 100L, 200L, 300L))
        assertEquals(10f, fps, 0.0001f)
    }

    @Test
    fun `supports non half step fps values`() {
        val fps = calculateRollingStreamFps(listOf(0L, 83L, 166L, 249L, 332L))
        assertEquals(12.048193f, fps, 0.0001f)
    }
}
