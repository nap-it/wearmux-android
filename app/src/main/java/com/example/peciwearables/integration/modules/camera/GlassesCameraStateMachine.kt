package com.example.peciwearables.integration.modules.camera

import com.example.peciwearables.integration.WearableService.Companion.GlassesCameraStreamState
import kotlinx.coroutines.flow.MutableStateFlow


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
