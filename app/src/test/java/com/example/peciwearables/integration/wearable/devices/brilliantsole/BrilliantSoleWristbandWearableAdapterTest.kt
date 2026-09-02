package com.example.peciwearables.integration.wearable.devices.brilliantsole

import com.example.peciwearables.integration.wearable.WearableCapability
import com.example.peciwearables.integration.wearable.WearableConnectedDeviceProfile
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableScanCandidate
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BrilliantSoleWristbandWearableAdapterTest {

    private val bsServiceUuid = UUID.fromString("ea6d0000-a725-4f9b-893d-c3913e33b39f")
    private val sdkServiceDataUuid = UUID.fromString("00000000-0000-1000-8000-00805f9b34fb")
    private val bsRxUuid = UUID.fromString("ea6d1000-a725-4f9b-893d-c3913e33b39f")
    private val bsTxUuid = UUID.fromString("ea6d1001-a725-4f9b-893d-c3913e33b39f")
    private val batteryUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private val fakeClients = mutableMapOf<WearableId, FakeBrilliantSoleWristbandBleClient>()

    private fun buildAdapter() = BrilliantSoleWristbandWearableAdapter(
        clientFactory = { id -> FakeBrilliantSoleWristbandBleClient().also { fakeClients[id] = it } },
    )

    private fun scan(
        id: String = "BB:11:22:33:44:55",
        uuids: Set<UUID> = emptySet(),
        mfgData: Map<Int, ByteArray> = emptyMap(),
        serviceData: Map<UUID, ByteArray> = emptyMap(),
    ) = WearableScanCandidate(
        id = WearableId(id), displayName = "Sole", rssi = -60,
        advertisedServiceUuids = uuids, manufacturerData = mfgData,
        serviceData = serviceData, raw = null,
    )

    private fun profile(
        id: String = "BB:11:22:33:44:55",
        chars: Set<UUID> = emptySet(),
        probes: Map<String, Any?> = emptyMap(),
    ) = WearableConnectedDeviceProfile(
        scan = scan(id), discoveredServices = emptySet(),
        discoveredCharacteristics = chars,
        firmwareVersion = null, deviceName = null,
        handshakeProbes = probes,
    )

    // ── probableMatch ──

    @Test
    fun probableMatch_trueForBsUuidWithoutGlassesType() {
        assertTrue(buildAdapter().probableMatch(scan(uuids = setOf(bsServiceUuid))))
    }

    @Test
    fun probableMatch_falseWhenBsUuidWithGlassesType() {
        val mfg = mapOf(0x02FE to byteArrayOf(0x00, 0x01, 4)) // sdkDeviceType=4=GLASSES
        assertFalse(buildAdapter().probableMatch(scan(uuids = setOf(bsServiceUuid), mfgData = mfg)))
    }

    @Test
    fun probableMatch_falseWhenBsUuidWithGlassesServiceDataType() {
        val serviceData = mapOf(sdkServiceDataUuid to byteArrayOf(4))
        assertFalse(buildAdapter().probableMatch(scan(uuids = setOf(bsServiceUuid), serviceData = serviceData)))
    }

    @Test
    fun probableMatch_falseWithoutBsUuid() {
        val unknown = UUID.fromString("ffffffff-0000-0000-0000-000000000000")
        assertFalse(buildAdapter().probableMatch(scan(uuids = setOf(unknown))))
    }

    // ── confirmMatch ──

    @Test
    fun confirmMatch_trueWithRxTxChars() {
        assertTrue(buildAdapter().confirmMatch(profile(chars = setOf(bsRxUuid, bsTxUuid))))
    }

    @Test
    fun confirmMatch_falseWhenGlassesProbe() {
        assertFalse(
            buildAdapter().confirmMatch(
                profile(
                    chars = setOf(bsRxUuid, bsTxUuid),
                    probes = mapOf("sdkDeviceType" to 4),
                ),
            ),
        )
    }

    @Test
    fun confirmMatch_falseWithoutChars() {
        assertFalse(buildAdapter().confirmMatch(profile(chars = emptySet())))
    }

    // ── capabilities ──

    @Test
    fun connectAndCreateSession_rejectsScanWithoutRawDevice() = runTest {
        val result = buildAdapter().connectAndCreateSession(
            scan(uuids = setOf(bsServiceUuid)),
            TestScope(),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun capabilities_imuStreamAlwaysPresent() = runTest {
        val session = buildAdapter().createSession(
            profile(chars = setOf(bsRxUuid, bsTxUuid)),
            TestScope(),
        )
        assertTrue(WearableCapability.IMU_STREAM in session.capabilities)
    }

    @Test
    fun capabilities_hapticFromProbe() = runTest {
        val session = buildAdapter().createSession(
            profile(chars = setOf(bsRxUuid, bsTxUuid), probes = mapOf("hapticSupported" to true)),
            TestScope(),
        )
        assertTrue(WearableCapability.HAPTIC in session.capabilities)
    }

    @Test
    fun capabilities_onDeviceMlFromProbe() = runTest {
        val session = buildAdapter().createSession(
            profile(chars = setOf(bsRxUuid, bsTxUuid), probes = mapOf("tfliteReady" to true)),
            TestScope(),
        )
        assertTrue(WearableCapability.ON_DEVICE_ML in session.capabilities)
    }
}
