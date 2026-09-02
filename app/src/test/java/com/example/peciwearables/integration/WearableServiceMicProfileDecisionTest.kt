package com.example.peciwearables.integration

import com.example.peciwearables.integration.ble.BleDeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearableServiceMicProfileDecisionTest {

    @Test
    fun `disconnected state blocks profile apply`() {
        val decision = decideGlassesMicrophoneProfileApply(
            glassesState = BleDeviceState.DISCONNECTED,
            micStreaming = true,
        )

        assertFalse(decision.canApply)
        assertFalse(decision.shouldRestartStream)
        assertTrue(decision.reason?.contains("nao conectado") == true)
    }

    @Test
    fun `connected and idle applies without restart`() {
        val decision = decideGlassesMicrophoneProfileApply(
            glassesState = BleDeviceState.CONNECTED,
            micStreaming = false,
        )

        assertTrue(decision.canApply)
        assertFalse(decision.shouldRestartStream)
        assertEquals(null, decision.reason)
    }

    @Test
    fun `ready and streaming applies with restart`() {
        val decision = decideGlassesMicrophoneProfileApply(
            glassesState = BleDeviceState.READY,
            micStreaming = true,
        )

        assertTrue(decision.canApply)
        assertTrue(decision.shouldRestartStream)
        assertEquals(null, decision.reason)
    }

}
