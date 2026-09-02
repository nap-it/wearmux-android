package com.example.peciwearables.integration.wearable.devices.esp32

import com.example.peciwearables.integration.wearable.WearableConnectedDeviceProfile
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableScanCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class Esp32WearableAdapterTest {

    @Test
    fun `probableMatch accepts synthetic ESP32 candidate`() {
        val adapter = Esp32WearableAdapter()

        val candidate = WearableScanCandidate(
            id = WearableId("esp32-cam-192.168.4.1"),
            displayName = "ESP32-CAM",
            rssi = 0,
            advertisedServiceUuids = emptySet(),
            manufacturerData = emptyMap(),
            serviceData = emptyMap(),
            raw = null
        )

        assertTrue(adapter.probableMatch(candidate))
    }

    @Test
    fun `probableMatch rejects other candidates`() {
        val adapter = Esp32WearableAdapter()

        val candidate = WearableScanCandidate(
            id = WearableId("omi-glasses-1"),
            displayName = "Óculos",
            rssi = 0,
            advertisedServiceUuids = setOf(UUID.randomUUID()),
            manufacturerData = emptyMap(),
            serviceData = emptyMap(),
            raw = null
        )

        assertFalse(adapter.probableMatch(candidate))
    }

    @Test
    fun `confirmMatch accepts synthetic ESP32 profile`() {
        val adapter = Esp32WearableAdapter()

        val candidate = WearableScanCandidate(
            id = WearableId("esp32-cam-192.168.4.1"),
            displayName = "ESP32-CAM",
            rssi = 0,
            advertisedServiceUuids = emptySet(),
            manufacturerData = emptyMap(),
            serviceData = emptyMap(),
            raw = null
        )

        val profile = WearableConnectedDeviceProfile(
            scan = candidate,
            discoveredServices = emptySet(),
            discoveredCharacteristics = emptySet(),
            firmwareVersion = null,
            deviceName = null
        )

        assertTrue(adapter.confirmMatch(profile))
    }

    @Test
    fun `createSession returns valid session`() = runTest {
        val adapter = Esp32WearableAdapter()
        val candidate = WearableScanCandidate(
            id = WearableId("esp32-cam-192.168.4.1"),
            displayName = "ESP32-CAM",
            rssi = 0,
            advertisedServiceUuids = emptySet(),
            manufacturerData = emptyMap(),
            serviceData = emptyMap(),
            raw = null
        )
        val profile = WearableConnectedDeviceProfile(
            scan = candidate,
            discoveredServices = emptySet(),
            discoveredCharacteristics = emptySet(),
            firmwareVersion = null,
            deviceName = null
        )

        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val session = adapter.createSession(profile, scope)

        assertTrue(session is Esp32WearableSession)
        assertTrue(session.id.raw == "esp32-cam-192.168.4.1")
    }
}
