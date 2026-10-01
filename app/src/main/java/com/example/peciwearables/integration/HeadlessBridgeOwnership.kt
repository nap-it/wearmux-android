package com.example.peciwearables.integration

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Process-wide BLE ownership gate: the headless bridge and Omi client cannot share GATT. */
object HeadlessBridgeOwnership {
    private val active = AtomicBoolean(false)
    private val _state = MutableStateFlow(false)
    val state: StateFlow<Boolean> = _state
    @JvmStatic fun claim(): Boolean = active.compareAndSet(false, true).also { if (it) _state.value = true }
    @JvmStatic fun release() { active.set(false); _state.value = false }
    @JvmStatic fun isActive(): Boolean = active.get()
}
