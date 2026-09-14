package com.example.peciwearables.integration.communication

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class UdpSessionStateTest {

    enum class UdpSessionState { IDLE, STARTING, CONNECTED }

    private val state = AtomicReference(UdpSessionState.IDLE)

    private fun tryStartSession(): Boolean =
        state.compareAndSet(UdpSessionState.IDLE, UdpSessionState.STARTING)

    private fun onSessionConnected() { state.set(UdpSessionState.CONNECTED) }
    private fun onSessionDisconnected() { state.set(UdpSessionState.IDLE) }

    @Test
    fun `starting session transitions IDLE to STARTING`() {
        assertTrue(tryStartSession())
        assertEquals(UdpSessionState.STARTING, state.get())
    }

    @Test
    fun `starting session twice is idempotent — second call returns false`() {
        tryStartSession()
        assertFalse(tryStartSession())
        assertEquals(UdpSessionState.STARTING, state.get())
    }

    @Test
    fun `cannot start while already CONNECTED`() {
        tryStartSession(); onSessionConnected()
        assertFalse(tryStartSession())
        assertEquals(UdpSessionState.CONNECTED, state.get())
    }

    @Test
    fun `disconnect resets to IDLE`() {
        tryStartSession(); onSessionConnected(); onSessionDisconnected()
        assertEquals(UdpSessionState.IDLE, state.get())
    }

    @Test
    fun `can start again after disconnect`() {
        tryStartSession(); onSessionConnected(); onSessionDisconnected()
        assertTrue(tryStartSession())
    }
}
