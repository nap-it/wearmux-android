package com.wearmux.android.integration.modules.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
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
}
