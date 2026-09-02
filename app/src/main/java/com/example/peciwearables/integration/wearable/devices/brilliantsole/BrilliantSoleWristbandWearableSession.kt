package com.example.peciwearables.integration.wearable.devices.brilliantsole

import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.ble.devices.brilliantsole.BrilliantSoleCallbackMultiplexer
import com.example.peciwearables.integration.ble.devices.brilliantsole.BrilliantSoleWristbandBleClientApi
import com.example.peciwearables.integration.wearable.CommandResult
import com.example.peciwearables.integration.wearable.WearableCapability
import com.example.peciwearables.integration.wearable.WearableCommand
import com.example.peciwearables.integration.wearable.WearableConnectedDeviceProfile
import com.example.peciwearables.integration.wearable.WearableConnectionState
import com.example.peciwearables.integration.wearable.WearableEvent
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableRealtimeSink
import com.example.peciwearables.integration.wearable.WearableSession
import kotlinx.coroutines.CoroutineScope
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

class BrilliantSoleWristbandWearableSession(
    override val id: WearableId,
    profile: WearableConnectedDeviceProfile,
    private val client: BrilliantSoleWristbandBleClientApi,
    private val scope: CoroutineScope,
    private val realtimeSink: WearableRealtimeSink = WearableRealtimeSink.NONE,
    val callbackMultiplexer: BrilliantSoleCallbackMultiplexer = BrilliantSoleCallbackMultiplexer(client, scope),
) : WearableSession {

    override val adapterId: String = BrilliantSoleWristbandWearableAdapter.ADAPTER_ID

    companion object {
        private val BATTERY_LEVEL_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_REV_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        private const val IMU_QUEUE_CAPACITY = 48
    }

    override val capabilities: Set<WearableCapability> = buildSet {
        val chars = profile.discoveredCharacteristics
        if (BATTERY_LEVEL_UUID in chars) add(WearableCapability.BATTERY)
        if (FIRMWARE_REV_UUID in chars) add(WearableCapability.DEVICE_INFO)
        add(WearableCapability.IMU_STREAM) // core do device
        if (profile.handshakeProbes["hapticSupported"] == true) add(WearableCapability.HAPTIC)
        if (profile.handshakeProbes["tfliteReady"] == true) add(WearableCapability.ON_DEVICE_ML)
    }

    private val _state = MutableStateFlow(WearableConnectionState.CONNECTED)
    override val state: StateFlow<WearableConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<WearableEvent>(
        replay = 0,
        extraBufferCapacity = 128,
    )
    override val events: SharedFlow<WearableEvent> = _events.asSharedFlow()

    private val jobs = mutableListOf<Job>()
    private val imuRealtimeQueue = Channel<ImuRealtimeEvent>(
        capacity = IMU_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        startRealtimeWorker()
        wireRealtimeCallbacksDirect()
        wireStateFlows()
    }

    private fun wireRealtimeCallbacksDirect() {
        client.onImuSample = { sample, timestampRaw16, timestampEstimatedMs ->
            imuRealtimeQueue.trySend(
                ImuRealtimeEvent.Sample(
                    sample = sample,
                    timestampRaw16 = timestampRaw16,
                    timestampEstimatedMs = timestampEstimatedMs,
                ),
            )
        }
        client.onTfliteInference = { label, score, timestampEstimatedMs, values ->
            imuRealtimeQueue.trySend(
                ImuRealtimeEvent.Inference(
                    label = label,
                    score = score,
                    timestampEstimatedMs = timestampEstimatedMs,
                    values = values,
                ),
            )
        }
    }

    private fun startRealtimeWorker() {
        jobs += scope.launch {
            for (event in imuRealtimeQueue) {
                when (event) {
                    is ImuRealtimeEvent.Sample -> {
                        val sample = event.sample
                        realtimeSink.onImuSample(id, sample, event.timestampRaw16, event.timestampEstimatedMs)
                        _events.tryEmit(
                            WearableEvent.ImuSampleReceived(
                                accel = floatArrayOf(sample.ax / 1000f, sample.ay / 1000f, sample.az / 1000f),
                                gyro = floatArrayOf(sample.gx / 1000f, sample.gy / 1000f, sample.gz / 1000f),
                                mag = if (sample.mx != 0.toShort() || sample.my != 0.toShort() || sample.mz != 0.toShort())
                                    floatArrayOf(sample.mx / 10f, sample.my / 10f, sample.mz / 10f)
                                else null,
                                timestampMs = event.timestampEstimatedMs,
                            ),
                        )
                    }
                    is ImuRealtimeEvent.Inference -> {
                        realtimeSink.onTfliteInference(
                            id = id,
                            label = event.label,
                            score = event.score,
                            timestampEstimatedMs = event.timestampEstimatedMs,
                            values = event.values,
                        )
                        _events.tryEmit(
                            WearableEvent.MlInferenceReceived(
                                label = event.label,
                                score = event.score,
                                values = event.values,
                                timestampMs = event.timestampEstimatedMs,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun wireStateFlows() {
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
                            name = client.deviceName.value.takeIf { it.isNotEmpty() },
                            timestampMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun send(command: WearableCommand): CommandResult {
        return when (command) {
            is WearableCommand.Vibrate -> {
                if (WearableCapability.HAPTIC !in capabilities) {
                    return CommandResult.Unsupported(WearableCapability.HAPTIC)
                }
                val durationMs = command.durationMs
                val amplitude = command.intensity ?: 1.0f
                client.sendWaveformVibration(amplitude, durationMs, 0x03)
                CommandResult.Accepted
            }
            is WearableCommand.SetImuStreaming -> {
                client.setSensorsConfig(
                    rateMs = command.rateMs ?: 20,
                    includeMagnetometer = false,
                    includePressure = false,
                    includeLinearAcceleration = false,
                )
                CommandResult.Accepted
            }
            is WearableCommand.Disconnect -> {
                client.disconnect()
                CommandResult.Accepted
            }
            is WearableCommand.TakePicture -> CommandResult.Unsupported(WearableCapability.IMAGE_CAPTURE)
            is WearableCommand.StartVideoStream -> CommandResult.Unsupported(WearableCapability.VIDEO_STREAM)
            is WearableCommand.StopVideoStream -> CommandResult.Unsupported(WearableCapability.VIDEO_STREAM)
            is WearableCommand.StartAudioStream -> CommandResult.Unsupported(WearableCapability.AUDIO_STREAM)
            is WearableCommand.StopAudioStream -> CommandResult.Unsupported(WearableCapability.AUDIO_STREAM)
            is WearableCommand.ConfigureWifi -> CommandResult.Unsupported(WearableCapability.WIFI_HANDOFF)
            is WearableCommand.ConnectViaWifi -> CommandResult.Unsupported(WearableCapability.WIFI_HANDOFF)
        }
    }

    override suspend fun close() {
        imuRealtimeQueue.close()
        jobs.forEach { it.cancel() }
        jobs.clear()
        client.disconnect()
    }

    private sealed interface ImuRealtimeEvent {
        data class Sample(
            val sample: com.example.peciwearables.integration.protocol.ImuSample,
            val timestampRaw16: Int,
            val timestampEstimatedMs: Long,
        ) : ImuRealtimeEvent

        data class Inference(
            val label: String,
            val score: Float,
            val timestampEstimatedMs: Long,
            val values: FloatArray,
        ) : ImuRealtimeEvent
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
