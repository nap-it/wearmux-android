package com.example.peciwearables.integration.wearable.legacy

import com.example.peciwearables.integration.wearable.CommandResult
import com.example.peciwearables.integration.wearable.WearableCommand
import com.example.peciwearables.integration.wearable.WearableHub
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableScanCandidate
import com.example.peciwearables.integration.wearable.WearableSession
import com.example.peciwearables.integration.wearable.deviceProfile
import com.example.peciwearables.integration.wearable.devices.brilliantsole.BrilliantSoleWristbandWearableSession
import com.example.peciwearables.integration.wearable.devices.brilliantsole.FakeBrilliantSoleWristbandBleClient
import com.example.peciwearables.integration.wearable.devices.omi.FakeOmiGlassesBleClient
import com.example.peciwearables.integration.wearable.devices.omi.OmiGlassesWearableSession
import com.example.peciwearables.integration.wearable.scanCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearableServiceLegacyBridgeTest {

    private val glassesId = WearableId("AA:11:22:33:44:55")
    private val wristbandId = WearableId("BB:11:22:33:44:55")

    @Test
    fun glassesSessionAttached_audioCodecCallbackReachesLegacyCallback() = runTest {
        val hub = FakeWearableHub()
        val (session, client) = makeOmiSession(scope = backgroundScope)
        var capturedCodec: Int? = null
        val bridge = WearableServiceLegacyBridge(
            hub = hub,
            scope = this,
            callbacks = WearableServiceLegacyBridge.Callbacks(
                onGlassesAudioCodecId = { capturedCodec = it },
            ),
        )

        bridge.start()
        hub.setSessions(session)
        advanceUntilIdle()

        client.simulateAudioCodecId(21)
        advanceUntilIdle()

        assertEquals(21, capturedCodec)
        bridge.stop()
    }

    @Test
    fun wristbandSessionAttached_batteryCallbackReachesLegacyCallback() = runTest {
        val hub = FakeWearableHub()
        val (session, client) = makeSoleSession(scope = backgroundScope)
        var capturedBattery: Int? = null
        val bridge = WearableServiceLegacyBridge(
            hub = hub,
            scope = this,
            callbacks = WearableServiceLegacyBridge.Callbacks(
                onWristbandBattery = { capturedBattery = it },
            ),
        )

        bridge.start()
        hub.setSessions(session)
        advanceUntilIdle()

        client.simulateBattery(55)
        advanceUntilIdle()

        assertEquals(55, capturedBattery)
        bridge.stop()
    }

    @Test
    fun sessionRemoved_noFurtherCallbacksFire() = runTest {
        val hub = FakeWearableHub()
        val (session, client) = makeOmiSession(scope = backgroundScope)
        var codecCount = 0
        val bridge = WearableServiceLegacyBridge(
            hub = hub,
            scope = this,
            callbacks = WearableServiceLegacyBridge.Callbacks(
                onGlassesAudioCodecId = { codecCount++ },
            ),
        )

        bridge.start()
        hub.setSessions(session)
        advanceUntilIdle()
        client.simulateAudioCodecId(2)
        advanceUntilIdle()

        hub.clearSessions()
        advanceUntilIdle()
        client.simulateAudioCodecId(3)
        advanceUntilIdle()

        assertEquals(1, codecCount)
        bridge.stop()
    }

    @Test
    fun stop_cancelsAttachedCollectors() = runTest {
        val hub = FakeWearableHub()
        val (glassesSession, glassesClient) = makeOmiSession(scope = backgroundScope)
        val (wristbandSession, wristbandClient) = makeSoleSession(scope = backgroundScope)
        var codecCount = 0
        var batteryCount = 0
        val bridge = WearableServiceLegacyBridge(
            hub = hub,
            scope = this,
            callbacks = WearableServiceLegacyBridge.Callbacks(
                onGlassesAudioCodecId = { codecCount++ },
                onWristbandBattery = { batteryCount++ },
            ),
        )

        bridge.start()
        hub.setSessions(glassesSession, wristbandSession)
        advanceUntilIdle()
        codecCount = 0
        batteryCount = 0

        bridge.stop()
        glassesClient.simulateAudioCodecId(3)
        wristbandClient.simulateBattery(12)
        advanceUntilIdle()

        assertEquals(0, codecCount)
        assertEquals(0, batteryCount)
    }

    private fun makeOmiSession(
        scope: CoroutineScope,
    ): Pair<OmiGlassesWearableSession, FakeOmiGlassesBleClient> {
        val client = FakeOmiGlassesBleClient()
        val session = OmiGlassesWearableSession(
            id = glassesId,
            profile = deviceProfile(scan = scanCandidate(id = glassesId.raw, name = "Omi")),
            client = client,
            scope = scope,
        )
        return session to client
    }

    private fun makeSoleSession(
        scope: CoroutineScope,
    ): Pair<BrilliantSoleWristbandWearableSession, FakeBrilliantSoleWristbandBleClient> {
        val client = FakeBrilliantSoleWristbandBleClient()
        val session = BrilliantSoleWristbandWearableSession(
            id = wristbandId,
            profile = deviceProfile(scan = scanCandidate(id = wristbandId.raw, name = "BrilliantSole")),
            client = client,
            scope = scope,
        )
        return session to client
    }

    private class FakeWearableHub : WearableHub {
        private val _sessions = MutableStateFlow<Map<WearableId, WearableSession>>(emptyMap())
        override val sessions: StateFlow<Map<WearableId, WearableSession>> = _sessions

        fun setSessions(vararg sessions: WearableSession) {
            _sessions.value = sessions.associateBy { it.id }
        }

        fun clearSessions() {
            _sessions.value = emptyMap()
        }

        override suspend fun connectByCandidate(scan: WearableScanCandidate): WearableSession? = null

        override suspend fun disconnect(id: WearableId) {
            _sessions.value = _sessions.value - id
        }

        override suspend fun send(id: WearableId, command: WearableCommand): CommandResult {
            return _sessions.value[id]?.send(command) ?: CommandResult.Rejected("No active session for ${id.raw}")
        }
    }
}
