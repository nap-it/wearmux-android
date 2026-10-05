package com.wearmux.android

import com.wearmux.android.integration.adapters.BleDeviceState
import com.wearmux.android.integration.modules.wearos.WatchClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearableVisibilityTest {

    @Test
    fun `no wearable visible when everything is disconnected`() {
        val result = WearableVisibility.anyVisible(
            watch = WatchClient.State.DISCONNECTED,
            glasses = BleDeviceState.DISCONNECTED,
            wristband = BleDeviceState.DISCONNECTED,
            esp32 = BleDeviceState.DISCONNECTED,
        )
        assertFalse(result)
    }

    @Test
    fun `wearable visible when glasses are connecting`() {
        val result = WearableVisibility.anyVisible(
            watch = WatchClient.State.DISCONNECTED,
            glasses = BleDeviceState.CONNECTING,
            wristband = BleDeviceState.DISCONNECTED,
            esp32 = BleDeviceState.DISCONNECTED,
        )
        assertTrue(result)
    }

    @Test
    fun `wearable visible when watch is remote`() {
        val result = WearableVisibility.anyVisible(
            watch = WatchClient.State.REMOTE,
            glasses = BleDeviceState.DISCONNECTED,
            wristband = BleDeviceState.DISCONNECTED,
            esp32 = BleDeviceState.DISCONNECTED,
        )
        assertTrue(result)
    }
}
