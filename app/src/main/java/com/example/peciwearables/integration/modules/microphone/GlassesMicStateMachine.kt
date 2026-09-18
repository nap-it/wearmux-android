package com.example.peciwearables.integration.modules.microphone

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
        if (status == 0 && !audioRecordingActive.value) dataText.value = "No data"
    }
}
