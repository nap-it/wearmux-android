package com.wearmux.android.integration.hub

import com.wearmux.android.integration.adapters.WearableScanCandidate
import com.wearmux.android.integration.adapters.WearableSession
import kotlinx.coroutines.CoroutineScope

/**
 * Orquestra o fluxo `scan → probable → connectGatt → discoverServices → confirm
 * → createSession` para um candidate.
 *
 * Extraído da [DeviceHub] para que a lógica BLE-real possa ser substituída por
 * um fake em testes.
 */
interface DeviceConnectionCoordinator {
    suspend fun coordinate(
        scan: WearableScanCandidate,
        scope: CoroutineScope,
    ): Result<WearableSession>
}
