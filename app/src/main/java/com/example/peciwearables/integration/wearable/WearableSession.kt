package com.example.peciwearables.integration.wearable

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sessão genérica de um wearable conectado. Adapters concretos implementam esta
 * interface; a UI/pipelines consomem apenas o contrato genérico.
 *
 * As [capabilities] são calculadas pelo adapter **após** o handshake — não são
 * declaradas por marca, mas pelo que o protocolo realmente expõe naquela sessão.
 */
interface WearableSession {
    val adapterId: String
    val id: WearableId
    val capabilities: Set<WearableCapability>
    val state: StateFlow<WearableConnectionState>
    val events: SharedFlow<WearableEvent>
    suspend fun send(command: WearableCommand): CommandResult
    suspend fun close()
}
