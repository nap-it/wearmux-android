package com.example.peciwearables.telemetry

import com.example.peciwearables.integration.telemetry.TelemetryReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TelemetryReporterPayloadTest {

    @Test
    fun `Payload defaults glassesImu to null`() {
        val p = TelemetryReporter.Payload(lat = null, lon = null)
        assertNull(p.glassesImu)
    }

    @Test
    fun `Payload accepts glassesImu and preserves all fields`() {
        val imu = TelemetryReporter.ImuVector(
            ax = 1.0f, ay = 2.0f, az = 9.8f,
            gx = 0.1f, gy = 0.2f, gz = -0.3f,
        )
        val p = TelemetryReporter.Payload(lat = null, lon = null, glassesImu = imu)
        assertNotNull(p.glassesImu)
        assertEquals(1.0f, p.glassesImu!!.ax, 0.001f)
        assertEquals(9.8f, p.glassesImu!!.az, 0.001f)
        assertEquals(-0.3f, p.glassesImu!!.gz, 0.001f)
        assertNull(p.glassesImu!!.mx)
    }

    @Test
    fun `Payload accepts glassesImu with magnetometer fields`() {
        val imu = TelemetryReporter.ImuVector(
            ax = 0f, ay = 0f, az = 9.8f,
            gx = 0f, gy = 0f, gz = 0f,
            mx = 25.0f, my = 18.0f, mz = -42.0f,
        )
        val p = TelemetryReporter.Payload(lat = null, lon = null, glassesImu = imu)
        assertEquals(25.0f, p.glassesImu!!.mx!!, 0.001f)
        assertEquals(18.0f, p.glassesImu!!.my!!, 0.001f)
        assertEquals(-42.0f, p.glassesImu!!.mz!!, 0.001f)
    }
}
