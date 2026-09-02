package com.example.peciwearables.integration.network

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkInterfaceUtils {

    fun localIpv4(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
                }
            }
        } catch (e: Exception) {
            Log.w("NetworkInterfaceUtils", "localIpv4 falhou: ${e.message}")
        }
        return null
    }
}
