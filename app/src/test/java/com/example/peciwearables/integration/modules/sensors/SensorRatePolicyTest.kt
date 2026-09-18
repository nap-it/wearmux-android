package com.example.peciwearables.integration.modules.sensors

import com.example.peciwearables.integration.MlProcessingLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorRatePolicyTest {

    @Test
    fun `inferencia no wearable usa a taxa alta e ignora contencao`() {
        assertEquals(10, SensorRatePolicy.wristbandRateMs(MlProcessingLocation.WRISTBAND, contended = false))
        assertEquals(10, SensorRatePolicy.wristbandRateMs(MlProcessingLocation.WRISTBAND, contended = true))
    }

    @Test
    fun `sem contencao a taxa e a default`() {
        assertEquals(50, SensorRatePolicy.wristbandRateMs(MlProcessingLocation.APP, contended = false))
        assertEquals(50, SensorRatePolicy.wristbandRateMs(MlProcessingLocation.SERVER, contended = false))
    }

    @Test
    fun `camara ou microfone activos baixam a taxa`() {
        assertEquals(200, SensorRatePolicy.wristbandRateMs(MlProcessingLocation.APP, contended = true))
        assertEquals(200, SensorRatePolicy.wristbandRateMs(MlProcessingLocation.SERVER, contended = true))
    }

    @Test
    fun `magnetometro so e dispensado com inferencia no wearable`() {
        assertFalse(SensorRatePolicy.includeMagnetometer(MlProcessingLocation.WRISTBAND))
        assertTrue(SensorRatePolicy.includeMagnetometer(MlProcessingLocation.APP))
        assertTrue(SensorRatePolicy.includeMagnetometer(MlProcessingLocation.SERVER))
    }
}
