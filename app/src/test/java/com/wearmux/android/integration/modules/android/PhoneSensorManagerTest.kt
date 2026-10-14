package com.wearmux.android.integration.modules.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneSensorManagerTest {

    private fun fakeContext(): Context {
        val sensorManager = mockk<SensorManager>(relaxed = true)
        every { sensorManager.getDefaultSensor(any()) } returns null
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
        return context
    }

    @Test
    fun `magnetic field event updates latestMag and invokes onMagSample without touching accel`() {
        val manager = PhoneSensorManager(fakeContext())
        var callbackValues: FloatArray? = null
        manager.onMagSample = { values, _ -> callbackValues = values }

        manager.handleSensorEvent(Sensor.TYPE_MAGNETIC_FIELD, floatArrayOf(1f, 2f, 3f), 123L)

        assertArrayEquals(floatArrayOf(1f, 2f, 3f), manager.latestMag.value, 0.001f)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), callbackValues, 0.001f)
        // Accel callback must not have fired for a magnetometer event.
        assertNull(manager.onAccelSample)
    }

    @Test
    fun `game rotation vector event updates latestGameRotation and fires callback`() {
        val manager = PhoneSensorManager(fakeContext())
        var callbackValues: FloatArray? = null
        var callbackTs: Long? = null
        manager.onGameRotationSample = { values, ts ->
            callbackValues = values
            callbackTs = ts
        }

        val quat = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f)
        manager.handleSensorEvent(Sensor.TYPE_GAME_ROTATION_VECTOR, quat, 12345L)

        assertArrayEquals(quat, manager.latestGameRotation.value, 0.0001f)
        assertArrayEquals(quat, callbackValues, 0.0001f)
        assertEquals(12345L, callbackTs)
    }

    @Test
    fun `game rotation vector event with 5-element array (with accuracy) still updates correctly`() {
        val manager = PhoneSensorManager(fakeContext())
        // Some OEM builds append estimated heading accuracy as a 5th float.
        val quatWithAccuracy = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f, 0.05f)

        manager.handleSensorEvent(Sensor.TYPE_GAME_ROTATION_VECTOR, quatWithAccuracy, 999L)

        assertEquals(0.1f, manager.latestGameRotation.value[0], 0.0001f)
        assertEquals(0.2f, manager.latestGameRotation.value[1], 0.0001f)
        assertEquals(0.3f, manager.latestGameRotation.value[2], 0.0001f)
        assertEquals(0.9f, manager.latestGameRotation.value[3], 0.0001f)
    }
}
