package com.example.peciwearables.integration.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WearableAdapterRegistryTest {

    private val uuidA = UUID.fromString("11111111-0000-0000-0000-000000000000")
    private val uuidB = UUID.fromString("22222222-0000-0000-0000-000000000000")
    private val uuidC = UUID.fromString("33333333-0000-0000-0000-000000000000")

    @Test
    fun selectProbable_emptyWhenNoAdapterMatches() {
        val registry = WearableAdapterRegistry(
            listOf(
                FakeWearableAdapter(id = "A", probable = { false }),
                FakeWearableAdapter(id = "B", probable = { false }),
            ),
        )
        val result = registry.selectProbable(scanCandidate(serviceUuids = setOf(uuidC)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun selectProbable_returnsAllAdaptersThatMatch() {
        val registry = WearableAdapterRegistry(
            listOf(
                FakeWearableAdapter(id = "A", probable = { uuidA in it.advertisedServiceUuids }),
                FakeWearableAdapter(id = "B", probable = { uuidA in it.advertisedServiceUuids }),
                FakeWearableAdapter(id = "C", probable = { uuidB in it.advertisedServiceUuids }),
            ),
        )
        val result = registry.selectProbable(scanCandidate(serviceUuids = setOf(uuidA)))
        assertEquals(listOf("A", "B"), result.map { it.id })
    }

    @Test
    fun confirm_returnsFirstAdapterThatConfirms() {
        val a = FakeWearableAdapter(id = "A", confirm = { false })
        val b = FakeWearableAdapter(id = "B", confirm = { uuidA in it.discoveredServices })
        val c = FakeWearableAdapter(id = "C", confirm = { uuidA in it.discoveredServices })
        val registry = WearableAdapterRegistry(listOf(a, b, c))

        val chosen = registry.confirm(deviceProfile(services = setOf(uuidA)))
        assertSame(b, chosen)
    }

    @Test
    fun confirm_nullWhenNoAdapterConfirms() {
        val registry = WearableAdapterRegistry(
            listOf(
                FakeWearableAdapter(id = "A", confirm = { false }),
            ),
        )
        assertNull(registry.confirm(deviceProfile(services = setOf(uuidA))))
    }
}
