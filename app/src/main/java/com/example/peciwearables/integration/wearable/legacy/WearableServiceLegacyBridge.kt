package com.example.peciwearables.integration.wearable.legacy

import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.wearable.WearableHub
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableSession
import com.example.peciwearables.integration.wearable.devices.brilliantsole.BrilliantSoleWristbandWearableSession
import com.example.peciwearables.integration.wearable.devices.esp32.Esp32WearableSession
import com.example.peciwearables.integration.wearable.devices.omi.OmiGlassesWearableSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WearableServiceLegacyBridge(
    private val hub: WearableHub,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val onGlassesState: (BleDeviceState) -> Unit = {},
        val onGlassesBattery: (Int) -> Unit = {},
        val onGlassesFirmware: (String) -> Unit = {},
        val onGlassesAudioCodecId: (Int) -> Unit = {},
        val onGlassesMicrophoneConfig: (Int, Int, Int) -> Unit = { _, _, _ -> },
        val onGlassesMicrophoneStatus: (Int) -> Unit = {},
        val onGlassesCameraStatus: (Int) -> Unit = {},
        val onGlassesIp: (String) -> Unit = {},
        val onWristbandState: (BleDeviceState) -> Unit = {},
        val onWristbandBattery: (Int) -> Unit = {},
        val onWristbandFirmware: (String) -> Unit = {},
        val onEsp32State: (BleDeviceState) -> Unit = {},
    )

    private val sessionJobs = mutableMapOf<WearableId, List<Job>>()
    private var observeJob: Job? = null

    fun start() {
        if (observeJob != null) return
        observeJob = scope.launch {
            hub.sessions.collect { sessions ->
                val activeIds = sessions.keys
                val removedIds = sessionJobs.keys - activeIds
                removedIds.forEach { id ->
                    sessionJobs.remove(id)?.forEach { it.cancel() }
                }
                sessions.values.forEach { session ->
                    if (session.id !in sessionJobs) {
                        sessionJobs[session.id] = attach(session)
                    }
                }
            }
        }
    }

    fun stop() {
        observeJob?.cancel()
        observeJob = null
        sessionJobs.values.flatten().forEach { it.cancel() }
        sessionJobs.clear()
    }

    private fun attach(session: WearableSession): List<Job> {
        return when (session) {
            is OmiGlassesWearableSession -> attachGlasses(session)
            is BrilliantSoleWristbandWearableSession -> attachWristband(session)
            is Esp32WearableSession -> attachEsp32(session)
            else -> emptyList()
        }
    }

    private fun attachGlasses(session: OmiGlassesWearableSession): List<Job> {
        val mux = session.callbackMultiplexer
        return listOf(
            scope.launch { mux.state.collect { callbacks.onGlassesState(it) } },
            scope.launch { mux.batteryLevel.collect { callbacks.onGlassesBattery(it) } },
            scope.launch { mux.firmwareVersion.collect { callbacks.onGlassesFirmware(it) } },
            scope.launch { mux.audioCodecId.collect { callbacks.onGlassesAudioCodecId(it) } },
            scope.launch { mux.microphoneConfig.collect { callbacks.onGlassesMicrophoneConfig(it.first, it.second, it.third) } },
            scope.launch { mux.microphoneStatus.collect { callbacks.onGlassesMicrophoneStatus(it) } },
            scope.launch { mux.cameraStatus.collect { callbacks.onGlassesCameraStatus(it) } },
            scope.launch { mux.glassesIp.collect { callbacks.onGlassesIp(it) } },
        )
    }

    private fun attachWristband(session: BrilliantSoleWristbandWearableSession): List<Job> {
        val mux = session.callbackMultiplexer
        return listOf(
            scope.launch { mux.state.collect { callbacks.onWristbandState(it) } },
            scope.launch { mux.batteryLevel.collect { callbacks.onWristbandBattery(it) } },
            scope.launch { mux.firmwareVersion.collect { callbacks.onWristbandFirmware(it) } },
        )
    }

    private fun attachEsp32(session: Esp32WearableSession): List<Job> {
        return listOf(
            scope.launch {
                session.state.collect { callbacks.onEsp32State(it.toBleState()) }
            }
        )
    }
}

private fun com.example.peciwearables.integration.wearable.WearableConnectionState.toBleState(): BleDeviceState {
    return when (this) {
        com.example.peciwearables.integration.wearable.WearableConnectionState.CONNECTED -> BleDeviceState.CONNECTED
        com.example.peciwearables.integration.wearable.WearableConnectionState.CONNECTING -> BleDeviceState.CONNECTING
        else -> BleDeviceState.DISCONNECTED
    }
}
