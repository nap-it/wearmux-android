package com.example.peciwearables.integration.wearable

import app.cash.turbine.test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultWearableHubTest {

    private val glassesId = WearableId("AA:11:22:33:44:55")
    private val wristbandId = WearableId("BB:11:22:33:44:55")

    @Test
    fun connectByCandidate_addsSessionToMap() = runTest {
        val coordinator = FakeWearableConnectionCoordinator()
        val glassesSession = FakeWearableSession(glassesId)
        coordinator.register(glassesId, glassesSession)
        val hub = DefaultWearableHub(coordinator, TestScope())

        val session = hub.connectByCandidate(scanCandidate(id = glassesId.raw))

        assertSame(glassesSession, session)
        assertEquals(setOf(glassesId), hub.sessions.value.keys)
    }

    @Test
    fun connectByCandidate_reusesExistingSessionForSameId() = runTest {
        val coordinator = FakeWearableConnectionCoordinator()
        val glassesSession = FakeWearableSession(glassesId)
        coordinator.register(glassesId, glassesSession)
        val hub = DefaultWearableHub(coordinator, TestScope())

        val first = hub.connectByCandidate(scanCandidate(id = glassesId.raw))
        val second = hub.connectByCandidate(scanCandidate(id = glassesId.raw))

        assertSame(first, second)
        assertEquals(1, coordinator.coordinateCalls.size)
    }

    @Test
    fun connectByCandidate_returnsNullWhenCoordinatorFails() = runTest {
        val coordinator = FakeWearableConnectionCoordinator()
        coordinator.fail(glassesId, IllegalStateException("boom"))
        val hub = DefaultWearableHub(coordinator, TestScope())

        val session = hub.connectByCandidate(scanCandidate(id = glassesId.raw))

        assertEquals(null, session)
        assertTrue(hub.sessions.value.isEmpty())
    }

    @Test
    fun disconnect_removesAndClosesSession() = runTest {
        val coordinator = FakeWearableConnectionCoordinator()
        val glassesSession = FakeWearableSession(glassesId)
        coordinator.register(glassesId, glassesSession)
        val hub = DefaultWearableHub(coordinator, TestScope())
        hub.connectByCandidate(scanCandidate(id = glassesId.raw))

        hub.disconnect(glassesId)

        assertTrue(hub.sessions.value.isEmpty())
        assertTrue(glassesSession.closeCalled)
    }

    @Test
    fun disconnect_isNoopWhenIdAbsent() = runTest {
        val hub = DefaultWearableHub(FakeWearableConnectionCoordinator(), TestScope())
        hub.disconnect(glassesId)
        assertTrue(hub.sessions.value.isEmpty())
    }

    @Test
    fun send_routesCommandToCorrectSession() = runTest {
        val coordinator = FakeWearableConnectionCoordinator()
        val glassesSession = FakeWearableSession(glassesId)
        val wristbandSession = FakeWearableSession(wristbandId)
        coordinator.register(glassesId, glassesSession)
        coordinator.register(wristbandId, wristbandSession)
        val hub = DefaultWearableHub(coordinator, TestScope())
        hub.connectByCandidate(scanCandidate(id = glassesId.raw))
        hub.connectByCandidate(scanCandidate(id = wristbandId.raw))

        hub.send(glassesId, WearableCommand.TakePicture)
        hub.send(wristbandId, WearableCommand.Vibrate(durationMs = 100))

        assertEquals(listOf<WearableCommand>(WearableCommand.TakePicture), glassesSession.sentCommands)
        assertEquals(
            listOf<WearableCommand>(WearableCommand.Vibrate(durationMs = 100)),
            wristbandSession.sentCommands,
        )
    }

    @Test
    fun send_returnsRejectedWhenIdAbsent() = runTest {
        val hub = DefaultWearableHub(FakeWearableConnectionCoordinator(), TestScope())
        val result = hub.send(glassesId, WearableCommand.TakePicture)
        assertTrue(result is CommandResult.Rejected)
    }

    @Test
    fun sessions_emitsUpdatesViaTurbine() = runTest {
        val coordinator = FakeWearableConnectionCoordinator()
        val glassesSession = FakeWearableSession(glassesId)
        coordinator.register(glassesId, glassesSession)
        val hub = DefaultWearableHub(coordinator, TestScope())

        hub.sessions.test {
            assertTrue(awaitItem().isEmpty())

            hub.connectByCandidate(scanCandidate(id = glassesId.raw))
            assertEquals(setOf(glassesId), awaitItem().keys)

            hub.disconnect(glassesId)
            assertTrue(awaitItem().isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
