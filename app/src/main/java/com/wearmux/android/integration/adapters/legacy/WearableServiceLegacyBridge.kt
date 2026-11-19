package com.wearmux.android.integration.adapters.legacy

import com.wearmux.android.integration.adapters.BleDeviceState
import com.wearmux.android.integration.adapters.WearableDeviceSnapshot
import com.wearmux.android.integration.adapters.WearableEvent
import com.wearmux.android.integration.adapters.WearableId
import com.wearmux.android.integration.adapters.WearableSession
import com.wearmux.android.integration.adapters.devices.brilliantsole.BrilliantSoleWristbandWearableSession
import com.wearmux.android.integration.adapters.devices.esp32.Esp32WearableSession
import com.wearmux.android.integration.adapters.devices.omi.OmiGlassesWearableSession
import com.wearmux.android.integration.hub.DeviceHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class WearableServiceLegacyBridge(
    private val hub: DeviceHub,
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
        /**
         * Estado de **uma** pulseira. Recebe o [WearableId] (MAC) para que o
         * consumidor consiga manter estado por instância — com duas pulseiras
         * ligadas, um callback sem identidade faria o estado "saltar" entre elas.
         */
        val onWristbandState: (WearableId, BleDeviceState) -> Unit = { _, _ -> },
        val onWristbandBattery: (WearableId, Int) -> Unit = { _, _ -> },
        val onWristbandFirmware: (WearableId, String) -> Unit = { _, _ -> },
        /**
         * Retrato completo de **uma** pulseira (MAC + estado + bateria + fw).
         * Emite sempre que qualquer um desses valores muda.
         */
        val onWristbandDevice: (WearableDeviceSnapshot) -> Unit = {},
        /** Retrato completo de **um** par de óculos (MAC + estado + bateria + fw). */
        val onGlassesDevice: (WearableDeviceSnapshot) -> Unit = {},
        /** Chamado quando uma sessão sai do hub — a UI tem de a remover da lista. */
        val onSessionRemoved: (WearableId) -> Unit = {},
        /**
         * Evento genérico de um dispositivo, já com a sua identidade.
         *
         * Cobre tudo o que a sessão emite — IMU, inferência ML, bateria,
         * firmware, erros — para que cada dispositivo possa ser consumido em
         * separado.
         */
        val onDeviceEvent: (WearableId, WearableEvent) -> Unit = { _, _ -> },
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
                    callbacks.onSessionRemoved(id)
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
        val specific = when (session) {
            is OmiGlassesWearableSession -> attachGlasses(session)
            is BrilliantSoleWristbandWearableSession -> attachWristband(session)
            is Esp32WearableSession -> attachEsp32(session)
            else -> emptyList()
        }
        // Canal genérico: TODOS os eventos da sessão, já identificados.
        //
        // É este canal que dá IMU (e inferência, bateria, firmware, erros)
        // **por dispositivo** — o contrato [WearableSession.events] já é
        // por sessão, o que faltava era republicá-lo com o id.
        val events = listOf(
            scope.launch {
                session.events.collect { event -> callbacks.onDeviceEvent(session.id, event) }
            },
        )
        return specific + events
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
            // Retrato por sessão, para a UI poder mostrar cada óculos pelo MAC.
            scope.launch {
                combine(mux.state, mux.batteryLevel, mux.firmwareVersion) { state, battery, firmware ->
                    WearableDeviceSnapshot(
                        id = session.id.raw,
                        state = state,
                        battery = battery,
                        firmware = firmware,
                    )
                }.collect { snapshot -> callbacks.onGlassesDevice(snapshot) }
            },
        )
    }

    private fun attachWristband(session: BrilliantSoleWristbandWearableSession): List<Job> {
        val mux = session.callbackMultiplexer
        // Os três sinais são combinados num único retrato por sessão: assim a UI
        // recebe sempre dados coerentes e não estados "a meio".
        return listOf(
            scope.launch {
                combine(mux.state, mux.batteryLevel, mux.firmwareVersion) { state, battery, firmware ->
                    WearableDeviceSnapshot(
                        id = session.id.raw,
                        state = state,
                        battery = battery,
                        firmware = firmware,
                    )
                }.collect { snapshot ->
                    callbacks.onWristbandDevice(snapshot)
                    callbacks.onWristbandState(session.id, snapshot.state)
                    callbacks.onWristbandBattery(session.id, snapshot.battery)
                    callbacks.onWristbandFirmware(session.id, snapshot.firmware)
                }
            },
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

private fun com.wearmux.android.integration.adapters.WearableConnectionState.toBleState(): BleDeviceState {
    return when (this) {
        com.wearmux.android.integration.adapters.WearableConnectionState.CONNECTED -> BleDeviceState.CONNECTED
        com.wearmux.android.integration.adapters.WearableConnectionState.CONNECTING -> BleDeviceState.CONNECTING
        else -> BleDeviceState.DISCONNECTED
    }
}
