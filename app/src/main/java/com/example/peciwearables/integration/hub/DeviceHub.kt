package com.example.peciwearables.integration.hub

import com.example.peciwearables.integration.adapters.CommandResult
import com.example.peciwearables.integration.adapters.WearableCommand
import com.example.peciwearables.integration.adapters.WearableId
import com.example.peciwearables.integration.adapters.WearableScanCandidate
import com.example.peciwearables.integration.adapters.WearableSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Dono lógico das sessões de wearables ativas. Único ponto de entrada para a UI
 * iniciar conexões e enviar comandos.
 *
 * Suporta múltiplas sessões em simultâneo (mapa indexado por [WearableId]).
 */
interface DeviceHub {
    val sessions: StateFlow<Map<WearableId, WearableSession>>

    suspend fun connectByCandidate(scan: WearableScanCandidate): WearableSession?

    suspend fun disconnect(id: WearableId)

    suspend fun send(id: WearableId, command: WearableCommand): CommandResult
}
