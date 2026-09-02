package com.example.peciwearables.integration.wearable.devices.brilliantsole

import com.example.peciwearables.integration.ble.devices.brilliantsole.BrilliantSoleWristbandBleClientApi
import com.example.peciwearables.integration.ble.devices.brilliantsole.BrilliantSoleWristbandClientFactory
import com.example.peciwearables.integration.ble.SDK_DEVICE_TYPE_GLASSES_RAW
import com.example.peciwearables.integration.ble.SDK_SERVICE_DATA_UUID
import com.example.peciwearables.integration.ble.sdkDeviceTypeHintFromServiceData
import com.example.peciwearables.integration.ble.sdkDeviceTypeHintsFromManufacturerPayloads
import com.example.peciwearables.integration.wearable.GattProfileObserver
import com.example.peciwearables.integration.wearable.WearableAdapter
import com.example.peciwearables.integration.wearable.WearableConnectedDeviceProfile
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableRealtimeSink
import com.example.peciwearables.integration.wearable.WearableScanCandidate
import com.example.peciwearables.integration.wearable.WearableSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BrilliantSoleWristbandWearableAdapter(
    private val clientFactory: BrilliantSoleWristbandClientFactory,
    private val realtimeSink: WearableRealtimeSink = WearableRealtimeSink.NONE,
) : WearableAdapter {

    override val id: String = ADAPTER_ID

    private val clientPool = ConcurrentHashMap<WearableId, BrilliantSoleWristbandBleClientApi>()

    companion object {
        const val ADAPTER_ID = "brilliant-sole-wristband"
        private const val PROFILE_TIMEOUT_MS = 15_000L

        private val BS_SERVICE_UUID = UUID.fromString("ea6d0000-a725-4f9b-893d-c3913e33b39f")
        private val BS_RX_UUID = UUID.fromString("ea6d1000-a725-4f9b-893d-c3913e33b39f")
        private val BS_TX_UUID = UUID.fromString("ea6d1001-a725-4f9b-893d-c3913e33b39f")

        // sdkDeviceType=4 é Glasses (Omi) — excluímos esse valor
        private const val SDK_DEVICE_TYPE_GLASSES = SDK_DEVICE_TYPE_GLASSES_RAW
    }

    override fun probableMatch(scan: WearableScanCandidate): Boolean {
        if (BS_SERVICE_UUID !in scan.advertisedServiceUuids) return false
        // Se sdkDeviceType=GLASSES, não é Sole
        if (SDK_DEVICE_TYPE_GLASSES in scan.sdkDeviceTypeHints()) return false
        return true
    }

    override fun confirmMatch(profile: WearableConnectedDeviceProfile): Boolean {
        val chars = profile.discoveredCharacteristics
        if (BS_RX_UUID !in chars || BS_TX_UUID !in chars) return false
        // Se probe confirmou que é Omi (sdkDeviceType=4), rejeita
        val probeType = profile.handshakeProbes["sdkDeviceType"] as? Int
        if (probeType == SDK_DEVICE_TYPE_GLASSES) return false
        return true
    }

    override suspend fun createSession(
        profile: WearableConnectedDeviceProfile,
        scope: CoroutineScope,
    ): WearableSession {
        val client = clientPool.computeIfAbsent(profile.id) { clientFactory.create(it) }
        return BrilliantSoleWristbandWearableSession(
            id = profile.id,
            profile = profile,
            client = client,
            scope = scope,
            realtimeSink = realtimeSink,
        )
    }

    override suspend fun connectAndCreateSession(
        scan: WearableScanCandidate,
        scope: CoroutineScope,
    ): Result<WearableSession> {
        val raw = scan.raw
            ?: return Result.failure(IllegalArgumentException("scan.raw is required to connect ${scan.id.raw}"))

        val client = getOrCreateClient(scan.id)
        client.connectDevice(raw)

        val snapshot = withTimeoutOrNull(PROFILE_TIMEOUT_MS) {
            client.gattProfile.filterNotNull().first()
        } ?: run {
            client.disconnect()
            releaseClient(scan.id)
            return Result.failure(IllegalStateException("Timed out waiting for Brilliant Sole GATT profile for ${scan.id.raw}"))
        }

        val profile = GattProfileObserver.connectedProfile(scan, snapshot)
        if (!confirmMatch(profile)) {
            client.disconnect()
            releaseClient(scan.id)
            return Result.failure(IllegalStateException("Brilliant Sole profile did not confirm match for ${scan.id.raw}"))
        }

        return Result.success(createSession(profile, scope))
    }

    fun getOrCreateClient(id: WearableId): BrilliantSoleWristbandBleClientApi =
        clientPool.computeIfAbsent(id) { clientFactory.create(it) }

    fun releaseClient(id: WearableId) {
        clientPool.remove(id)
    }

    private fun WearableScanCandidate.sdkDeviceTypeHints(): List<Int> {
        return sdkDeviceTypeHintsFromManufacturerPayloads(manufacturerData.values) +
            listOfNotNull(sdkDeviceTypeHintFromServiceData(serviceData[SDK_SERVICE_DATA_UUID]))
    }
}
