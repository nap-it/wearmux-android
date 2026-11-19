package com.wearmux.android.integration

import com.wearmux.android.integration.adapters.BleDeviceState
import com.wearmux.android.integration.adapters.WearableDeviceSnapshot
import com.wearmux.android.integration.adapters.WearableEvent
import com.wearmux.android.integration.adapters.WearableId
import com.wearmux.android.integration.protocol.DeviceImuState
import com.wearmux.android.integration.protocol.ImuSample
import kotlinx.coroutines.flow.update

/**
 * Estado **por sessão** dos wearables ligados.
 *
 * O serviço expõe três níveis de estado:
 * - [_wristbandDevices] / [_glassesDevices]: uma entrada por sessão activa
 *   (com o seu MAC) — é isto que a UI consome para desenhar um objecto por
 *   dispositivo.
 * - [_deviceEvents]: todos os eventos, já identificados — o canal genérico
 *   para consumir IMU (e o resto) de cada dispositivo em separado.
 * - [_wristbandState]: o **agregado** das entradas por sessão, para os
 *   consumidores legados (tráfego IMU, modo ML, visibilidade dos cards).
 */
internal fun WearableService.onWristbandStateChanged(id: WearableId, state: BleDeviceState) {
    wristbandStatesById[id] = state
    publishAggregatedWristbandState()
    when (state) {
        BleDeviceState.READY -> {
            wristbandSensorRateAppliedMs = -1; applyMlProcessingMode(WearableService._mlProcessingLocation.value, "BS READY")
        }
        BleDeviceState.DISCONNECTED, BleDeviceState.ERROR -> {
            if (primaryWristbandId == null || primaryWristbandId == id) {
                wristbandSensorRateAppliedMs = -1
                mlProcessingCoordinator.onWristbandDisconnected()
                WearableService._latestImuSamples.update { emptyList() }
            }
        }
        else -> Unit
    }
}

internal fun WearableService.onWristbandBatteryChanged(id: WearableId, battery: Int) {
    wristbandBatteryById[id] = battery
    publishAggregatedWristbandBattery()
}

internal fun WearableService.onWristbandFirmwareChanged(id: WearableId, firmware: String) {
    wristbandFirmwareById[id] = firmware
    publishAggregatedWristbandFirmware()
}

internal fun WearableService.onWristbandDeviceChanged(snapshot: WearableDeviceSnapshot) {
    WearableService._wristbandDevices.update { current ->
        val previous = current.firstOrNull { it.id == snapshot.id }
        val merged = if (snapshot.activity.isBlank() && previous != null) {
            snapshot.copy(activity = previous.activity)
        } else {
            snapshot
        }
        val without = current.filterNot { it.id == snapshot.id }
        // Ordena por MAC para a lista da UI ser estável entre recomposições.
        (without + merged).sortedBy { it.id }
    }
}

internal fun WearableService.onWristbandActivityChanged(id: WearableId, activity: String) {
    wristbandActivityById[id] = activity
    WearableService.wristbandActivity.value = activity
    WearableService._wristbandDevices.update { current ->
        current.map { if (it.id == id.raw) it.copy(activity = activity) else it }
    }
}

internal fun WearableService.onGlassesDeviceChanged(snapshot: WearableDeviceSnapshot) {
    WearableService._glassesDevices.update { current ->
        val without = current.filterNot { it.id == snapshot.id }
        (without + snapshot).sortedBy { it.id }
    }
}

internal fun WearableService.onDeviceEvent(id: WearableId, event: WearableEvent) {
    WearableService._deviceEvents.tryEmit(DeviceEvent(id, event))
}

internal fun WearableService.onWearableSessionRemoved(id: WearableId) {
    WearableService._wristbandDevices.update { current -> current.filterNot { it.id == id.raw } }
    WearableService._glassesDevices.update { current -> current.filterNot { it.id == id.raw } }
    WearableService._imuByDevice.update { current -> current - id.raw }
    imuTracker.remove(id.raw)
    wristbandStatesById.remove(id)
    wristbandBatteryById.remove(id)
    wristbandFirmwareById.remove(id)
    wristbandActivityById.remove(id)
    if (primaryWristbandId == id) {
        primaryWristbandId = WearableService._wristbandDevices.value
            .firstOrNull { it.id != id.raw }
            ?.id
            ?.let { WearableId(it) }
    }
    publishAggregatedWristbandState()
    publishAggregatedWristbandBattery()
    publishAggregatedWristbandFirmware()
}

internal fun WearableService.recordImuSample(id: WearableId, sample: ImuSample) {
    val state = imuTracker.record(id.raw, sample)
    WearableService._imuByDevice.update { current -> current + (id.raw to state) }
}

internal fun aggregateWristbandState(states: Collection<BleDeviceState>): BleDeviceState = when {
    states.isEmpty() -> BleDeviceState.DISCONNECTED
    states.any { it == BleDeviceState.CONNECTED || it == BleDeviceState.READY } -> BleDeviceState.CONNECTED
    states.any { it == BleDeviceState.CONNECTING || it == BleDeviceState.DISCOVERING || it == BleDeviceState.CONFIGURING } -> BleDeviceState.CONNECTING
    states.any { it == BleDeviceState.ERROR } -> BleDeviceState.ERROR
    else -> BleDeviceState.DISCONNECTED
}

private fun WearableService.publishAggregatedWristbandState() {
    val aggregated = aggregateWristbandState(wristbandStatesById.values)
    if (WearableService._wristbandState.value != aggregated) {
        WearableService._wristbandState.value = aggregated
    }
}

private fun WearableService.publishAggregatedWristbandBattery() {
    val battery = wristbandBatteryById.values.firstOrNull { it >= 0 } ?: -1
    if (WearableService._wristbandBattery.value != battery) {
        WearableService._wristbandBattery.value = battery
    }
}

private fun WearableService.publishAggregatedWristbandFirmware() {
    val firmware = wristbandFirmwareById.values.firstOrNull { it.isNotBlank() } ?: ""
    if (WearableService._wristbandFirmware.value != firmware) {
        WearableService._wristbandFirmware.value = firmware
    }
}
