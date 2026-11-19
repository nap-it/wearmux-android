package com.wearmux.android.integration.adapters

import com.wearmux.android.integration.hub.DeviceType
import com.wearmux.android.integration.hub.WearableAdapterRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearableAdapterRegistryTest {

    private val uuidA = UUID.fromString("11111111-0000-0000-0000-000000000000")
    private val uuidB = UUID.fromString("22222222-0000-0000-0000-000000000000")
    private val uuidC = UUID.fromString("33333333-0000-0000-0000-000000000000")

    /** UUID de serviço das pulseiras Brilliant Sole. */
    private val soleUuid = UUID.fromString("ea6d0000-a725-4f9b-893d-c3913e33b39f")

    @Test
    fun selectProbable_emptyWhenNoAdapterMatches() {
        val registry = WearableAdapterRegistry(
            mapOf(
                DeviceType.GLASSES_OMI to { FakeWearableAdapter(id = "A", probable = { false }) },
                DeviceType.WRISTBAND_BRILLIANT_SOLE to { FakeWearableAdapter(id = "B", probable = { false }) },
            ),
        )
        val result = registry.selectProbable(scanCandidate(serviceUuids = setOf(uuidC)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun selectProbable_returnsAllAdaptersThatMatch() {
        val registry = WearableAdapterRegistry(
            mapOf(
                DeviceType.GLASSES_OMI to { FakeWearableAdapter(id = "A", probable = { uuidA in it.advertisedServiceUuids }) },
                DeviceType.WRISTBAND_BRILLIANT_SOLE to { FakeWearableAdapter(id = "B", probable = { uuidA in it.advertisedServiceUuids }) },
                DeviceType.ESP32_CAMERA to { FakeWearableAdapter(id = "C", probable = { uuidB in it.advertisedServiceUuids }) },
            ),
        )
        val result = registry.selectProbable(scanCandidate(serviceUuids = setOf(uuidA)))
        assertEquals(listOf("A", "B"), result.map { it.id })
    }

    /**
     * `confirm` resolve o adapter pelo **tipo** do dispositivo (via
     * `DeviceType.fromScan`), não por "o primeiro que confirma".
     */
    @Test
    fun confirm_resolvesGlassesAdapterByDeviceType() {
        val registry = WearableAdapterRegistry(
            mapOf(
                DeviceType.GLASSES_OMI to { FakeWearableAdapter(id = "A") },
                DeviceType.WRISTBAND_BRILLIANT_SOLE to { FakeWearableAdapter(id = "B") },
            ),
        )
        val profile = deviceProfile(
            scan = scanCandidate(manufacturerData = mapOf(-1 to byteArrayOf(0, 0, 4))),
        )
        assertEquals("A", registry.confirm(profile)?.id)
    }

    @Test
    fun confirm_resolvesSoleAdapterByDeviceType() {
        val registry = WearableAdapterRegistry(
            mapOf(
                DeviceType.GLASSES_OMI to { FakeWearableAdapter(id = "A") },
                DeviceType.WRISTBAND_BRILLIANT_SOLE to { FakeWearableAdapter(id = "B") },
            ),
        )
        val profile = deviceProfile(scan = scanCandidate(serviceUuids = setOf(soleUuid)))
        assertEquals("B", registry.confirm(profile)?.id)
    }

    @Test
    fun confirm_nullWhenNoAdapterConfirms() {
        val registry = WearableAdapterRegistry(
            mapOf(
                DeviceType.GLASSES_OMI to { FakeWearableAdapter(id = "A") },
            ),
        )
        assertNull(registry.confirm(deviceProfile()))
    }
}
