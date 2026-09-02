package com.example.peciwearables.integration.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTest {

    private fun session(
        id: String,
        capabilities: Set<WearableCapability>,
    ): WearableSession = FakeWearableSession(
        id = WearableId(id),
        capabilities = capabilities,
    )

    @Test
    fun `isWatch true quando tem imu e haptica e nao tem camara`() {
        val s = session(
            "gw",
            setOf(
                WearableCapability.IMU_STREAM,
                WearableCapability.HAPTIC,
                WearableCapability.BATTERY,
                WearableCapability.DEVICE_INFO,
            ),
        )
        assertTrue(s.isWatch())
    }

    @Test
    fun `isWatch true mesmo com extras nao excludentes`() {
        val s = session(
            "gw-extra",
            setOf(
                WearableCapability.IMU_STREAM,
                WearableCapability.HAPTIC,
                WearableCapability.ON_DEVICE_ML,
            ),
        )
        assertTrue(s.isWatch())
    }

    @Test
    fun `isWatch false quando falta haptica`() {
        val s = session(
            "no-haptic",
            setOf(WearableCapability.IMU_STREAM),
        )
        assertFalse(s.isWatch())
    }

    @Test
    fun `isWatch false quando falta IMU`() {
        val s = session(
            "no-imu",
            setOf(WearableCapability.HAPTIC, WearableCapability.BATTERY),
        )
        assertFalse(s.isWatch())
    }

    @Test
    fun `isWatch false quando tem camara (passa a contar como oculos)`() {
        val s = session(
            "glasses-like",
            setOf(
                WearableCapability.IMU_STREAM,
                WearableCapability.HAPTIC,
                WearableCapability.IMAGE_CAPTURE,
            ),
        )
        assertFalse(s.isWatch())
    }

    @Test
    fun `watchSessions devolve apenas sessoes no perfil de relogio`() {
        val watch = session(
            "w",
            setOf(WearableCapability.IMU_STREAM, WearableCapability.HAPTIC),
        )
        val glasses = session(
            "g",
            setOf(
                WearableCapability.IMU_STREAM,
                WearableCapability.HAPTIC,
                WearableCapability.IMAGE_CAPTURE,
                WearableCapability.AUDIO_STREAM,
            ),
        )
        val plainImu = session("imu-only", setOf(WearableCapability.IMU_STREAM))
        val hub = FakeWatchHub(mapOf(watch.id to watch, glasses.id to glasses, plainImu.id to plainImu))
        val filtered = hub.watchSessions()
        assertEquals(1, filtered.size)
        assertEquals(watch.id, filtered.single().id)
    }

    @Test
    fun `activeWatchSession devolve a primeira sessao de relogio`() = runTestBlocking {
        val w1 = session(
            "w1",
            setOf(WearableCapability.IMU_STREAM, WearableCapability.HAPTIC),
        )
        val w2 = session(
            "w2",
            setOf(WearableCapability.IMU_STREAM, WearableCapability.HAPTIC),
        )
        val hub = FakeWatchHub(mapOf(w1.id to w1, w2.id to w2))
        val flow = hub.activeWatchSession(testScope())
        assertNotNull(flow.value)
        assertEquals(w1.id, flow.value?.id)
    }

    @Test
    fun `activeWatchSession devolve null quando nao ha relogios`() = runTestBlocking {
        val glasses = session(
            "g",
            setOf(
                WearableCapability.IMU_STREAM,
                WearableCapability.HAPTIC,
                WearableCapability.IMAGE_CAPTURE,
            ),
        )
        val hub = FakeWatchHub(mapOf(glasses.id to glasses))
        val flow = hub.activeWatchSession(testScope())
        assertNull(flow.value)
    }
}

private class FakeWatchHub(
    initial: Map<WearableId, WearableSession>,
) : WearableHub {
    override val sessions =
        kotlinx.coroutines.flow.MutableStateFlow(initial)

    override suspend fun connectByCandidate(scan: WearableScanCandidate): WearableSession? = null
    override suspend fun disconnect(id: WearableId) {}
    override suspend fun send(id: WearableId, command: WearableCommand): CommandResult =
        CommandResult.Accepted
}

private fun testScope(): kotlinx.coroutines.CoroutineScope =
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)

private fun runTestBlocking(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
