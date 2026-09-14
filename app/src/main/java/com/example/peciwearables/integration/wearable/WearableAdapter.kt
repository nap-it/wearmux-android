package com.example.peciwearables.integration.wearable

import kotlinx.coroutines.CoroutineScope

/**
 * Tradutor entre o protocolo concreto de um wearable e o contrato genérico.
 *
 * O adapter decide em duas fases:
 * 1. [probableMatch]: lê advertising data — barato, sem ligar.
 * 2. [confirmMatch]: lê services/characteristics realmente descobertos e/ou
 *    o resultado de probes feitos durante o handshake. Só este resultado é
 *    determinante (advertising data pode estar truncada/ambígua — UUID `ea6d…`
 *    é partilhado entre dois protocolos SDK).
 *
 * O adapter mantém **internamente** o pool de clients indexado por [WearableId]
 * — garante uma só conexão BLE real por dispositivo.
 */
interface WearableAdapter {
    val id: String

    fun probableMatch(scan: WearableScanCandidate): Boolean

    fun confirmMatch(profile: WearableConnectedDeviceProfile): Boolean

    suspend fun connectAndCreateSession(
        scan: WearableScanCandidate,
        scope: CoroutineScope,
    ): Result<WearableSession> = Result.failure(
        UnsupportedOperationException("Adapter $id does not implement BLE connection orchestration")
    )

    suspend fun createSession(
        profile: WearableConnectedDeviceProfile,
        scope: CoroutineScope,
    ): WearableSession
}
