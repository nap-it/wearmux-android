package com.wearmux.android.integration

import com.wearmux.android.integration.adapters.BleDeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesNetworkStatusResolverTest {

    @Test
    fun `udp server active alone does not mark wifi session active`() {
        val status = GlassesNetworkStatusResolver.resolve(
            bleState = BleDeviceState.DISCONNECTED,
            udpServerActive = true,
            wifiSessionActive = false,
            glassesIp = null
        )

        assertTrue(status.udpServerActive)
        assertFalse(status.wifiSessionActive)
        assertEquals("Session: no Wi-Fi connection", status.sessionText)
    }

    @Test
    fun `ble ready without udp session shows ble transport`() {
        val status = GlassesNetworkStatusResolver.resolve(
            bleState = BleDeviceState.READY,
            udpServerActive = true,
            wifiSessionActive = false,
            glassesIp = "192.168.1.10"
        )

        assertFalse(status.wifiSessionActive)
        assertEquals("Session: BLE active, glasses IP = 192.168.1.10", status.sessionText)
    }

    @Test
    fun `wifi session active wins over udp server state`() {
        val status = GlassesNetworkStatusResolver.resolve(
            bleState = BleDeviceState.CONNECTED,
            udpServerActive = true,
            wifiSessionActive = true,
            glassesIp = "192.168.1.10"
        )

        assertTrue(status.wifiSessionActive)
        assertEquals("Session: Wi-Fi/UDP connected (192.168.1.10)", status.sessionText)
    }
}
