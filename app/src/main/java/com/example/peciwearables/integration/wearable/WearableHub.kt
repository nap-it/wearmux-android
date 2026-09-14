package com.example.peciwearables.integration.wearable

import kotlinx.coroutines.flow.StateFlow

/**
 * Dono lógico das sessões de wearables ativas. Único ponto de entrada para a UI
 * iniciar conexões e enviar comandos.
 *
 * Suporta múltiplas sessões em simultâneo (mapa indexado por [WearableId]).
 */
interface WearableHub {
    val sessions: StateFlow<Map<WearableId, WearableSession>>

    suspend fun connectByCandidate(scan: WearableScanCandidate): WearableSession?

    suspend fun disconnect(id: WearableId)

    suspend fun send(id: WearableId, command: WearableCommand): CommandResult
}
