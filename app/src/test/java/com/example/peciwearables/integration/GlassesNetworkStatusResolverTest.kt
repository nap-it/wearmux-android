package com.example.peciwearables.integration

import com.example.peciwearables.integration.ble.BleDeviceState
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
        assertEquals("Sessao: sem ligacao Wi-Fi", status.sessionText)
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
        assertEquals("Sessao: BLE ativo, IP dos oculos = 192.168.1.10", status.sessionText)
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
        assertEquals("Sessao: Wi-Fi/UDP ligado (192.168.1.10)", status.sessionText)
    }
}
