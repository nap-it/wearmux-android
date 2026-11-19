package com.wearmux.android.integration.modules.sensors

import com.wearmux.android.integration.protocol.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O IMU é **por dispositivo**: duas pulseiras têm contadores, taxas e últimas
 * amostras separados. Nada é misturado.
 */
class DeviceImuTrackerTest {

    private val macA = "AA:BB:CC:DD:EE:FF"
    private val macB = "11:22:33:44:55:66"

    private fun sample(ax: Short = 100, ay: Short = 200, az: Short = 300) =
        ImuSample(ax, ay, az, 10, 20, 30, 1, 2, 3, ShortArray(8))

    @Test
    fun twoDevices_keepSeparateCounters() {
        val tracker = DeviceImuTracker()

        tracker.record(macA, sample(ax = 1))
        tracker.record(macA, sample(ax = 2))
        tracker.record(macB, sample(ax = 9))

        assertEquals(2L, tracker.stateOf(macA)?.sampleCount)
        assertEquals(1L, tracker.stateOf(macB)?.sampleCount)
        assertEquals(2, tracker.stateOf(macA)?.latest?.ax?.toInt())
        assertEquals(9, tracker.stateOf(macB)?.latest?.ax?.toInt())
    }

    @Test
    fun unknownDevice_hasNoState() {
        val tracker = DeviceImuTracker()
        assertNull(tracker.stateOf(macA))
    }

    @Test
    fun rateIsMeasuredOverTheRecentWindow() {
        val tracker = DeviceImuTracker()

        // 10 amostras em 1 segundo → 10 Hz (contagem = 9 intervalos por 1000ms).
        repeat(10) { index ->
            tracker.record(macA, sample(), nowMs = index * 100L)
        }

        val rate = tracker.stateOf(macA)?.rateHz
        assertNotNull(rate)
        assertTrue("taxa esperada perto de 10 Hz, obtida $rate", rate in 8..10)
    }

    @Test
    fun remove_forgetsTheDevice() {
        val tracker = DeviceImuTracker()
        tracker.record(macA, sample())

        tracker.remove(macA)

        assertNull(tracker.stateOf(macA))
        assertTrue(tracker.ids().isEmpty())
    }

    @Test
    fun ids_listsTheDevicesThatSentSamples() {
        val tracker = DeviceImuTracker()
        tracker.record(macA, sample())
        tracker.record(macB, sample())

        assertEquals(setOf(macA, macB), tracker.ids())
    }
}
