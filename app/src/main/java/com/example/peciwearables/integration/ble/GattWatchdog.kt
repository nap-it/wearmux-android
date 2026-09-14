package com.example.peciwearables.integration.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class GattWatchdog(
    private val timeoutMs: Long = 1_800L,
    private val scope: CoroutineScope,
) {
    private var watchdogJob: Job? = null

    fun arm(opName: String, onTimeout: () -> Unit) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(timeoutMs)
            Log.w("GattWatchdog", "GATT op '$opName' timed out after ${timeoutMs}ms — recovering queue")
            onTimeout()
        }
    }

    fun disarm() {
        watchdogJob?.cancel()
        watchdogJob = null
    }
}
