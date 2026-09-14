package com.example.peciwearables.integration.wearable

import android.bluetooth.BluetoothGatt
import java.util.UUID

data class GattProfileSnapshot(
    val discoveredServices: Set<UUID>,
    val discoveredCharacteristics: Set<UUID>,
    val firmwareVersion: String? = null,
    val deviceName: String? = null,
    val handshakeProbes: Map<String, Any?> = emptyMap(),
)

object GattProfileObserver {
    fun snapshot(
        gatt: BluetoothGatt,
        firmwareVersion: String? = null,
        deviceName: String? = null,
        handshakeProbes: Map<String, Any?> = emptyMap(),
    ): GattProfileSnapshot {
        val services = gatt.services.map { it.uuid }.toSet()
        val characteristics = gatt.services
            .flatMap { service -> service.characteristics.map { it.uuid } }
            .toSet()
        return GattProfileSnapshot(
            discoveredServices = services,
            discoveredCharacteristics = characteristics,
            firmwareVersion = firmwareVersion?.takeIf { it.isNotBlank() },
            deviceName = deviceName?.takeIf { it.isNotBlank() },
            handshakeProbes = handshakeProbes,
        )
    }

    fun connectedProfile(
        scan: WearableScanCandidate,
        snapshot: GattProfileSnapshot,
    ): WearableConnectedDeviceProfile = WearableConnectedDeviceProfile(
        scan = scan,
        discoveredServices = snapshot.discoveredServices,
        discoveredCharacteristics = snapshot.discoveredCharacteristics,
        firmwareVersion = snapshot.firmwareVersion,
        deviceName = snapshot.deviceName,
        handshakeProbes = snapshot.handshakeProbes,
    )
}

