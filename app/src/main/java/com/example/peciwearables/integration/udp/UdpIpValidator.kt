package com.example.peciwearables.integration.udp

/** Rejeita IPv4 não-routable: vazio, 0.0.0.0, 127.x, 169.254.x, 255.255.255.255. */
object UdpIpValidator {
    fun isRoutable(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        if (parts.all { it == 0 }) return false
        if (parts[0] == 127) return false
        if (parts.all { it == 255 }) return false
        if (parts[0] == 169 && parts[1] == 254) return false
        return true
    }
}
