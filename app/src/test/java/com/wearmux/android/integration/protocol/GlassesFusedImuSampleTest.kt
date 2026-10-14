package com.wearmux.android.integration.protocol

import com.wearmux.android.integration.adapters.SdkSensorSample
import com.wearmux.android.integration.adapters.SdkSensorType
import org.junit.Assert.*
import org.junit.Test

class GlassesFusedImuSampleTest {

    @Test
    fun `returns null when no linearAcceleration sample has arrived yet`() {
        val gameRotation = SdkSensorSample(
            sensorType = SdkSensorType.GAME_ROTATION,
            x = 1638, y = 3277, z = 4915, w = 14746, timestampRaw16 = 0,
        )

        val result = mergeGlassesFusedSample(lastLinearAccel = null, gameRotation = gameRotation)

        assertNull(result)
    }

    @Test
    fun `merges the newest linearAcceleration sample with an arriving gameRotation sample, scaled by the Q-point convention`() {
        val linearAccel = SdkSensorSample(
            sensorType = SdkSensorType.LINEAR_ACCELERATION,
            x = 16384, y = -16384, z = 8192, w = 0, timestampRaw16 = 0,
        )
        val gameRotation = SdkSensorSample(
            sensorType = SdkSensorType.GAME_ROTATION,
            x = 1638, y = 3277, z = 4915, w = 14746, timestampRaw16 = 0,
        )

        val result = mergeGlassesFusedSample(lastLinearAccel = linearAccel, gameRotation = gameRotation)

        assertNotNull(result)
        val sample = result!!
        assertEquals(1f, sample.ax, 0.001f); assertEquals(-1f, sample.ay, 0.001f); assertEquals(0.5f, sample.az, 0.001f)
        assertEquals(0.1f, sample.qx, 0.001f); assertEquals(0.2f, sample.qy, 0.001f)
        assertEquals(0.3f, sample.qz, 0.001f); assertEquals(0.9f, sample.qw, 0.001f)
    }
}
