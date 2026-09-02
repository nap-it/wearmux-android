package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.watch.WatchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * UC0 transversal: observa state flows dos 3 wearables e dispara
 * `notifyLost`/`DeviceConnected` no event bus na transição online↔offline.
 */
class DeviceConnectivityObservers(
    private val scope: CoroutineScope,
    private val eventBus: SafetyEventBus,
    private val notifyLost: (String) -> Unit,
) {
    fun attach(
        glassesState: StateFlow<BleDeviceState>,
        wristbandState: StateFlow<BleDeviceState>,
        watchState: StateFlow<WatchClient.State>,
    ) {
        observeBle(glassesState, "Omi glasses", "omi")
        observeBle(wristbandState, "Pulseira", "sole")
        observeWatch(watchState)
    }

    private fun observeBle(flow: StateFlow<BleDeviceState>, displayName: String, busKey: String) {
        scope.launch {
            var prev = false
            flow.collect { st ->
                val online = st == BleDeviceState.READY || st == BleDeviceState.CONNECTED
                if (prev && !online) notifyLost(displayName)
                else if (!prev && online) eventBus.publish(SafetyEventBus.Event.DeviceConnected(busKey))
                prev = online
            }
        }
    }

    private fun observeWatch(flow: StateFlow<WatchClient.State>) {
        scope.launch {
            var prev = false
            flow.collect { st ->
                val online = st != WatchClient.State.DISCONNECTED && st != WatchClient.State.ERROR
                if (prev && !online) notifyLost("Galaxy Watch")
                else if (!prev && online) eventBus.publish(SafetyEventBus.Event.DeviceConnected("watch"))
                prev = online
            }
        }
    }
}
