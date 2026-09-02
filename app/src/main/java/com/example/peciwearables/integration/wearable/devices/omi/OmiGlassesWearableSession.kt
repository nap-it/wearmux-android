package com.example.peciwearables.integration.wearable.devices.omi

import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.ble.devices.omi.OmiGlassesBleClientApi
import com.example.peciwearables.integration.ble.devices.omi.OmiCallbackMultiplexer
import com.example.peciwearables.integration.wearable.AudioCodec
import com.example.peciwearables.integration.wearable.CommandResult
import com.example.peciwearables.integration.wearable.ImageFormat
import com.example.peciwearables.integration.wearable.WearableCapability
import com.example.peciwearables.integration.wearable.WearableCommand
import com.example.peciwearables.integration.wearable.WearableConnectedDeviceProfile
import com.example.peciwearables.integration.wearable.WearableConnectionState
import com.example.peciwearables.integration.wearable.WearableEvent
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableRealtimeSink
import com.example.peciwearables.integration.wearable.WearableSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class OmiGlassesWearableSession(
    override val id: WearableId,
    profile: WearableConnectedDeviceProfile,
    private val client: OmiGlassesBleClientApi,
    private val scope: CoroutineScope,
    private val realtimeSink: WearableRealtimeSink = WearableRealtimeSink.NONE,
    val callbackMultiplexer: OmiCallbackMultiplexer = OmiCallbackMultiplexer(client, scope),
) : WearableSession {

    override val adapterId: String = OmiGlassesWearableAdapter.ADAPTER_ID

    companion object {
        private val BATTERY_LEVEL_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_REV_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        private val PHOTO_CONTROL_UUID = UUID.fromString("19B10006-E8F2-537E-4F6C-D104768A1214")
        private val AUDIO_DATA_UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
        private val IMU_DATA_UUID = UUID.fromString("19B10003-E8F2-537E-4F6C-D104768A1214")
        private val SDK_TX_UUID = UUID.fromString("ea6d1001-a725-4f9b-893d-c3913e33b39f")
        private const val CAMERA_QUEUE_CAPACITY = 256
        // Subimos de 96 para 384 (~6s a 16ms/pacote) porque sob vídeo+áudio
        // simultâneos a queue enchia rapidamente e o DROP_OLDEST estava a
        // deitar pacotes fora silenciosamente, ouvindo-se "todo cortado".
        // Capacity maior absorve rajadas sem perder amostras.
        private const val AUDIO_QUEUE_CAPACITY = 384
        private const val IMU_QUEUE_CAPACITY = 32
    }

    override val capabilities: Set<WearableCapability> = buildSet {
        val chars = profile.discoveredCharacteristics
        if (BATTERY_LEVEL_UUID in chars) add(WearableCapability.BATTERY)
        if (FIRMWARE_REV_UUID in chars) add(WearableCapability.DEVICE_INFO)
        if (PHOTO_CONTROL_UUID in chars || SDK_TX_UUID in chars) add(WearableCapability.IMAGE_CAPTURE)
        if (SDK_TX_UUID in chars) add(WearableCapability.VIDEO_STREAM)
        if (AUDIO_DATA_UUID in chars || SDK_TX_UUID in chars) add(WearableCapability.AUDIO_STREAM)
        if (IMU_DATA_UUID in chars || SDK_TX_UUID in chars) add(WearableCapability.IMU_STREAM)
        if (profile.handshakeProbes["wifiSupported"] == true) add(WearableCapability.WIFI_HANDOFF)
        // ON_DEVICE_ML não declarado para Omi actual
    }

    private val _state = MutableStateFlow(WearableConnectionState.CONNECTED)
    override val state: StateFlow<WearableConnectionState> = _state.asStateFlow()

    // extraBufferCapacity permite tryEmit sem bloquear; suficiente para callbacks BLE
    private val _events = MutableSharedFlow<WearableEvent>(
        replay = 0,
        extraBufferCapacity = 128,
    )
    override val events: SharedFlow<WearableEvent> = _events.asSharedFlow()

    private var currentCodecId: Int = 2 // default: MULAW
    private var currentSampleRateHz: Int = 16_000
    private var currentBitDepth: Int = 8
    private val jobs = mutableListOf<Job>()
    private val cameraRealtimeQueue = Channel<CameraRealtimeEvent>(
        capacity = CAMERA_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    // DROP_OLDEST consistente com os outros canais. Se o consumidor não
    // acompanhar perdemos o pacote mais velho, o que é preferível a executar
    // a entrega na thread de callback do BLE (que era o que o fallback
    // síncrono anterior fazia e arriscava bloquear o BLE inteiro).
    private val audioRealtimeQueue = Channel<AudioRealtimeEvent>(
        capacity = AUDIO_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val imuRealtimeQueue = Channel<ImuRealtimeEvent>(
        capacity = IMU_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        startRealtimeWorkers()
        wireRealtimeCallbacksDirect()
        wireCallbacks()
        wireStateFlows()
    }

    private fun wireRealtimeCallbacksDirect() {
        client.onPhotoReceived = { bytes, orientation ->
            cameraRealtimeQueue.trySend(CameraRealtimeEvent.PhotoJpeg(bytes = bytes, orientationDeg = orientation))
        }
        client.onCameraData = { subType, bytes ->
            cameraRealtimeQueue.trySend(CameraRealtimeEvent.CameraChunk(subType = subType, bytes = bytes))
        }
        client.onCameraImageFileReceived = { bytes ->
            cameraRealtimeQueue.trySend(CameraRealtimeEvent.CameraImageFile(bytes = bytes))
        }
        client.onAudioPacket = { bytes ->
            audioRealtimeQueue.trySend(AudioRealtimeEvent.AudioPacket(bytes))
        }
        client.onImuData = { bytes ->
            imuRealtimeQueue.trySend(ImuRealtimeEvent.ImuPacket(bytes))
        }
        client.onGlassesQuaternion = { quaternion ->
            imuRealtimeQueue.trySend(ImuRealtimeEvent.Quaternion(quaternion))
        }
    }

    private fun startRealtimeWorkers() {
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (event in cameraRealtimeQueue) {
                when (event) {
                    is CameraRealtimeEvent.CameraChunk -> {
                        realtimeSink.onCameraChunk(id, event.subType, event.bytes)
                    }
                    is CameraRealtimeEvent.CameraImageFile -> {
                        realtimeSink.onCameraImageFile(id, event.bytes)
                        _events.tryEmit(
                            WearableEvent.ImageFrameReceived(
                                bytes = event.bytes,
                                format = ImageFormat.JPEG,
                                timestampMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                    is CameraRealtimeEvent.PhotoJpeg -> {
                        realtimeSink.onPhotoJpeg(id, event.bytes, event.orientationDeg)
                        _events.tryEmit(
                            WearableEvent.ImageFrameReceived(
                                bytes = event.bytes,
                                format = ImageFormat.JPEG,
                                orientationDeg = event.orientationDeg,
                                timestampMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }
        }

        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (event in audioRealtimeQueue) {
                when (event) {
                    is AudioRealtimeEvent.AudioPacket -> {
                        emitAudioPacket(event.bytes)
                    }
                }
            }
        }

        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (event in imuRealtimeQueue) {
                when (event) {
                    is ImuRealtimeEvent.ImuPacket -> {
                        val bytes = event.bytes
                        realtimeSink.onImuPacket(id, bytes)
                        if (bytes.size >= 6) {
                            val accel = floatArrayOf(
                                shortFromLe(bytes, 0) / 1000f,
                                shortFromLe(bytes, 2) / 1000f,
                                shortFromLe(bytes, 4) / 1000f,
                            )
                            _events.tryEmit(
                                WearableEvent.ImuSampleReceived(
                                    accel = accel,
                                    gyro = floatArrayOf(0f, 0f, 0f),
                                    timestampMs = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                    is ImuRealtimeEvent.Quaternion -> {
                        realtimeSink.onQuaternion(id, event.values)
                        _events.tryEmit(
                            WearableEvent.ImuSampleReceived(
                                accel = floatArrayOf(0f, 0f, 0f),
                                gyro = floatArrayOf(0f, 0f, 0f),
                                quaternion = event.values,
                                timestampMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun emitAudioPacket(bytes: ByteArray) {
        realtimeSink.onAudioPacket(id, bytes, currentCodecId)
        _events.tryEmit(
            WearableEvent.AudioFrameReceived(
                bytes = bytes,
                codec = mapCodec(currentCodecId),
                sampleRateHz = currentSampleRateHz,
                channels = 1,
                timestampMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun wireCallbacks() {
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            callbackMultiplexer.audioCodecId.collect { id -> currentCodecId = id }
        }
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            callbackMultiplexer.microphoneConfig.collect { (sampleRate, bitDepth, codecId) ->
                currentSampleRateHz = sampleRate
                currentBitDepth = bitDepth
                currentCodecId = codecId
            }
        }
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            callbackMultiplexer.cameraStatus.collect { status ->
                _events.emit(
                    WearableEvent.DeviceLog(
                        level = WearableEvent.LogLevel.INFO,
                        message = "cameraStatus=$status",
                        timestampMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            callbackMultiplexer.glassesIp.collect { ip ->
                _events.emit(
                    WearableEvent.DeviceLog(
                        level = WearableEvent.LogLevel.INFO,
                        message = "glassesIp=$ip",
                        timestampMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun wireStateFlows() {
        // StateFlows do client emitidos por coroutines — normais em runTest
        jobs += scope.launch {
            client.state.collect { bleState ->
                _state.value = bleState.toWearable()
                if (bleState == BleDeviceState.ERROR || bleState == BleDeviceState.DISCONNECTED) {
                    _events.emit(
                        WearableEvent.ConnectionChanged(
                            state = bleState.toWearable(),
                            timestampMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
        jobs += scope.launch {
            client.batteryLevel.collect { pct ->
                if (pct >= 0) {
                    _events.emit(
                        WearableEvent.BatteryChanged(
                            percent = pct,
                            timestampMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
        jobs += scope.launch {
            client.firmwareVersion.collect { fw ->
                if (fw.isNotEmpty()) {
                    _events.emit(
                        WearableEvent.DeviceInfoReceived(
                            firmware = fw,
                            timestampMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun send(command: WearableCommand): CommandResult {
        return when (command) {
            is WearableCommand.TakePicture -> {
                if (WearableCapability.IMAGE_CAPTURE !in capabilities) {
                    return CommandResult.Unsupported(WearableCapability.IMAGE_CAPTURE)
                }
                client.takePicture()
                CommandResult.Accepted
            }
            is WearableCommand.StartVideoStream -> {
                if (WearableCapability.VIDEO_STREAM !in capabilities) {
                    return CommandResult.Unsupported(WearableCapability.VIDEO_STREAM)
                }
                client.setStreamMode(enable = true, targetFps = command.targetFps)
                CommandResult.Accepted
            }
            is WearableCommand.StopVideoStream -> {
                client.setStreamMode(enable = false)
                CommandResult.Accepted
            }
            is WearableCommand.StartAudioStream -> {
                if (WearableCapability.AUDIO_STREAM !in capabilities) {
                    return CommandResult.Unsupported(WearableCapability.AUDIO_STREAM)
                }
                client.startMicrophone()
                CommandResult.Accepted
            }
            is WearableCommand.StopAudioStream -> {
                client.stopMicrophone()
                CommandResult.Accepted
            }
            is WearableCommand.SetImuStreaming -> {
                if (WearableCapability.IMU_STREAM !in capabilities) {
                    return CommandResult.Unsupported(WearableCapability.IMU_STREAM)
                }
                client.setImuStreamingEnabled(command.enabled, command.rateMs ?: 50)
                CommandResult.Accepted
            }
            is WearableCommand.ConfigureWifi -> {
                if (WearableCapability.WIFI_HANDOFF !in capabilities) {
                    return CommandResult.Unsupported(WearableCapability.WIFI_HANDOFF)
                }
                client.sendWifiCredentialsAndEnable(command.ssid, command.password)
                CommandResult.Accepted
            }
            is WearableCommand.ConnectViaWifi -> {
                if (WearableCapability.WIFI_HANDOFF !in capabilities) {
                    return CommandResult.Unsupported(WearableCapability.WIFI_HANDOFF)
                }
                CommandResult.Accepted
            }
            is WearableCommand.Disconnect -> {
                client.disconnect()
                CommandResult.Accepted
            }
            is WearableCommand.Vibrate ->
                CommandResult.Unsupported(WearableCapability.HAPTIC)
        }
    }

    override suspend fun close() {
        cameraRealtimeQueue.close()
        audioRealtimeQueue.close()
        imuRealtimeQueue.close()
        jobs.forEach { it.cancel() }
        jobs.clear()
        client.disconnect()
    }

    private fun mapCodec(id: Int): AudioCodec = when (id) {
        1 -> AudioCodec.OPUS
        2 -> AudioCodec.MULAW
        3 -> AudioCodec.PCM16
        4 -> AudioCodec.PCM8
        21 -> AudioCodec.MULAW
        else -> AudioCodec.UNKNOWN
    }

    private fun shortFromLe(bytes: ByteArray, offset: Int): Short =
        ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xFF)).toShort()

    private sealed interface CameraRealtimeEvent {
        data class CameraChunk(val subType: Int, val bytes: ByteArray) : CameraRealtimeEvent
        data class CameraImageFile(val bytes: ByteArray) : CameraRealtimeEvent
        data class PhotoJpeg(val bytes: ByteArray, val orientationDeg: Int) : CameraRealtimeEvent
    }

    private sealed interface AudioRealtimeEvent {
        data class AudioPacket(val bytes: ByteArray) : AudioRealtimeEvent
    }

    private sealed interface ImuRealtimeEvent {
        data class ImuPacket(val bytes: ByteArray) : ImuRealtimeEvent
        data class Quaternion(val values: FloatArray) : ImuRealtimeEvent
    }

    private fun BleDeviceState.toWearable(): WearableConnectionState = when (this) {
        BleDeviceState.DISCONNECTED -> WearableConnectionState.DISCONNECTED
        BleDeviceState.CONNECTING -> WearableConnectionState.CONNECTING
        BleDeviceState.DISCOVERING -> WearableConnectionState.DISCOVERING_SERVICES
        BleDeviceState.CONFIGURING -> WearableConnectionState.HANDSHAKING
        BleDeviceState.READY -> WearableConnectionState.HANDSHAKING
        BleDeviceState.CONNECTED -> WearableConnectionState.CONNECTED
        BleDeviceState.ERROR -> WearableConnectionState.ERROR
    }
}
