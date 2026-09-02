package com.example.peciwearables.integration.watch

import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.protocol.ImuSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Liga os flows do [WatchClient] aos espelhos no companion do WearableService
 * e arranca o heartbeat de phone-state. Substitui ~80 linhas de `serviceScope.launch`
 * verboso no `onCreate`.
 */
class WatchClientObservers(
    private val scope: CoroutineScope,
    private val watch: WatchClient,
    private val imuStream: MutableSharedFlow<ImuSample>,
    private val onState: (WatchClient.State) -> Unit,
    private val onName: (String?) -> Unit,
    private val onBattery: (Int) -> Unit,
    private val onSampleRate: (Int) -> Unit,
    private val onSamplesPerSec: (Int) -> Unit,
    private val onHeartRate: (Int) -> Unit,
    private val onLastError: (String?) -> Unit,
    private val onImuSample: (ImuSample) -> Unit,
    private val onLog: (String) -> Unit,
    private val heartbeat: HeartbeatProvider,
) {
    interface HeartbeatProvider {
        val glassesState: BleDeviceState
        val wristbandState: BleDeviceState
        val phoneSensorsActive: Boolean
    }

    fun attach() {
        scope.launch {
            watch.state.collect { st ->
                onState(st)
                when (st) {
                    WatchClient.State.AVAILABLE -> onLog("⌚ Watch disponível (nearby)")
                    WatchClient.State.REMOTE -> onLog("⌚ Watch fora de alcance — só visível via cloud")
                    WatchClient.State.STREAMING -> onLog("⌚ Watch a transmitir IMU")
                    WatchClient.State.ERROR -> onLog("⌚ Watch erro: ${watch.lastError.value ?: "?"}")
                    WatchClient.State.DISCONNECTED -> onLog("⌚ Watch desligado")
                }
            }
        }
        scope.launch { watch.watchNodeName.collect(onName) }
        scope.launch { watch.battery.collect(onBattery) }
        scope.launch { watch.sampleRateHz.collect(onSampleRate) }
        scope.launch { watch.samplesPerSec.collect(onSamplesPerSec) }
        scope.launch { watch.heartRateBpm.collect(onHeartRate) }
        scope.launch { watch.lastError.collect(onLastError) }
        scope.launch {
            watch.imuStream.collect { sample ->
                imuStream.tryEmit(sample)
                onImuSample(sample)
            }
        }
        scope.launch {
            while (true) {
                try {
                    val g = heartbeat.glassesState; val w = heartbeat.wristbandState
                    if (watch.state.value != WatchClient.State.DISCONNECTED) {
                        val devCount = listOf(
                            heartbeat.phoneSensorsActive,
                            g == BleDeviceState.READY || g == BleDeviceState.CONNECTED,
                            w == BleDeviceState.READY || w == BleDeviceState.CONNECTED,
                            watch.state.value == WatchClient.State.STREAMING || watch.state.value == WatchClient.State.AVAILABLE,
                        ).count { it }
                        watch.sendPhoneState(
                            glassesStateOrdinal = g.ordinal,
                            wristbandStateOrdinal = w.ordinal,
                            phoneSensorsActive = heartbeat.phoneSensorsActive,
                            audioTestActive = false,
                            deviceCount = devCount,
                        )
                    }
                } catch (_: Exception) {}
                delay(1500)
            }
        }
    }
}
