package com.wearmux.android.integration.hub

import com.wearmux.android.integration.adapters.CommandResult
import com.wearmux.android.integration.adapters.WearableCommand
import com.wearmux.android.integration.adapters.WearableId
import com.wearmux.android.integration.adapters.WearableScanCandidate
import com.wearmux.android.integration.adapters.WearableSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Implementação default do [DeviceHub]. Delega o fluxo de conexão a um
 * [DeviceConnectionCoordinator] (testável com fake) e mantém o mapa de sessões
 * ativas indexado por [WearableId].
 *
 * Garantias:
 * - Uma chamada [connectByCandidate] para um id já presente devolve a sessão
 *   existente (evita double-connect a partir do hub; o adapter também faz
 *   verificação no seu pool, defesa em camadas).
 * - [disconnect] fecha a sessão e remove do mapa antes de retornar.
 * - [send] devolve [CommandResult.Rejected] se o id não estiver no mapa.
 */
class DefaultDeviceHub(
    private val coordinator: DeviceConnectionCoordinator,
    private val scope: CoroutineScope,
) : DeviceHub {

    private val _sessions = MutableStateFlow<Map<WearableId, WearableSession>>(emptyMap())
    override val sessions: StateFlow<Map<WearableId, WearableSession>> = _sessions.asStateFlow()

    private val mutex = Mutex()

    override suspend fun connectByCandidate(scan: WearableScanCandidate): WearableSession? =
        mutex.withLock {
            _sessions.value[scan.id]?.let { return@withLock it }

            val result = coordinator.coordinate(scan, scope)
            val session = result.getOrNull() ?: return@withLock null

            _sessions.update { it + (session.id to session) }
            session
        }

    override suspend fun disconnect(id: WearableId) {
        val session = mutex.withLock {
            val s = _sessions.value[id] ?: return
            _sessions.update { it - id }
            s
        }
        session.close()
    }

    override suspend fun send(id: WearableId, command: WearableCommand): CommandResult {
        val session = _sessions.value[id]
            ?: return CommandResult.Rejected("no session for id=$id")
        return session.send(command)
    }
}
