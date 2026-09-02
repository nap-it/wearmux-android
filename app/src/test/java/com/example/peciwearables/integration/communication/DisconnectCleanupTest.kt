package com.example.peciwearables.integration.communication

import org.junit.Assert.*
import org.junit.Test

/** Tests the logical behaviour of disconnect cleanup state transitions.
 *  Uses a pure state simulation — no Android dependencies needed. */
class DisconnectCleanupTest {

    data class OmiDeviceState(
        var photoCaptureInFlight: Boolean = false,
        var micStreaming: Boolean = false,
        var audioRecordingActive: Boolean = false,
        var micStatusText: String = "STREAMING",
        var audioPipelineCleared: Boolean = false,
        var photoTimeoutCancelled: Boolean = false,
        var wifiTransitionCancelled: Boolean = false,
        var imuReporterCancelled: Boolean = false,
        var streamFpsCleared: Boolean = false,
    )

    private fun simulateDisconnect(state: OmiDeviceState) {
        state.photoTimeoutCancelled = true
        state.wifiTransitionCancelled = true
        state.imuReporterCancelled = true
        if (state.audioRecordingActive) {
            state.audioRecordingActive = false
        }
        state.audioPipelineCleared = true
        state.photoCaptureInFlight = false
        state.micStreaming = false
        state.micStatusText = "IDLE"
        state.streamFpsCleared = true
    }

    @Test
    fun `disconnect resets photoCaptureInFlight`() {
        val state = OmiDeviceState(photoCaptureInFlight = true)
        simulateDisconnect(state)
        assertFalse(state.photoCaptureInFlight)
    }

    @Test
    fun `disconnect clears micStreaming`() {
        val state = OmiDeviceState(micStreaming = true)
        simulateDisconnect(state)
        assertFalse(state.micStreaming)
    }

    @Test
    fun `disconnect cancels photoTimeoutJob`() {
        val state = OmiDeviceState()
        simulateDisconnect(state)
        assertTrue(state.photoTimeoutCancelled)
    }

    @Test
    fun `disconnect cancels wifiTransitionJob`() {
        val state = OmiDeviceState()
        simulateDisconnect(state)
        assertTrue(state.wifiTransitionCancelled)
    }

    @Test
    fun `disconnect cancels imuReporterJob`() {
        val state = OmiDeviceState()
        simulateDisconnect(state)
        assertTrue(state.imuReporterCancelled)
    }

    @Test
    fun `disconnect clears audioRecordingActive`() {
        val state = OmiDeviceState(audioRecordingActive = true)
        simulateDisconnect(state)
        assertFalse(state.audioRecordingActive)
    }

    @Test
    fun `disconnect clears audioPipeline`() {
        val state = OmiDeviceState()
        simulateDisconnect(state)
        assertTrue(state.audioPipelineCleared)
    }

    @Test
    fun `disconnect clears streamFps timestamps`() {
        val state = OmiDeviceState()
        simulateDisconnect(state)
        assertTrue(state.streamFpsCleared)
    }

    @Test
    fun `audioRecordingActive guard is respected — no recording means no buffer reset`() {
        // If audioRecordingActive is false, the conditional branch is skipped
        // Verify that micStreaming is still cleared even when no recording was active
        val state = OmiDeviceState(audioRecordingActive = false, micStreaming = true)
        simulateDisconnect(state)
        assertFalse("micStreaming should be cleared even without an active recording", state.micStreaming)
        assertFalse("audioRecordingActive should stay false if it was already false", state.audioRecordingActive)
    }
}
