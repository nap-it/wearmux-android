package com.example.peciwearables.integration.hub

import com.example.peciwearables.integration.adapters.WearableId
import com.example.peciwearables.integration.adapters.WearableScanCandidate
import com.example.peciwearables.integration.adapters.WearableSession
import kotlinx.coroutines.CoroutineScope

/**
 * Coordinator que devolve uma sessão pré-construída (ou erro) consoante o
 * [WearableId] do candidate. Permite testar o [DefaultDeviceHub] sem BLE real.
 */
class FakeDeviceConnectionCoordinator(
    private val sessionsById: MutableMap<WearableId, WearableSession> = mutableMapOf(),
    private val errorsById: MutableMap<WearableId, Throwable> = mutableMapOf(),
) : DeviceConnectionCoordinator {

    val coordinateCalls = mutableListOf<WearableScanCandidate>()

    fun register(id: WearableId, session: WearableSession) {
        sessionsById[id] = session
    }

    fun fail(id: WearableId, throwable: Throwable) {
        errorsById[id] = throwable
    }

    override suspend fun coordinate(
        scan: WearableScanCandidate,
        scope: CoroutineScope,
    ): Result<WearableSession> {
        coordinateCalls += scan
        errorsById[scan.id]?.let { return Result.failure(it) }
        val session = sessionsById[scan.id]
            ?: return Result.failure(IllegalStateException("no fake session for ${scan.id}"))
        return Result.success(session)
    }
}
