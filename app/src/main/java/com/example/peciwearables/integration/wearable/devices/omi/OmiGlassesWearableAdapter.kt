package com.example.peciwearables.integration.wearable.devices.omi

import com.example.peciwearables.integration.ble.devices.omi.OmiGlassesBleClientApi
import com.example.peciwearables.integration.ble.devices.omi.OmiGlassesClientFactory
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

class OmiGlassesWearableAdapter(
    private val clientFactory: OmiGlassesClientFactory,
    private val realtimeSink: WearableRealtimeSink = WearableRealtimeSink.NONE,
) : WearableAdapter {

    override val id: String = ADAPTER_ID

    // Pool de clients por WearableId — garante uma só conexão BLE por device
    private val clientPool = ConcurrentHashMap<WearableId, OmiGlassesBleClientApi>()

    companion object {
        const val ADAPTER_ID = "omi-glasses"
        private const val PROFILE_TIMEOUT_MS = 15_000L

        // UUID firmware legado Omi
        private val LEGACY_SERVICE_UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
        // UUID firmware SDK (partilhado com Brilliant Sole)
        private val SDK_SERVICE_UUID = UUID.fromString("ea6d0000-a725-4f9b-893d-c3913e33b39f")

        // Characteristics que confirmam ser Omi (firmware legado)
        private val PHOTO_DATA_UUID = UUID.fromString("19B10005-E8F2-537E-4F6C-D104768A1214")
        private val AUDIO_DATA_UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
        // Characteristics SDK Omi
        private val SDK_RX_UUID = UUID.fromString("ea6d1000-a725-4f9b-893d-c3913e33b39f")
        private val SDK_TX_UUID = UUID.fromString("ea6d1001-a725-4f9b-893d-c3913e33b39f")

    }

    override fun probableMatch(scan: WearableScanCandidate): Boolean {
        val uuids = scan.advertisedServiceUuids
        if (LEGACY_SERVICE_UUID in uuids) return true

        val looksLikeGlasses = looksLikeGlassesName(scan.displayName)
        if (SDK_SERVICE_UUID in uuids) {
            val hints = scan.sdkDeviceTypeHints()
            if (SDK_DEVICE_TYPE_GLASSES_RAW in hints) return true
            if (hints.isNotEmpty()) return false
            // UUID SDK é partilhado com Sole; sem hint explícito, usar o nome.
            return looksLikeGlasses
        }

        // Fallback para firmwares/advertising que não expõem UUID esperado.
        return looksLikeGlasses
    }

    override fun confirmMatch(profile: WearableConnectedDeviceProfile): Boolean {
        val services = profile.discoveredServices
        val chars = profile.discoveredCharacteristics
        val displayName = profile.deviceName ?: profile.scan.displayName
        // Firmware legado: tem PHOTO_DATA ou AUDIO_DATA
        if (PHOTO_DATA_UUID in chars || AUDIO_DATA_UUID in chars) return true
        // Firmware SDK: tem RX+TX E handshake probe confirma Omi (não Sole)
        if (SDK_RX_UUID in chars && SDK_TX_UUID in chars) {
            // Se probe retornar sdkDeviceType=GLASSES, é Omi
            val probeType = profile.handshakeProbes["sdkDeviceType"] as? Int
            if (probeType == SDK_DEVICE_TYPE_GLASSES_RAW) return true
            // Alguns firmwares podem não publicar probe; usar hint do nome.
            return probeType == null && looksLikeGlassesName(displayName)
        }

        // Último fallback: nome + qualquer service legado conhecido.
        if (looksLikeGlassesName(displayName) && LEGACY_SERVICE_UUID in services) return true
        return false
    }

    private fun looksLikeGlassesName(name: String?): Boolean {
        val lower = name?.lowercase() ?: return false
        return "omi" in lower || "glass" in lower || "frame" in lower || "g1" in lower
    }

    override suspend fun createSession(
        profile: WearableConnectedDeviceProfile,
        scope: CoroutineScope,
    ): WearableSession {
        val client = clientPool.computeIfAbsent(profile.id) {
            clientFactory.create(it)
        }
        return OmiGlassesWearableSession(
            id = profile.id,
            profile = profile,
            client = client,
            scope = scope,
            realtimeSink = realtimeSink,
        )
    }

    /** Expõe o client para uso legacy no WearableService (evita double-connect). */
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
            return Result.failure(IllegalStateException("Timed out waiting for Omi GATT profile for ${scan.id.raw}"))
        }

        val profile = GattProfileObserver.connectedProfile(scan, snapshot)
        if (!confirmMatch(profile)) {
            client.disconnect()
            releaseClient(scan.id)
            return Result.failure(IllegalStateException("Omi profile did not confirm match for ${scan.id.raw}"))
        }

        return Result.success(createSession(profile, scope))
    }

    fun getOrCreateClient(id: WearableId): OmiGlassesBleClientApi =
        clientPool.computeIfAbsent(id) { clientFactory.create(it) }

    fun releaseClient(id: WearableId) {
        clientPool.remove(id)
    }

    private fun WearableScanCandidate.sdkDeviceTypeHints(): List<Int> {
        return sdkDeviceTypeHintsFromManufacturerPayloads(manufacturerData.values) +
            listOfNotNull(sdkDeviceTypeHintFromServiceData(serviceData[SDK_SERVICE_DATA_UUID]))
    }
}
