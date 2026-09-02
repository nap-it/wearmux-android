package com.example.peciwearables.integration

import com.example.peciwearables.integration.ble.BleDeviceState

data class GlassesNetworkStatus(
    val udpServerActive: Boolean,
    val wifiSessionActive: Boolean,
    val sessionText: String,
)

object GlassesNetworkStatusResolver {
    fun resolve(
        bleState: BleDeviceState,
        udpServerActive: Boolean,
        wifiSessionActive: Boolean,
        glassesIp: String?,
    ): GlassesNetworkStatus {
        val sessionText = when {
            wifiSessionActive -> {
                "Sessao: Wi-Fi/UDP ligado" +
                    if (!glassesIp.isNullOrBlank()) " ($glassesIp)" else ""
            }

            bleState == BleDeviceState.READY || bleState == BleDeviceState.CONNECTED -> {
                if (!glassesIp.isNullOrBlank()) {
                    "Sessao: BLE ativo, IP dos oculos = $glassesIp"
                } else {
                    "Sessao: BLE ativo"
                }
            }

            else -> "Sessao: sem ligacao Wi-Fi"
        }

        return GlassesNetworkStatus(
            udpServerActive = udpServerActive,
            wifiSessionActive = wifiSessionActive,
            sessionText = sessionText
        )
    }
}
