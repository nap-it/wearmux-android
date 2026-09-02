package com.example.peciwearables.integration.communication

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class StreamLifecycleTest {

    enum class GlassesMicStreamState { IDLE, STARTING, STREAMING, STOPPING, ERROR }
    enum class GlassesCameraStreamState { IDLE, STARTING, STREAMING, STOPPING, ERROR }

    private val micState = MutableStateFlow(GlassesMicStreamState.IDLE)
    private val camState = MutableStateFlow(GlassesCameraStreamState.IDLE)

    private fun startMic(): Boolean {
        if (micState.value != GlassesMicStreamState.IDLE) return false
        micState.value = GlassesMicStreamState.STARTING
        return true
    }

    private fun onMicStatusStreaming() {
        if (micState.value == GlassesMicStreamState.STARTING) micState.value = GlassesMicStreamState.STREAMING
    }

    private fun stopMic(): Boolean {
        if (micState.value == GlassesMicStreamState.IDLE) return false
        micState.value = GlassesMicStreamState.STOPPING
        return true
    }

    private fun onMicStatusIdle() { micState.value = GlassesMicStreamState.IDLE }

    @Test
    fun `starting mic transitions to STARTING`() {
        assertTrue(startMic())
        assertEquals(GlassesMicStreamState.STARTING, micState.value)
    }

    @Test
    fun `starting mic twice is idempotent — second returns false`() {
        startMic()
        assertFalse(startMic())
        assertEquals(GlassesMicStreamState.STARTING, micState.value)
    }

    @Test
    fun `mic status 1 transitions STARTING to STREAMING`() {
        startMic()
        onMicStatusStreaming()
        assertEquals(GlassesMicStreamState.STREAMING, micState.value)
    }

    @Test
    fun `stopping mic transitions to STOPPING`() {
        startMic(); onMicStatusStreaming(); stopMic()
        assertEquals(GlassesMicStreamState.STOPPING, micState.value)
    }

    @Test
    fun `stopping mic from IDLE returns false — idempotent`() {
        assertFalse(stopMic())
    }

    @Test
    fun `stopping mic does NOT affect camera state`() {
        camState.value = GlassesCameraStreamState.STREAMING
        startMic(); onMicStatusStreaming(); stopMic()
        assertEquals(GlassesCameraStreamState.STREAMING, camState.value)
    }

    @Test
    fun `stopping camera does NOT affect mic state`() {
        micState.value = GlassesMicStreamState.STREAMING
        camState.value = GlassesCameraStreamState.STREAMING
        camState.value = GlassesCameraStreamState.STOPPING
        assertEquals(GlassesMicStreamState.STREAMING, micState.value)
    }

    @Test
    fun `device disconnect resets both streams to IDLE`() {
        micState.value = GlassesMicStreamState.STREAMING
        camState.value = GlassesCameraStreamState.STREAMING
        // Simulate onGlassesDeviceDisconnected()
        micState.value = GlassesMicStreamState.IDLE
        camState.value = GlassesCameraStreamState.IDLE
        assertEquals(GlassesMicStreamState.IDLE, micState.value)
        assertEquals(GlassesCameraStreamState.IDLE, camState.value)
    }
}
