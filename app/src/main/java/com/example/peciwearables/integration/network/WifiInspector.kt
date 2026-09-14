package com.example.peciwearables.integration.network

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log


object WifiInspector {

    private const val TAG = "WifiInspector"

    /** SSID actual da Wi-Fi do telemóvel, ou `null` se não estiver ligado / não autorizado. */
    fun currentSsid(context: Context): String? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val raw = wm.connectionInfo?.ssid?.trim() ?: return null
            // O sistema rodeia o SSID com aspas. Strip + filtra placeholders.
            val cleaned = raw.removePrefix("\"").removeSuffix("\"")
            when {
                cleaned.isBlank() -> null
                cleaned.equals("<unknown ssid>", ignoreCase = true) -> null
                cleaned == "0x" -> null
                else -> cleaned
            }
        } catch (e: Exception) {
            Log.w(TAG, "currentSsid: ${e.message}")
            null
        }
    }

    /** Abre o ecrã "Wi-Fi" das Definições do Android para o utilizador mudar de rede. */
    fun openSystemWifiSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "openSystemWifiSettings: ${e.message}")
        }
    }
}
