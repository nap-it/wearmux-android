package com.example.peciwearables.integration.wearable.devices.galaxywatch

import com.example.peciwearables.integration.watch.WatchClient
import com.example.peciwearables.integration.wearable.WearableAdapter
import com.example.peciwearables.integration.wearable.WearableConnectedDeviceProfile
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableScanCandidate
import com.example.peciwearables.integration.wearable.WearableSession
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * Adapter para Galaxy Watch (Wear OS) ligado por Wear DataLayer (Bluetooth +
 * Google Play Services), **não** por BLE genérico.
 *
 * Este adapter foge ligeiramente do padrão dos restantes:
 *  - O dispositivo não é descoberto por BLE scan tradicional. Quem cria o
 *    [WearableScanCandidate] é o próprio app — ao detectar um nó com a
 *    capability `peci_watch_app` via [WatchClient], constrói um candidate
 *    sintético e chama [WearableHub.connectByCandidate].
 *  - `connectAndCreateSession` não faz `connectGatt` — limita-se a iniciar o
 *    [WatchClient] e devolver a sessão. O transport real é Wear DataLayer.
 *
 * Capabilities declaradas: IMU_STREAM, BATTERY, HAPTIC, DEVICE_INFO. O watch
 * **não** tem IMAGE_CAPTURE, VIDEO_STREAM, AUDIO_STREAM (no sentido sink) ou
 * WIFI_HANDOFF — esses ficam por declarar.
 */
class GalaxyWatchWearableAdapter(
    private val watchClient: WatchClient,
) : WearableAdapter {

    override val id: String = ADAPTER_ID

    companion object {
        const val ADAPTER_ID = "galaxy-watch"

        /** UUID sintético usado nos `serviceData` do candidate sintético para
         *  marcar este como sendo do Galaxy Watch (apenas marker — não é um
         *  serviço BLE real). */
        val MARKER_UUID: UUID = UUID.fromString("00000000-7eca-1000-0000-000000000000")

        /** ID estável — o sistema só vê um watch nearby de cada vez via Wear. */
        val STABLE_ID = WearableId("galaxy-watch")

        /**
         * Constrói um candidate sintético para o watch. Usado pelo bridge
         * que detecta o nó via [WatchClient.watchNodeName] e chama
         * `WearableHub.connectByCandidate(...)`.
         */
        fun syntheticCandidate(displayName: String): WearableScanCandidate =
            WearableScanCandidate(
                id = STABLE_ID,
                displayName = displayName,
                rssi = 0,
                advertisedServiceUuids = setOf(MARKER_UUID),
                manufacturerData = emptyMap(),
                serviceData = mapOf(MARKER_UUID to ByteArray(0)),
                raw = null,
            )
    }

    override fun probableMatch(scan: WearableScanCandidate): Boolean =
        MARKER_UUID in scan.advertisedServiceUuids

    override fun confirmMatch(profile: WearableConnectedDeviceProfile): Boolean =
        MARKER_UUID in profile.scan.advertisedServiceUuids

    override suspend fun connectAndCreateSession(
        scan: WearableScanCandidate,
        scope: CoroutineScope,
    ): Result<WearableSession> {
        return try {
            // O WatchClient é um singleton já gerido pelo WearableService —
            // só garantimos que está inicializado.
            val profile = WearableConnectedDeviceProfile(
                scan = scan,
                discoveredServices = emptySet(),
                discoveredCharacteristics = emptySet(),
                firmwareVersion = null,
                deviceName = scan.displayName,
            )
            Result.success(createSession(profile, scope))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun createSession(
        profile: WearableConnectedDeviceProfile,
        scope: CoroutineScope,
    ): WearableSession = GalaxyWatchWearableSession(
        id = profile.id,
        displayName = profile.deviceName ?: "Galaxy Watch",
        client = watchClient,
        scope = scope,
    )
}
