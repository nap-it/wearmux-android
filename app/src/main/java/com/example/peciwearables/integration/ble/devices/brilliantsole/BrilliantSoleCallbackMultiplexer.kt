package com.example.peciwearables.integration.ble.devices.brilliantsole

import kotlinx.coroutines.CoroutineScope

class BrilliantSoleCallbackMultiplexer(
    private val client: BrilliantSoleWristbandBleClientApi,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
) {
    val state get() = client.state
    val batteryLevel get() = client.batteryLevel
    val firmwareVersion get() = client.firmwareVersion
    val deviceName get() = client.deviceName
}
