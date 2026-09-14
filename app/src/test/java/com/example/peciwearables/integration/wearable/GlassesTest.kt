package com.example.peciwearables.integration.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesTest {

    private fun session(
        id: String,
        capabilities: Set<WearableCapability>,
    ): WearableSession = FakeWearableSession(
        id = WearableId(id),
        capabilities = capabilities,
    )

    @Test
    fun `isGlasses true quando tem camara audio e imu`() {
        val s = session(
            "g1",
            setOf(
                WearableCapability.IMAGE_CAPTURE,
                WearableCapability.AUDIO_STREAM,
                WearableCapability.IMU_STREAM,
            ),
        )
        assertTrue(s.isGlasses())
    }

    @Test
    fun `isGlasses false quando falta uma capability`() {
        val s = session(
            "no-imu",
            setOf(
                WearableCapability.IMAGE_CAPTURE,
                WearableCapability.AUDIO_STREAM,
            ),
        )
        assertFalse(s.isGlasses())
    }

    @Test
    fun `isGlasses true mesmo com capabilities extra (battery, haptic, wifi)`() {
        val s = session(
            "g-full",
            WearableCapability.entries.toSet(),
        )
        assertTrue(s.isGlasses())
    }

    @Test
    fun `isGlasses false para sessao sem capabilities`() {
        val s = session("empty", emptySet())
        assertFalse(s.isGlasses())
    }

    @Test
    fun `glassesSessions devolve apenas sessoes com capabilities de oculos`() {
        val glasses = session(
            "g",
            setOf(
                WearableCapability.IMAGE_CAPTURE,
                WearableCapability.AUDIO_STREAM,
                WearableCapability.IMU_STREAM,
            ),
        )
        val watch = session(
            "w",
            setOf(WearableCapability.IMU_STREAM, WearableCapability.HAPTIC),
        )
        val hub = FakeHub(mapOf(glasses.id to glasses, watch.id to watch))
        val filtered = hub.glassesSessions()
        assertEquals(1, filtered.size)
        assertEquals(glasses.id, filtered.single().id)
    }

    @Test
    fun `glassesSessions vazio quando nao ha oculos`() {
        val watch = session(
            "w",
            setOf(WearableCapability.IMU_STREAM, WearableCapability.HAPTIC),
        )
        val hub = FakeHub(mapOf(watch.id to watch))
        assertTrue(hub.glassesSessions().isEmpty())
    }

    @Test
    fun `activeGlassesSession devolve a primeira sessao de oculos`() = runTestBlocking {
        val a = session(
            "a",
            setOf(
                WearableCapability.IMAGE_CAPTURE,
                WearableCapability.AUDIO_STREAM,
                WearableCapability.IMU_STREAM,
            ),
        )
        val b = session(
            "b",
            setOf(
                WearableCapability.IMAGE_CAPTURE,
                WearableCapability.AUDIO_STREAM,
                WearableCapability.IMU_STREAM,
            ),
        )
        val hub = FakeHub(mapOf(a.id to a, b.id to b))
        val flow = hub.activeGlassesSession(testScope())
        assertNotNull(flow.value)
        // O Map iteration order do Kotlin é insertion-order (LinkedHashMap); o
        // primeiro a entrar é "a".
        assertEquals(a.id, flow.value?.id)
    }

    @Test
    fun `activeGlassesSession devolve null quando nao ha oculos`() = runTestBlocking {
        val watch = session("w", setOf(WearableCapability.HAPTIC))
        val hub = FakeHub(mapOf(watch.id to watch))
        val flow = hub.activeGlassesSession(testScope())
        assertNull(flow.value)
    }
}

private class FakeHub(
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
