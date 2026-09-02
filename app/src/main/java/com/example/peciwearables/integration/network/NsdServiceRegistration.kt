package com.example.peciwearables.integration.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Regista o serviço UDP via mDNS para descoberta na LAN.
 * Garante MulticastLock para que mDNS funcione em Wi-Fi.
 */
class NsdServiceRegistration(
    private val context: Context,
    private val serviceName: String,
    private val serviceType: String,
    private val port: Int,
) {
    private var nsdManager: NsdManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) { Log.d(TAG, "NSD registered: ${info.serviceName}") }
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { Log.e(TAG, "NSD register failed: $errorCode") }
        override fun onServiceUnregistered(info: NsdServiceInfo) { Log.d(TAG, "NSD unregistered") }
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) { Log.e(TAG, "NSD unregister failed: $errorCode") }
    }

    fun register() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("peci_mcast_lock").apply {
                setReferenceCounted(true); acquire()
            }
            val info = NsdServiceInfo().apply {
                serviceName = this@NsdServiceRegistration.serviceName
                serviceType = this@NsdServiceRegistration.serviceType
                port = this@NsdServiceRegistration.port
            }
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "NSD registration failed: ${e.message}")
        }
    }

    fun unregister() {
        runCatching { nsdManager?.unregisterService(listener) }
            .onFailure { Log.w(TAG, "NSD unregister error: ${it.message}") }
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }.onFailure { Log.w(TAG, "MulticastLock release error: ${it.message}") }
        multicastLock = null
    }

    private companion object { const val TAG = "NsdRegistration" }
}
