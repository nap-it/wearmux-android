package com.wearmux.android.integration.adapters.legacy

import com.wearmux.android.integration.adapters.WearableEvent
import com.wearmux.android.integration.adapters.WearableId
import com.wearmux.android.integration.adapters.deviceProfile
import com.wearmux.android.integration.adapters.devices.brilliantsole.BrilliantSoleWristbandWearableSession
import com.wearmux.android.integration.adapters.devices.brilliantsole.FakeBrilliantSoleWristbandBleClient
import com.wearmux.android.integration.adapters.devices.omi.FakeOmiGlassesBleClient
import com.wearmux.android.integration.adapters.devices.omi.OmiGlassesWearableSession
import com.wearmux.android.integration.adapters.scanCandidate
import com.wearmux.android.integration.hub.DeviceHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O canal genérico de eventos tem de publicar **por dispositivo**: sem o id,
 * o IMU de duas pulseiras seria indistinguível.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearableServiceDeviceEventsTest {

    private val wristbandId = WearableId("BB:11:22:33:44:55")
    private val secondWristbandId = WearableId("CC:11:22:33:44:55")

    @Test
    fun deviceEvent_carriesTheIdentityOfTheEmittingSession() = runTest {
        val hub = FakeWearableHub()
        val (session, client) = makeSoleSession(scope = backgroundScope, id = wristbandId)

        val seen = mutableListOf<Pair<WearableId, WearableEvent>>()
        val bridge = WearableServiceLegacyBridge(
            hub = hub,
            scope = this,
            callbacks = WearableServiceLegacyBridge.Callbacks(
                onDeviceEvent = { id, event -> seen += id to event },
            ),
        )

        bridge.start()
        hub.setSessions(session)
        advanceUntilIdle()

        // A sessão emite eventos próprio (IMU/inferência) — republish com o id.
        client.simulateBattery(42)
        advanceUntilIdle()

        assertTrue("a sessão deve ter emitido eventos", seen.isNotEmpty())
        assertTrue(
            "todos os eventos devem pertencer à sessão que os emitiu",
            seen.all { it.first == wristbandId },
        )
        bridge.stop()
    }

    @Test
    fun twoWristbands_emitEventsUnderTheirOwnIds() = runTest {
        val hub = FakeWearableHub()
        val (sessionA, clientA) = makeSoleSession(scope = backgroundScope, id = wristbandId)
        val (sessionB, clientB) = makeSoleSession(scope = backgroundScope, id = secondWristbandId)

        val seenIds = mutableListOf<WearableId>()
        val bridge = WearableServiceLegacyBridge(
            hub = hub,
            scope = this,
            callbacks = WearableServiceLegacyBridge.Callbacks(
                onDeviceEvent = { id, _ -> seenIds += id },
            ),
        )

        bridge.start()
        hub.setSessions(sessionA, sessionB)
        advanceUntilIdle()

        clientA.simulateBattery(10)
        clientB.simulateBattery(20)
        advanceUntilIdle()

        assertTrue("a pulseira A deve reportar eventos", seenIds.contains(wristbandId))
        assertTrue("a pulseira B deve reportar eventos", seenIds.contains(secondWristbandId))
        bridge.stop()
    }

    @Test
    fun sessionRemoved_stopsPublishingItsEvents() = runTest {
        val hub = FakeWearableHub()
        val (session, client) = makeSoleSession(scope = backgroundScope, id = wristbandId)

        var count = 0
        val bridge = WearableServiceLegacyBridge(
            hub = hub,
            scope = this,
            callbacks = WearableServiceLegacyBridge.Callbacks(
                onDeviceEvent = { _, _ -> count++ },
            ),
        )

        bridge.start()
        hub.setSessions(session)
        advanceUntilIdle()
        client.simulateBattery(1)
        advanceUntilIdle()
        val before = count

        hub.clearSessions()
        advanceUntilIdle()
        client.simulateBattery(2)
        advanceUntilIdle()

        assertEquals(before, count)
        bridge.stop()
    }

    private fun makeSoleSession(
        scope: CoroutineScope,
        id: WearableId,
    ): Pair<BrilliantSoleWristbandWearableSession, FakeBrilliantSoleWristbandBleClient> {
        val client = FakeBrilliantSoleWristbandBleClient()
        val session = BrilliantSoleWristbandWearableSession(
            id = id,
            profile = deviceProfile(scan = scanCandidate(id = id.raw, name = "BrilliantSole")),
            client = client,
            scope = scope,
        )
        return session to client
    }

    private class FakeWearableHub : DeviceHub {
        private val _sessions = MutableStateFlow<Map<WearableId, com.wearmux.android.integration.adapters.WearableSession>>(emptyMap())
        override val sessions: StateFlow<Map<WearableId, com.wearmux.android.integration.adapters.WearableSession>> = _sessions

        fun setSessions(vararg sessions: com.wearmux.android.integration.adapters.WearableSession) {
            _sessions.value = sessions.associateBy { it.id }
        }

        fun clearSessions() {
            _sessions.value = emptyMap()
        }

        override suspend fun connectByCandidate(scan: com.wearmux.android.integration.adapters.WearableScanCandidate) = null

        override suspend fun disconnect(id: WearableId) {
            _sessions.value = _sessions.value - id
        }

        override suspend fun send(id: WearableId, command: com.wearmux.android.integration.adapters.WearableCommand) =
            com.wearmux.android.integration.adapters.CommandResult.Rejected("No active session for ${id.raw}")
    }
}
