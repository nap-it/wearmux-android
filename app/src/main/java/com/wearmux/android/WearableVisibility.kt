package com.wearmux.android

import com.wearmux.android.integration.adapters.BleDeviceState
import com.wearmux.android.integration.modules.wearos.WatchClient

/**
 * Shared "is this wearable worth showing/counting" rule, used by both
 * [AppViewModel] (to decide if any external wearable is connected) and
 * [com.wearmux.android.ui.HomeScreen] (to decide which device cards
 * to render).
 */
object WearableVisibility {

    fun isVisible(state: WatchClient.State): Boolean = when (state) {
        WatchClient.State.STREAMING, WatchClient.State.AVAILABLE, WatchClient.State.REMOTE -> true
        WatchClient.State.ERROR -> true
        WatchClient.State.DISCONNECTED -> false
    }

    fun isVisible(state: BleDeviceState): Boolean = when (state) {
        BleDeviceState.CONNECTING, BleDeviceState.DISCOVERING, BleDeviceState.CONFIGURING,
        BleDeviceState.READY, BleDeviceState.CONNECTED, BleDeviceState.ERROR -> true
        BleDeviceState.DISCONNECTED -> false
    }

    fun anyVisible(
        watch: WatchClient.State,
        glasses: BleDeviceState,
        wristband: BleDeviceState,
        esp32: BleDeviceState,
    ): Boolean = isVisible(watch) || isVisible(glasses) || isVisible(wristband) || isVisible(esp32)
}
