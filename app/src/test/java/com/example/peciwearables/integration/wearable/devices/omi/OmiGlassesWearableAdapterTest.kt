package com.example.peciwearables.integration.wearable.devices.omi

import com.example.peciwearables.integration.wearable.WearableCapability
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableScanCandidate
import com.example.peciwearables.integration.wearable.WearableConnectedDeviceProfile
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class OmiGlassesWearableAdapterTest {

    private val legacyServiceUuid = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
    private val sdkServiceUuid = UUID.fromString("ea6d0000-a725-4f9b-893d-c3913e33b39f")
    private val sdkServiceDataUuid = UUID.fromString("00000000-0000-1000-8000-00805f9b34fb")
    private val photoDataUuid = UUID.fromString("19B10005-E8F2-537E-4F6C-D104768A1214")
    private val sdkTxUuid = UUID.fromString("ea6d1001-a725-4f9b-893d-c3913e33b39f")
    private val sdkRxUuid = UUID.fromString("ea6d1000-a725-4f9b-893d-c3913e33b39f")
    private val batteryUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    private val firmwareUuid = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    private val fakeClients = mutableMapOf<WearableId, FakeOmiGlassesBleClient>()

    private fun buildAdapter(): OmiGlassesWearableAdapter {
        return OmiGlassesWearableAdapter(
            clientFactory = { id ->
                FakeOmiGlassesBleClient().also { fakeClients[id] = it }
            },
        )
    }

    private fun scan(
        id: String = "AA:11:22:33:44:55",
        name: String = "Unknown Device",
        uuids: Set<UUID> = emptySet(),
        mfgData: Map<Int, ByteArray> = emptyMap(),
        serviceData: Map<UUID, ByteArray> = emptyMap(),
    ) = WearableScanCandidate(
        id = WearableId(id),
        displayName = name,
        rssi = -55,
        advertisedServiceUuids = uuids,
        manufacturerData = mfgData,
        serviceData = serviceData,
        raw = null,
    )

    private fun profile(
        id: String = "AA:11:22:33:44:55",
        services: Set<UUID> = emptySet(),
        chars: Set<UUID> = emptySet(),
        probes: Map<String, Any?> = emptyMap(),
    ): WearableConnectedDeviceProfile {
        val scanCandidate = scan(id)
        return WearableConnectedDeviceProfile(
            scan = scanCandidate,
            discoveredServices = services,
            discoveredCharacteristics = chars,
            firmwareVersion = null,
            deviceName = null,
            handshakeProbes = probes,
        )
    }

    // ── probableMatch ──

    @Test
    fun probableMatch_trueForLegacyUuid() {
        assertTrue(buildAdapter().probableMatch(scan(uuids = setOf(legacyServiceUuid))))
    }

    @Test
    fun probableMatch_trueForSdkUuidWithGlassesManufacturerHint() {
        // manufacturer data byte[2] = 4 (GLASSES)
        val mfg = mapOf(0x02FE to byteArrayOf(0x00, 0x01, 4))
        assertTrue(buildAdapter().probableMatch(scan(uuids = setOf(sdkServiceUuid), mfgData = mfg)))
    }

    @Test
    fun probableMatch_trueForSdkUuidWithGlassesServiceDataHint() {
        val serviceData = mapOf(sdkServiceDataUuid to byteArrayOf(4))
        assertTrue(buildAdapter().probableMatch(scan(uuids = setOf(sdkServiceUuid), serviceData = serviceData)))
    }

    @Test
    fun probableMatch_falseForSdkUuidWithoutSdkTypeHint() {
        assertFalse(buildAdapter().probableMatch(scan(uuids = setOf(sdkServiceUuid))))
    }

    @Test
    fun probableMatch_falseForUnknownUuids() {
        val unknown = UUID.fromString("ffffffff-0000-0000-0000-000000000000")
        assertFalse(buildAdapter().probableMatch(scan(uuids = setOf(unknown))))
    }

    @Test
    fun probableMatch_falseForSdkUuidWithSoleManufacturerType() {
        // manufacturer data byte[2] = 0 (LEFT_INSOLE)
        val mfg = mapOf(0x02FE to byteArrayOf(0x00, 0x01, 0))
        assertFalse(buildAdapter().probableMatch(scan(uuids = setOf(sdkServiceUuid), mfgData = mfg)))
    }

    // ── confirmMatch ──

    @Test
    fun confirmMatch_trueWhenPhotoDataCharPresent() {
        assertTrue(buildAdapter().confirmMatch(profile(chars = setOf(photoDataUuid))))
    }

    @Test
    fun confirmMatch_trueWhenSdkCharsAndGlassesProbe() {
        assertTrue(
            buildAdapter().confirmMatch(
                profile(
                    chars = setOf(sdkRxUuid, sdkTxUuid),
                    probes = mapOf("sdkDeviceType" to 4),
                ),
            ),
        )
    }

    @Test
    fun confirmMatch_falseWhenSdkCharsAndSoleProbe() {
        assertFalse(
            buildAdapter().confirmMatch(
                profile(
                    chars = setOf(sdkRxUuid, sdkTxUuid),
                    probes = mapOf("sdkDeviceType" to 0), // LEFT_INSOLE
                ),
            ),
        )
    }

    @Test
    fun confirmMatch_falseWhenSdkCharsWithoutProbe() {
        assertFalse(buildAdapter().confirmMatch(profile(chars = setOf(sdkRxUuid, sdkTxUuid))))
    }

    @Test
    fun confirmMatch_falseWhenNoRelevantChars() {
        assertFalse(buildAdapter().confirmMatch(profile(chars = emptySet())))
    }

    // ── pool de clients ──

    @Test
    fun createSession_sameIdReusesClient() = runTest {
        val adapter = buildAdapter()
        val p = profile(chars = setOf(photoDataUuid, batteryUuid, firmwareUuid))
        val s1 = adapter.createSession(p, TestScope())
        val s2 = adapter.createSession(p, TestScope())
        assertEquals(1, fakeClients.size)
    }

    @Test
    fun createSession_differentIdsCreateDifferentClients() = runTest {
        val adapter = buildAdapter()
        val p1 = profile(id = "AA:11:22:33:44:55", chars = setOf(photoDataUuid))
        val p2 = profile(id = "BB:11:22:33:44:55", chars = setOf(photoDataUuid))
        adapter.createSession(p1, TestScope())
        adapter.createSession(p2, TestScope())
        assertEquals(2, fakeClients.size)
    }

    // ── capabilities ──

    @Test
    fun connectAndCreateSession_rejectsScanWithoutRawDevice() = runTest {
        val adapter = buildAdapter()
        val result = adapter.connectAndCreateSession(
            scan(uuids = setOf(legacyServiceUuid)),
            TestScope(),
        )

        assertTrue(result.isFailure)
        assertEquals(0, fakeClients.size)
    }

    @Test
    fun capabilities_imageCapture_presentWhenPhotoControlDiscovered() = runTest {
        val photoControlUuid = UUID.fromString("19B10006-E8F2-537E-4F6C-D104768A1214")
        val adapter = buildAdapter()
        val session = adapter.createSession(profile(chars = setOf(photoControlUuid)), TestScope())
        assertTrue(WearableCapability.IMAGE_CAPTURE in session.capabilities)
    }

    @Test
    fun capabilities_onDeviceMl_neverPresentForCurrentOmi() = runTest {
        val adapter = buildAdapter()
        val session = adapter.createSession(
            profile(chars = setOf(photoDataUuid, sdkRxUuid, sdkTxUuid, batteryUuid)),
            TestScope(),
        )
        assertFalse(WearableCapability.ON_DEVICE_ML in session.capabilities)
    }
}
