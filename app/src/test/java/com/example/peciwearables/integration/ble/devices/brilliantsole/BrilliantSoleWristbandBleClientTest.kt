package com.example.peciwearables.integration.ble.devices.brilliantsole

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrilliantSoleWristbandBleClientTest {

    @Test
    fun shouldConnectSharedSdkAdvertiser_falseForGlassesManufacturerType() {
        val manufacturerPayloads = listOf(byteArrayOf(0x00, 0x01, 4))

        assertFalse(
            BrilliantSoleWristbandBleClient.shouldConnectSharedSdkAdvertiser(
                manufacturerPayloads = manufacturerPayloads,
            ),
        )
    }

    @Test
    fun shouldConnectSharedSdkAdvertiser_falseForGlassesServiceDataType() {
        assertFalse(
            BrilliantSoleWristbandBleClient.shouldConnectSharedSdkAdvertiser(
                manufacturerPayloads = emptyList(),
                serviceData = byteArrayOf(4),
            ),
        )
    }

    @Test
    fun shouldConnectSharedSdkAdvertiser_trueForNonGlassesManufacturerType() {
        val manufacturerPayloads = listOf(byteArrayOf(0x00, 0x01, 0))

        assertTrue(
            BrilliantSoleWristbandBleClient.shouldConnectSharedSdkAdvertiser(
                manufacturerPayloads = manufacturerPayloads,
            ),
        )
    }

    @Test
    fun shouldConnectSharedSdkAdvertiser_trueWhenTypeMissing() {
        assertTrue(
            BrilliantSoleWristbandBleClient.shouldConnectSharedSdkAdvertiser(
                manufacturerPayloads = listOf(byteArrayOf(0x00, 0x01)),
            ),
        )
    }
}
