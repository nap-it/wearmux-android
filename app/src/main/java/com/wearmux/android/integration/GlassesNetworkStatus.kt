package com.wearmux.android.integration

import com.wearmux.android.integration.adapters.BleDeviceState

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
                "Session: Wi-Fi/UDP connected" +
                    if (!glassesIp.isNullOrBlank()) " ($glassesIp)" else ""
            }

            bleState == BleDeviceState.READY || bleState == BleDeviceState.CONNECTED -> {
                if (!glassesIp.isNullOrBlank()) {
                    "Session: BLE active, glasses IP = $glassesIp"
                } else {
                    "Session: BLE active"
                }
            }

            else -> "Session: no Wi-Fi connection"
        }

        return GlassesNetworkStatus(
            udpServerActive = udpServerActive,
            wifiSessionActive = wifiSessionActive,
            sessionText = sessionText
        )
    }
}
