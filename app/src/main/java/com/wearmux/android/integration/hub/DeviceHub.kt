package com.wearmux.android.integration.hub

import com.wearmux.android.integration.adapters.CommandResult
import com.wearmux.android.integration.adapters.WearableCommand
import com.wearmux.android.integration.adapters.WearableId
import com.wearmux.android.integration.adapters.WearableScanCandidate
import com.wearmux.android.integration.adapters.WearableSession
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
