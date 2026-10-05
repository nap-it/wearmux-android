package com.wearmux.android.integration.adapters

import com.wearmux.android.integration.hub.DefaultDeviceConnectionCoordinator
import com.wearmux.android.integration.hub.WearableAdapterRegistry
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultWearableConnectionCoordinatorTest {

    private val serviceUuid = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")

    private fun scan(id: String = "AA:11:22:33:44:55") = WearableScanCandidate(
        id = WearableId(id),
        displayName = "candidate",
        rssi = -50,
        advertisedServiceUuids = setOf(serviceUuid),
        manufacturerData = emptyMap(),
        serviceData = emptyMap(),
        raw = null,
    )

    @Test
    fun coordinate_usesFirstProbableAdapterThatConnects() = runTest {
        val expected = FakeWearableSession(WearableId("AA:11:22:33:44:55"), adapterId = "B")
        val adapterA = FakeWearableAdapter(
            id = "A",
            probable = { true },
            connectResult = { _, _ -> Result.failure(IllegalStateException("not mine")) },
        )
        val adapterB = FakeWearableAdapter(
            id = "B",
            probable = { true },
            connectResult = { _, _ -> Result.success(expected) },
        )
        val coordinator = DefaultDeviceConnectionCoordinator(WearableAdapterRegistry(listOf(adapterA, adapterB)))

        val result = coordinator.coordinate(scan(), backgroundScope)

        assertTrue(result.isSuccess)
        assertSame(expected, result.getOrThrow())
        assertEquals(1, adapterA.connectCalls.size)
        assertEquals(1, adapterB.connectCalls.size)
    }

    @Test
    fun coordinate_doesNotCallAdaptersThatAreNotProbable() = runTest {
        val adapterA = FakeWearableAdapter(id = "A", probable = { false })
        val adapterB = FakeWearableAdapter(id = "B", probable = { true })
        val coordinator = DefaultDeviceConnectionCoordinator(WearableAdapterRegistry(listOf(adapterA, adapterB)))

        val result = coordinator.coordinate(scan(), backgroundScope)

        assertTrue(result.isSuccess)
        assertEquals(0, adapterA.connectCalls.size)
        assertEquals(1, adapterB.connectCalls.size)
    }

    @Test
    fun coordinate_returnsFailureWhenNoAdapterMatches() = runTest {
        val coordinator = DefaultDeviceConnectionCoordinator(
            WearableAdapterRegistry(listOf(FakeWearableAdapter(id = "A", probable = { false }))),
        )

        val result = coordinator.coordinate(scan(), backgroundScope)

        assertTrue(result.isFailure)
    }
}
