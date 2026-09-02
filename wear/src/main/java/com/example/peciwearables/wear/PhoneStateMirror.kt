package com.example.peciwearables.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


object PhoneStateMirror {

    enum class BleState { DISCONNECTED, CONNECTING, DISCOVERING, CONFIGURING, READY, CONNECTED, ERROR }

    private val _glassesState = MutableStateFlow(BleState.DISCONNECTED)
    val glassesState: StateFlow<BleState> = _glassesState

    private val _wristbandState = MutableStateFlow(BleState.DISCONNECTED)
    val wristbandState: StateFlow<BleState> = _wristbandState

    private val _phoneSensorsActive = MutableStateFlow(false)
    val phoneSensorsActive: StateFlow<Boolean> = _phoneSensorsActive

    private val _audioTestActive = MutableStateFlow(false)
    val audioTestActive: StateFlow<Boolean> = _audioTestActive

    private val _deviceCount = MutableStateFlow(0)
    val deviceCount: StateFlow<Int> = _deviceCount

    fun update(
        glassesState: Int,
        wristbandState: Int,
        phoneSensorsActive: Boolean,
        audioTestActive: Boolean,
        deviceCount: Int,
    ) {
        _glassesState.value = stateFor(glassesState)
        _wristbandState.value = stateFor(wristbandState)
        _phoneSensorsActive.value = phoneSensorsActive
        _audioTestActive.value = audioTestActive
        _deviceCount.value = deviceCount
    }

    private fun stateFor(raw: Int): BleState {
        return BleState.values().getOrNull(raw) ?: BleState.DISCONNECTED
    }
}
