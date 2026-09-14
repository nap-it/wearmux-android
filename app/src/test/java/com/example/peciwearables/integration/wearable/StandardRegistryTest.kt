package com.example.peciwearables.integration.wearable

import com.example.peciwearables.integration.wearable.devices.brilliantsole.BrilliantSoleWristbandWearableAdapter
import com.example.peciwearables.integration.wearable.devices.omi.OmiGlassesWearableAdapter
import com.example.peciwearables.integration.wearable.devices.esp32.Esp32WearableAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class StandardRegistryTest {

    private val legacyOmiUuid = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
    private val sdkOmiUuid = UUID.fromString("ea6d0000-a725-4f9b-893d-c3913e33b39f")
    private val photoDataUuid = UUID.fromString("19B10005-E8F2-537E-4F6C-D104768A1214")
    private val sdkRxUuid = UUID.fromString("ea6d1000-a725-4f9b-893d-c3913e33b39f")
    private val sdkTxUuid = UUID.fromString("ea6d1001-a725-4f9b-893d-c3913e33b39f")

    private val glassesAdapter = OmiGlassesWearableAdapter(clientFactory = { _ -> throw NotImplementedError() })
    private val wristbandAdapter = BrilliantSoleWristbandWearableAdapter(clientFactory = { _ -> throw NotImplementedError() })
    private val esp32Adapter = Esp32WearableAdapter()
    private val registry = standardWearableAdapterRegistry(glassesAdapter, wristbandAdapter, esp32Adapter)

    private fun scan(
        uuids: Set<UUID> = emptySet(),
        mfgType: Int? = null,
        name: String = "Device",
        deviceId: String = "AA:BB:CC:DD:EE:FF"
    ) = WearableScanCandidate(
        id = WearableId(deviceId),
        displayName = name,
        rssi = -60,
        advertisedServiceUuids = uuids,
        manufacturerData = mfgType?.let { mapOf(-1 to byteArrayOf(0, 0, it.toByte())) } ?: emptyMap(),
        serviceData = emptyMap(),
        raw = null,
    )

    private fun profile(
        chars: Set<UUID> = emptySet(),
        probes: Map<String, Any?> = emptyMap(),
        name: String = "Device",
        deviceId: String = "AA:BB:CC:DD:EE:FF"
    ) = WearableConnectedDeviceProfile(
        scan = scan(name = name, deviceId = deviceId),
        discoveredServices = emptySet(),
        discoveredCharacteristics = chars,
        firmwareVersion = null,
        deviceName = name,
        handshakeProbes = probes,
    )

    @Test
    fun legacyOmiUuid_selectsOnlyOmiAdapter() {
        val result = registry.selectProbable(scan(uuids = setOf(legacyOmiUuid)))
        assertEquals(listOf("omi-glasses"), result.map { it.id })
    }

    @Test
    fun sdkUuidWithGlassesType_selectsOnlyOmiAdapter() {
        val result = registry.selectProbable(scan(uuids = setOf(sdkOmiUuid), mfgType = 4))
        assertEquals(listOf("omi-glasses"), result.map { it.id })
    }

    @Test
    fun sdkUuidWithInsoleType_selectsOnlySoleAdapter() {
        val result = registry.selectProbable(scan(uuids = setOf(sdkOmiUuid), mfgType = 0))
        assertEquals(listOf("brilliant-sole-wristband"), result.map { it.id })
    }

    @Test
    fun sdkUuidWithNoMfgType_selectsOnlySoleAdapter() {
        val result = registry.selectProbable(scan(uuids = setOf(sdkOmiUuid)))
        assertEquals(listOf("brilliant-sole-wristband"), result.map { it.id })
    }

    @Test
    fun syntheticEsp32Name_selectsEsp32Adapter() {
        val result = registry.selectProbable(scan(name = "ESP32-CAM"))
        assertEquals(listOf("esp32-cam"), result.map { it.id })
    }

    @Test
    fun syntheticEsp32Id_selectsEsp32Adapter() {
        val result = registry.selectProbable(scan(deviceId = "esp32-cam-12345"))
        assertEquals(listOf("esp32-cam"), result.map { it.id })
    }

    @Test
    fun noKnownUuids_selectsNoAdapters() {
        val unknown = UUID.fromString("ffffffff-0000-0000-0000-000000000000")
        assertTrue(registry.selectProbable(scan(uuids = setOf(unknown))).isEmpty())
    }

    @Test
    fun photoDataChar_confirmsOmiAdapter() {
        val result = registry.confirm(profile(chars = setOf(photoDataUuid)))
        assertEquals("omi-glasses", result?.id)
    }

    @Test
    fun sdkCharsWithGlassesProbe_confirmsOmiAdapter() {
        val result = registry.confirm(
            profile(
                chars = setOf(sdkRxUuid, sdkTxUuid),
                probes = mapOf("sdkDeviceType" to 4),
            ),
        )
        assertEquals("omi-glasses", result?.id)
    }

    @Test
    fun sdkCharsWithInsoleProbe_confirmsSoleAdapter() {
        val result = registry.confirm(
            profile(
                chars = setOf(sdkRxUuid, sdkTxUuid),
                probes = mapOf("sdkDeviceType" to 0),
            ),
        )
        assertEquals("brilliant-sole-wristband", result?.id)
    }

    @Test
    fun sdkCharsNoProbe_confirmsSoleAdapter() {
        val result = registry.confirm(profile(chars = setOf(sdkRxUuid, sdkTxUuid)))
        assertEquals("brilliant-sole-wristband", result?.id)
    }

    @Test
    fun syntheticEsp32Name_confirmsEsp32Adapter() {
        val result = registry.confirm(profile(name = "ESP32-CAM"))
        assertEquals("esp32-cam", result?.id)
    }

    @Test
    fun noRelevantChars_confirmsNull() {
        assertNull(registry.confirm(profile(chars = emptySet())))
    }
}
