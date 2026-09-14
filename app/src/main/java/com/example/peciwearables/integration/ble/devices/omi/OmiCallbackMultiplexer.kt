package com.example.peciwearables.integration.ble.devices.omi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Converte os `var onX: (...) -> Unit` do [OmiGlassesBleClientApi] (que são
 * campos atribuíveis uma só vez) em [SharedFlow]s com vários collectors.
 *
 * Uso: criar antes de atribuir lambdas ao client; o antigo consumer (WearableService
 * legacy) pode subscrever ao flow em vez de atribuir directamente o callback.
 *
 * Garante que os callbacks existentes do WearableService continuam a funcionar
 * enquanto o adapter também recebe os mesmos eventos.
 */
class OmiCallbackMultiplexer(
    private val client: OmiGlassesBleClientApi,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
) {
    private val _audioCodecId = MutableSharedFlow<Int>(replay = 1)
    val audioCodecId: SharedFlow<Int> = _audioCodecId.asSharedFlow()

    private val _cameraStatus = MutableSharedFlow<Int>(replay = 1)
    val cameraStatus: SharedFlow<Int> = _cameraStatus.asSharedFlow()

    private val _microphoneStatus = MutableSharedFlow<Int>(replay = 1)
    val microphoneStatus: SharedFlow<Int> = _microphoneStatus.asSharedFlow()

    private val _microphoneConfig = MutableSharedFlow<Triple<Int, Int, Int>>(replay = 1)
    val microphoneConfig: SharedFlow<Triple<Int, Int, Int>> = _microphoneConfig.asSharedFlow()

    private val _glassesIp = MutableSharedFlow<String>(replay = 1)
    val glassesIp: SharedFlow<String> = _glassesIp.asSharedFlow()

    /** Acesso directo ao state da conexão BLE — apenas leitura. */
    val state get() = client.state
    val batteryLevel get() = client.batteryLevel
    val firmwareVersion get() = client.firmwareVersion

    init {
        // Instalar callbacks — multiplexam para os flows acima
        client.onAudioCodecId = { id ->
            emitAudioCodecId(id)
        }
        client.onCameraStatus = { status ->
            emitCameraStatus(status)
        }
        client.onMicrophoneStatus = { status ->
            emitMicrophoneStatus(status)
        }
        client.onMicrophoneConfig = { rate, bitDepth, codecId ->
            emitMicrophoneConfig(rate, bitDepth, codecId)
        }
        client.onGlassesIpReceived = { ip ->
            emitGlassesIp(ip)
        }
    }

    fun emitAudioCodecId(id: Int) {
        _audioCodecId.tryEmit(id)
    }

    fun emitCameraStatus(status: Int) {
        _cameraStatus.tryEmit(status)
    }

    fun emitMicrophoneStatus(status: Int) {
        _microphoneStatus.tryEmit(status)
    }

    fun emitMicrophoneConfig(sampleRate: Int, bitDepth: Int, codecId: Int) {
        _microphoneConfig.tryEmit(Triple(sampleRate, bitDepth, codecId))
    }

    fun emitGlassesIp(ip: String) {
        _glassesIp.tryEmit(ip)
    }
}
