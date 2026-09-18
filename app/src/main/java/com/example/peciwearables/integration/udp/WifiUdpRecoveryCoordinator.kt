package com.example.peciwearables.integration.udp

import android.util.Log
import com.example.peciwearables.integration.GlassesConnectionMode
import com.example.peciwearables.integration.adapters.BleDeviceState
import com.example.peciwearables.integration.adapters.devices.omi.OmiGlassesBleClientApi
import com.example.peciwearables.integration.network.UdpWifiLock
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WifiUdpRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val state: AtomicReference<WearableServiceUdpState> = AtomicReference(WearableServiceUdpState.IDLE),
    private val glassesClient: () -> OmiGlassesBleClientApi,
    private val wifiLock: UdpWifiLock,
    private val streamTargetFps: () -> Int,
    private val onLog: (String) -> Unit,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        val glassesConnectionMode: GlassesConnectionMode
        val glassesIp: String?
        val isStreaming: Boolean
        val isAudioRecording: Boolean
        val isGlassesMicStreaming: Boolean
        fun setGlassesState(state: BleDeviceState)
        fun setGlassesMicStreaming(v: Boolean)
        fun setGlassesMicStatus(text: String)
        fun setGlassesMicDataText(text: String)
        fun setUdpActive(v: Boolean)
        fun setGlassesIp(ip: String)
        fun onPersistAudioOnDisconnect()
        fun resetPhotoState()
        fun updateWristbandTrafficProfile(reason: String)
        fun isWifiCredentialsSent(): Boolean
    }

    private var wifiTransitionJob: Job? = null
    private var wifiReconnectJob: Job? = null
    private var udpManager: UdpConnectionManager? = null
    private var resumeMicAfterReconnect = false
    private var resumeStreamAfterReconnect = false

    val isConnected: Boolean get() = state.get() == WearableServiceUdpState.CONNECTED
    val activeUdpManager: UdpConnectionManager? get() = udpManager

    fun cancelWatchdog() { wifiTransitionJob?.cancel(); wifiTransitionJob = null }

    fun startWatchdog() {
        wifiTransitionJob?.cancel()
        wifiTransitionJob = scope.launch {
            onLog("🛰 Wi-Fi: monitoring BLE → UDP transition (up to 40s)")
            repeat(20) { attempt ->
                if (isConnected) return@launch
                val client = glassesClient(); client.requestWifiInfo()
                val ip = callbacks.glassesIp
                if (attempt % 3 == 0) {
                    onLog("🛰 Wi-Fi poll ${attempt + 1}/20 → enabled=${client.wifiConnectionEnabled ?: "?"} " +
                        "connected=${client.isWifiConnected ?: "?"} ip=${ip ?: "—"}")
                }
                if (callbacks.isWifiCredentialsSent() && !ip.isNullOrBlank()) connect(ip)
                delay(2_000L)
            }
            if (!isConnected) {
                onLog("⏱ Wi-Fi: timeout connecting UDP. Check SSID/password, AP isolation, 'UDP DBG' lines.")
            }
        }
    }

    fun connect(ip: String) {
        if (!UdpIpValidator.isRoutable(ip)) {
            onLog("⚠  Wi-Fi: invalid IP ignored ($ip)"); return
        }
        callbacks.setGlassesIp(ip)
        if (!state.compareAndSet(WearableServiceUdpState.IDLE, WearableServiceUdpState.STARTING)) {
            Log.i(TAG, "connect() ignored: state=${state.get()}"); return
        }
        val currentStatus = udpManager?.status?.value
        if (currentStatus == UdpConnectionManager.Status.CONNECTING || currentStatus == UdpConnectionManager.Status.CONNECTED) {
            state.set(WearableServiceUdpState.IDLE); return
        }
        onLog("Wi-Fi: trying UDP connection to $ip...")
        udpManager?.onDisconnected = null; udpManager?.disconnect()
        udpManager = UdpConnectionManager(ip).also { mgr ->
            mgr.onDebugLog = { onLog("UDP DBG: $it") }
            mgr.onUdpConnected = {
                onLog("✅ Wi-Fi/UDP connected to $ip! BLE kept for receiving frames...")
                wifiLock.acquire { onLog(it) }
                wifiReconnectJob?.cancel()
                state.set(WearableServiceUdpState.CONNECTED)
                wifiTransitionJob?.cancel()
                callbacks.setGlassesState(BleDeviceState.CONNECTED)
                glassesClient().onUdpTxNeeded = { bytes -> mgr.sendTxRxMessage(bytes) }
                callbacks.setUdpActive(true)
                scope.launch {
                    delay(250L)
                    if (resumeMicAfterReconnect) { glassesClient().startMicrophone(); onLog("🔁 Microphone resumed after Wi-Fi recovery") }
                    if (resumeStreamAfterReconnect) { glassesClient().setStreamMode(true, streamTargetFps()); onLog("🔁 Stream resumed after Wi-Fi recovery") }
                    resumeMicAfterReconnect = false; resumeStreamAfterReconnect = false
                }
            }
            mgr.onDisconnected = { previousStatus ->
                state.set(WearableServiceUdpState.IDLE)
                if (udpManager === mgr) {
                    udpManager = null
                    onLog("UDP DBG: session ended (previous status=$previousStatus)")
                    handleDisconnect(previousStatus)
                }
            }
            mgr.onTxRxMessage = { glassesClient().onTxRxBytesReceived(it) }
            mgr.connect(scope)
        }
    }

    fun clearRouting() {
        glassesClient().onUdpTxNeeded = null
        glassesClient().disconnect(keepUdpRouting = false)
        state.set(WearableServiceUdpState.IDLE)
        callbacks.setUdpActive(false)
    }

    fun disconnectUdpSession() {
        udpManager?.onDisconnected = null
        udpManager?.disconnect()
        udpManager = null
    }

    private fun handleDisconnect(previousStatus: UdpConnectionManager.Status) {
        clearRouting(); wifiLock.release()
        if (previousStatus != UdpConnectionManager.Status.CONNECTED) return
        val recoverWifi = callbacks.glassesConnectionMode == GlassesConnectionMode.WIFI &&
            !callbacks.glassesIp.isNullOrBlank()
        if (recoverWifi) {
            resumeMicAfterReconnect = callbacks.isGlassesMicStreaming || callbacks.isAudioRecording
            resumeStreamAfterReconnect = callbacks.isStreaming
            callbacks.setGlassesState(BleDeviceState.CONNECTING)
            callbacks.setGlassesMicStreaming(false)
            callbacks.setGlassesMicStatus("RECOVERING")
            onLog("🔁 Omi Wi-Fi/UDP session dropped — trying to recover")
            scheduleRecovery(callbacks.glassesIp.orEmpty())
            return
        }
        onLog("⚠  Omi Wi-Fi/UDP session ended")
        callbacks.setGlassesState(BleDeviceState.DISCONNECTED)
        callbacks.setGlassesMicStreaming(false)
        callbacks.setGlassesMicStatus("IDLE")
        callbacks.setGlassesMicDataText("No data")
        if (callbacks.isAudioRecording) callbacks.onPersistAudioOnDisconnect()
        callbacks.resetPhotoState()
        callbacks.updateWristbandTrafficProfile("udp disconnect")
    }

    private fun scheduleRecovery(ip: String) {
        if (ip.isBlank()) return
        wifiReconnectJob?.cancel()
        wifiReconnectJob = scope.launch {
            repeat(5) { attempt ->
                if (isConnected || callbacks.glassesConnectionMode != GlassesConnectionMode.WIFI) return@launch
                delay((1000L + attempt * 1200L).coerceAtMost(6000L))
                onLog("🔁 Wi-Fi recovery attempt ${attempt + 1}/5")
                connect(ip)
            }
            if (!isConnected) {
                onLog("⚠  Wi-Fi recovery failed after several attempts")
                callbacks.setGlassesState(BleDeviceState.DISCONNECTED)
                if (callbacks.isAudioRecording) callbacks.onPersistAudioOnDisconnect()
            }
        }
    }

    private companion object { const val TAG = "WifiUdpRecovery" }
}

enum class WearableServiceUdpState { IDLE, STARTING, CONNECTED }
