package com.wearmux.android.integration.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchFusedImuSampleTest {
    @Test
    fun `reconstructs w from unit quaternion xyz`() {
        // x=0.6, y=0.8, z=0.0 -> w deve ser 0.0 (vetor unitario já, x²+y²+z²=1)
        val fused = watchFusedFromRaw(ax = 0, ay = 0, az = 1000, qxQ14 = (0.6f * 16384).toInt().toShort(), qyQ14 = (0.8f * 16384).toInt().toShort(), qzQ14 = 0)
        assertEquals(0f, fused.qw, 0.01f)
        assertEquals(0.6f, fused.qx, 0.01f)
        assertEquals(0.8f, fused.qy, 0.01f)
    }

    @Test
    fun `clamps sqrt argument to zero when quantization noise pushes sum above 1`() {
        // x=1.0, y=0.1, z=0.0 -> x²+y²+z² > 1 por ruido de quantizacao
        val fused = watchFusedFromRaw(ax = 0, ay = 0, az = 1000, qxQ14 = (1.0f * 16384).toInt().toShort(), qyQ14 = (0.1f * 16384).toInt().toShort(), qzQ14 = 0)
        assertEquals(0f, fused.qw, 0.0001f) // não pode ser NaN
    }
}
