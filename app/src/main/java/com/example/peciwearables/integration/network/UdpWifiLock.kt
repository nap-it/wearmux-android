package com.example.peciwearables.integration.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/** WifiLock high-perf que mantém o rádio acordado durante a sessão UDP. */
class UdpWifiLock(private val context: Context) {
    private var lock: WifiManager.WifiLock? = null

    fun acquire(onLog: (String) -> Unit = {}) {
        if (lock?.isHeld == true) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            lock = wifi.createWifiLock(mode, "peci_udp_wifi_lock").apply {
                setReferenceCounted(false); acquire()
            }
            onLog("📶 WifiLock high-perf adquirido (sessão UDP)")
        } catch (e: Exception) {
            Log.w("UdpWifiLock", "acquire error: ${e.message}")
        }
    }

    fun release() {
        runCatching {
            if (lock?.isHeld == true) lock?.release()
        }.onFailure { Log.w("UdpWifiLock", "release error: ${it.message}") }
        lock = null
    }
}
