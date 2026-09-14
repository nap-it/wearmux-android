package com.example.peciwearables.ui

import com.example.peciwearables.BoxState
import com.example.peciwearables.integration.GlassesConnectionMode
import com.example.peciwearables.integration.ble.BleDeviceState

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

