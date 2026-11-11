package com.wearmux.android.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImuSamplePairerTest {

    private fun sample(ts: Long, tag: Short) =
        ImuSamplePairer.AccelGyroSample(ts, tag, tag, tag, tag, tag, tag)

    @Test
    fun `pairWith returns null when nothing recorded yet`() {
        val pairer = ImuSamplePairer()
        assertNull(pairer.pairWith(1_000L))
    }

    @Test
    fun `pairWith returns closest sample by timestamp`() {
        val pairer = ImuSamplePairer()
        pairer.record(sample(1_000_000L, 1))   // 1ms
        pairer.record(sample(1_010_000L, 2))   // 11ms
        pairer.record(sample(1_020_000L, 3))   // 21ms

        // Orientation arrives at 1_011_000ns -> closest buffered is 1_010_000ns (tag 2)
        val result = pairer.pairWith(1_011_000L)!!
        assertEquals(2.toShort(), result.ax)
    }

    @Test
    fun `pairWith falls back to oldest buffered sample when orientation predates buffer`() {
        val pairer = ImuSamplePairer()
        pairer.record(sample(5_000_000L, 1))
        pairer.record(sample(5_010_000L, 2))

        val result = pairer.pairWith(1_000L)!!
        assertEquals(1.toShort(), result.ax)
    }

    @Test
    fun `pairWith picks earlier sample on exact tie`() {
        val pairer = ImuSamplePairer()
        pairer.record(sample(1_000_000L, 1))
        pairer.record(sample(1_020_000L, 2))

        // Orientation at 1_010_000ns is equidistant (10_000ns) from both.
        val result = pairer.pairWith(1_010_000L)!!
        assertEquals(1.toShort(), result.ax)
    }

    @Test
    fun `record evicts oldest sample beyond maxBufferSize`() {
        val pairer = ImuSamplePairer(maxBufferSize = 2)
        pairer.record(sample(1_000_000L, 1))
        pairer.record(sample(1_010_000L, 2))
        pairer.record(sample(1_020_000L, 3)) // evicts tag 1

        // Orientation at 1_000_000ns: tag 1 would be the closest if still buffered,
        // but it's evicted, so closest remaining is tag 2 (1_010_000ns).
        val result = pairer.pairWith(1_000_000L)!!
        assertEquals(2.toShort(), result.ax)
    }
}
