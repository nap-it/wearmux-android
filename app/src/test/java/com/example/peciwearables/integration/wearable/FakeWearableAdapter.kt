package com.example.peciwearables.integration.wearable

import kotlinx.coroutines.CoroutineScope

/**
 * Adapter controlável por testes. As respostas de [probableMatch]/[confirmMatch]
 * são funções para os testes poderem decidir caso-a-caso.
 */
class FakeWearableAdapter(
    override val id: String,
    private val probable: (WearableScanCandidate) -> Boolean = { false },
    private val confirm: (WearableConnectedDeviceProfile) -> Boolean = { false },
    private val sessionFactory: (WearableConnectedDeviceProfile) -> WearableSession = { profile ->
        FakeWearableSession(profile.id)
    },
    private val connectResult: (WearableScanCandidate, CoroutineScope) -> Result<WearableSession> = { scan, _ ->
        Result.success(FakeWearableSession(scan.id))
    },
) : WearableAdapter {

    val createdSessions = mutableListOf<WearableSession>()
    val connectCalls = mutableListOf<WearableScanCandidate>()

    override fun probableMatch(scan: WearableScanCandidate): Boolean = probable(scan)

    override fun confirmMatch(profile: WearableConnectedDeviceProfile): Boolean = confirm(profile)

    override suspend fun createSession(
        profile: WearableConnectedDeviceProfile,
        scope: CoroutineScope,
    ): WearableSession = sessionFactory(profile).also { createdSessions += it }

    override suspend fun connectAndCreateSession(
        scan: WearableScanCandidate,
        scope: CoroutineScope,
    ): Result<WearableSession> {
        connectCalls += scan
        return connectResult(scan, scope)
    }
}
