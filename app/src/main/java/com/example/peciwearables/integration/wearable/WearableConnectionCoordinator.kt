package com.example.peciwearables.integration.wearable

import kotlinx.coroutines.CoroutineScope

/**
 * Orquestra o fluxo `scan → probable → connectGatt → discoverServices → confirm
 * → createSession` para um candidate.
 *
 * Extraído da [WearableHub] para que a lógica BLE-real possa ser substituída por
 * um fake em testes.
 */
interface WearableConnectionCoordinator {
    suspend fun coordinate(
        scan: WearableScanCandidate,
        scope: CoroutineScope,
    ): Result<WearableSession>
}
