package com.example.peciwearables.integration.managers

import com.example.peciwearables.integration.WearableService.Companion.GlassesCameraStreamState
import com.example.peciwearables.integration.WearableService.Companion.GlassesMicStreamState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


class GlassesMicStateMachine(
    private val streaming: MutableStateFlow<Boolean>,
    private val statusText: MutableStateFlow<String>,
    private val state: MutableStateFlow<GlassesMicStreamState>,
    private val dataText: MutableStateFlow<String>,
    private val audioRecordingActive: StateFlow<Boolean>,
) {
    fun onStatus(status: Int) {
        streaming.value = status != 0
        statusText.value = when (status) { 1 -> "STREAMING"; 2 -> "VAD"; else -> "IDLE" }
        val cur = state.value
        state.value = when {
            status != 0 && cur == GlassesMicStreamState.STARTING -> GlassesMicStreamState.STREAMING
            status == 0 && (cur == GlassesMicStreamState.STOPPING || cur == GlassesMicStreamState.STARTING) -> GlassesMicStreamState.IDLE
            else -> cur
        }
        if (status == 0 && !audioRecordingActive.value) dataText.value = "Sem dados"
    }
}

class GlassesCameraStateMachine(
    private val cameraStatus: MutableStateFlow<Int>,
    private val state: MutableStateFlow<GlassesCameraStreamState>,
    private val onStatusForwarded: (Int) -> Unit,
) {
    fun onStatus(status: Int) {
        cameraStatus.value = status
        onStatusForwarded(status)
        val cur = state.value
        val stopped = status == 0 || status == 3
        state.value = when {
            stopped && (cur == GlassesCameraStreamState.STOPPING || cur == GlassesCameraStreamState.STARTING) -> GlassesCameraStreamState.IDLE
            !stopped && cur == GlassesCameraStreamState.STARTING -> GlassesCameraStreamState.STREAMING
            else -> cur
        }
    }
}
