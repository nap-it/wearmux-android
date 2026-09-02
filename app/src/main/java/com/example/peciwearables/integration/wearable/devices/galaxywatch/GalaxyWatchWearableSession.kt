package com.example.peciwearables.integration.wearable.devices.galaxywatch

import com.example.peciwearables.integration.watch.WatchClient
import com.example.peciwearables.integration.watch.WatchProtocol
import com.example.peciwearables.integration.wearable.CommandResult
import com.example.peciwearables.integration.wearable.WearableCapability
import com.example.peciwearables.integration.wearable.WearableCommand
import com.example.peciwearables.integration.wearable.WearableConnectionState
import com.example.peciwearables.integration.wearable.WearableEvent
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sessão do Galaxy Watch — adapter para o [WatchClient] já existente.
 *
 * O `WatchClient` mantém-se como infraestrutura — esta sessão é só a tradução
 * para a "linguagem comum" `WearableEvent` / `WearableCommand`. A própria
 * `WearableService` continua a poder ler do `WatchClient` directamente para
 * compatibilidade com o painel "Galaxy Watch + Áudio" (que usa StateFlows
 * tipados, não eventos genéricos).
 */
class GalaxyWatchWearableSession(
    override val id: WearableId,
    private val displayName: String,
    private val client: WatchClient,
    private val scope: CoroutineScope,
) : WearableSession {

    override val adapterId: String = GalaxyWatchWearableAdapter.ADAPTER_ID

    /**
     * O Galaxy Watch expõe só sensores corporais e háptica — sem fotos nem
     * áudio. WiFi handoff é problema do telemóvel, não dele.
     */
    override val capabilities: Set<WearableCapability> = setOf(
        WearableCapability.IMU_STREAM,
        WearableCapability.BATTERY,
        WearableCapability.HAPTIC,
        WearableCapability.DEVICE_INFO,
    )

    private val _state = MutableStateFlow(mapClientState(client.state.value))
    override val state: StateFlow<WearableConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<WearableEvent>(
        replay = 0,
        extraBufferCapacity = 128,
    )
    override val events: SharedFlow<WearableEvent> = _events.asSharedFlow()

    private val jobs = mutableListOf<Job>()

    init {
        // Wire StateFlow do client → connectionState genérica
        // (StateFlow já só emite valores distintos — sem distinctUntilChanged)
        jobs += scope.launch {
            client.state.collect { st ->
                _state.value = mapClientState(st)
            }
        }
        // IMU: ImuSample (mg/mdps/µT×10) → WearableEvent.ImuSampleReceived (SI)
        jobs += scope.launch {
            client.imuStream.collect { s ->
                val ax = s.ax.toFloat() / 101.97f
                val ay = s.ay.toFloat() / 101.97f
                val az = s.az.toFloat() / 101.97f
                val gx = s.gx.toFloat() / 1000f
                val gy = s.gy.toFloat() / 1000f
                val gz = s.gz.toFloat() / 1000f
                val mx = s.mx.toFloat() / 10f
                val my = s.my.toFloat() / 10f
                val mz = s.mz.toFloat() / 10f
                _events.tryEmit(
                    WearableEvent.ImuSampleReceived(
                        accel = floatArrayOf(ax, ay, az),
                        gyro = floatArrayOf(gx, gy, gz),
                        mag = floatArrayOf(mx, my, mz),
                        quaternion = null,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
            }
        }
        // Bateria
        jobs += scope.launch {
            client.battery.collect { pct ->
                if (pct >= 0) _events.tryEmit(
                    WearableEvent.BatteryChanged(
                        percent = pct,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
            }
        }
        // Erros do watch
        jobs += scope.launch {
            client.lastError.collect { err ->
                if (!err.isNullOrBlank()) _events.tryEmit(
                    WearableEvent.DeviceError(
                        code = "watch_error",
                        message = err,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    override suspend fun send(command: WearableCommand): CommandResult {
        return when (command) {
            is WearableCommand.SetImuStreaming -> {
                if (command.enabled) client.requestStartImu()
                else client.requestStopImu()
                command.rateMs?.let { rateMs ->
                    val hz = (1000 / rateMs.coerceAtLeast(5)).coerceIn(20, 200)
                    client.requestSetSampleRate(hz)
                }
                CommandResult.Accepted
            }
            is WearableCommand.Vibrate -> {
                // Mapeia (durationMs, intensity) para pattern existente do watch.
                val patternId = when {
                    command.durationMs >= 800 -> WatchProtocol.VibratePattern.ALARM
                    command.durationMs >= 350 -> WatchProtocol.VibratePattern.LONG
                    command.durationMs >= 200 -> WatchProtocol.VibratePattern.DOUBLE
                    else -> WatchProtocol.VibratePattern.SHORT
                }
                val amp = command.intensity?.let { (it.coerceIn(0f, 1f) * 255).toInt() } ?: 0
                client.requestVibrate(patternId, amp)
                CommandResult.Accepted
            }
            is WearableCommand.Disconnect -> {
                // Não temos forma "limpa" de desligar o Wear DataLayer — o
                // WatchClient é singleton ligado ao serviço. Ignoramos.
                CommandResult.Accepted
            }
            // Capacidades não suportadas neste wearable
            is WearableCommand.TakePicture,
            is WearableCommand.StartVideoStream,
            WearableCommand.StopVideoStream,
            WearableCommand.StartAudioStream,
            WearableCommand.StopAudioStream -> CommandResult.Unsupported(
                when (command) {
                    is WearableCommand.TakePicture, is WearableCommand.StartVideoStream,
                    WearableCommand.StopVideoStream -> WearableCapability.IMAGE_CAPTURE
                    else -> WearableCapability.AUDIO_STREAM
                }
            )
            is WearableCommand.ConfigureWifi,
            WearableCommand.ConnectViaWifi -> CommandResult.Unsupported(WearableCapability.WIFI_HANDOFF)
        }
    }

    override suspend fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        // Não chamamos client.stop() — o WearableService é dono do client.
    }

    private fun mapClientState(s: WatchClient.State): WearableConnectionState = when (s) {
        WatchClient.State.DISCONNECTED -> WearableConnectionState.DISCONNECTED
        WatchClient.State.REMOTE -> WearableConnectionState.DISCOVERED
        WatchClient.State.AVAILABLE -> WearableConnectionState.CONNECTED
        WatchClient.State.STREAMING -> WearableConnectionState.CONNECTED
        WatchClient.State.ERROR -> WearableConnectionState.ERROR
    }
}
