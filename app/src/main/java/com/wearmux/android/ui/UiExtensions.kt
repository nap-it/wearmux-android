package com.wearmux.android.ui

import com.wearmux.android.BoxState
import com.wearmux.android.integration.GlassesConnectionMode
import com.wearmux.android.integration.adapters.BleDeviceState

fun BleDeviceState.toDisplayString(): String = when (this) {
    BleDeviceState.DISCONNECTED -> "Disconnected"
    BleDeviceState.CONNECTING -> "Connecting..."
    BleDeviceState.DISCOVERING -> "Discovering services..."
    BleDeviceState.CONFIGURING -> "Configuring..."
    BleDeviceState.READY -> "BLE ready"
    BleDeviceState.CONNECTED -> "Connected (handshake OK)"
    BleDeviceState.ERROR -> "Error"
}

fun GlassesConnectionMode.toDisplayString(): String = when (this) {
    GlassesConnectionMode.BLE -> "BLE"
    GlassesConnectionMode.WIFI -> "Wi-Fi"
}

fun BleDeviceState.toBoxState(): BoxState = when (this) {
    BleDeviceState.DISCONNECTED -> BoxState.WARNING
    BleDeviceState.CONNECTING, BleDeviceState.DISCOVERING, BleDeviceState.CONFIGURING -> BoxState.INFO
    BleDeviceState.READY -> BoxState.INFO
    BleDeviceState.CONNECTED -> BoxState.GOOD
    BleDeviceState.ERROR -> BoxState.BAD
}

