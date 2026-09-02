package com.example.peciwearables.integration.wearable.devices.esp32

import com.example.peciwearables.integration.wearable.CommandResult
import com.example.peciwearables.integration.wearable.WearableCapability
import com.example.peciwearables.integration.wearable.WearableCommand
import com.example.peciwearables.integration.wearable.WearableConnectionState
import com.example.peciwearables.integration.wearable.WearableEvent
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableRealtimeSink
import com.example.peciwearables.integration.wearable.WearableSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class Esp32WearableSession(
    override val id: WearableId,
    private val scope: CoroutineScope,
    private val controlClient: Esp32ControlClient = Esp32ControlClient(),
    private val udpClient: Esp32UdpClient = Esp32UdpClient(),
    private val realtimeSink: WearableRealtimeSink = WearableRealtimeSink.NONE
) : WearableSession {

    override val adapterId: String = "esp32-cam"

    override val capabilities: Set<WearableCapability> = setOf(
        WearableCapability.VIDEO_STREAM,
        WearableCapability.IMU_STREAM
    )

    private val _state = MutableStateFlow(WearableConnectionState.DISCONNECTED)
    override val state: StateFlow<WearableConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<WearableEvent>()
    override val events: SharedFlow<WearableEvent> = _events.asSharedFlow()

    init {
        udpClient.onImageChunk = { chunk ->
            realtimeSink.onStreamingJpeg(id, chunk.chunkBytes)
        }
        udpClient.onImuPayload = { payload ->
            payload.samples.forEach { sample ->
                realtimeSink.onImuSample(id, sample, 0, payload.baseTimestampUs / 1000L)
            }
        }
    }

    suspend fun connect(): Boolean {
        _state.value = WearableConnectionState.CONNECTING
        val status = controlClient.checkStatus()
        if (status == null) {
            _state.value = WearableConnectionState.DISCONNECTED
            return false
        }

        val localIp = getLocalIp()
        udpClient.startListening(scope, "192.168.4.1") // Target ESP32 for keepalives
        val started = controlClient.startUdpStream(8554, localIp)
        if (started) {
            _state.value = WearableConnectionState.CONNECTED
        } else {
            udpClient.stop()
            _state.value = WearableConnectionState.DISCONNECTED
        }
        return started
    }

    private fun getLocalIp(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address) {
                        if (addr.hostAddress.startsWith("192.168.4.")) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Esp32Session", "Failed to get local IP: ${e.message}")
        }
        return null
    }

    override suspend fun send(command: WearableCommand): CommandResult {
        return when (command) {
            is WearableCommand.StartVideoStream -> {
                val localIp = getLocalIp()
                udpClient.startListening(scope, "192.168.4.1")
                val started = controlClient.startUdpStream(8554, localIp)
                if (started) {
                    _state.value = WearableConnectionState.CONNECTED
                    CommandResult.Accepted
                } else {
                    CommandResult.Failed(Exception("Failed to start ESP32 UDP stream"))
                }
            }
            is WearableCommand.StopVideoStream -> {
                controlClient.stopUdpStream()
                udpClient.stop()
                _state.value = WearableConnectionState.CONNECTED
                CommandResult.Accepted
            }
            else -> CommandResult.Rejected("Unsupported command: ${command::class.simpleName}")
        }
    }

    override suspend fun close() {
        controlClient.stopUdpStream()
        udpClient.stop()
        _state.value = WearableConnectionState.DISCONNECTED
    }
}
