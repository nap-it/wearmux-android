package com.example.peciwearables.integration.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class GattWatchdogTest {

    @Test
    fun `watchdog fires after timeout`() = runTest {
        val fired = AtomicBoolean(false)
        val watchdog = GattWatchdog(timeoutMs = 1800L, scope = backgroundScope)
        watchdog.arm("testOp") { fired.set(true) }
        advanceTimeBy(2000L)
        assertTrue(fired.get())
    }

    @Test
    fun `watchdog does not fire if disarmed`() = runTest {
        val fired = AtomicBoolean(false)
        val watchdog = GattWatchdog(timeoutMs = 1800L, scope = backgroundScope)
        watchdog.arm("testOp") { fired.set(true) }
        watchdog.disarm()
        advanceTimeBy(2000L)
        assertFalse(fired.get())
    }

    @Test
    fun `watchdog can be re-armed after firing`() = runTest {
        val count = AtomicInteger(0)
        val watchdog = GattWatchdog(timeoutMs = 500L, scope = backgroundScope)
        watchdog.arm("op1") { count.incrementAndGet() }
        advanceTimeBy(600L)
        watchdog.arm("op2") { count.incrementAndGet() }
        advanceTimeBy(600L)
        assertEquals(2, count.get())
    }

    @Test
    fun `re-arming cancels previous watchdog`() = runTest {
        val count = AtomicInteger(0)
        val watchdog = GattWatchdog(timeoutMs = 1000L, scope = backgroundScope)
        watchdog.arm("op1") { count.incrementAndGet() }
        advanceTimeBy(500L)
        watchdog.arm("op2") { count.incrementAndGet() }  // re-arm resets timer
        advanceTimeBy(800L)
        // op1's timeout would have been at 1000ms total; re-arm at 500ms means
        // op2 fires at 500+1000=1500ms. At 1300ms total, nothing should have fired.
        assertEquals(0, count.get())
    }
}
