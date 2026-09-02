package com.example.peciwearables.integration.wearable

import java.util.UUID

internal fun scanCandidate(
    id: String = "AA:BB:CC:DD:EE:FF",
    name: String = "Fake Device",
    rssi: Int = -50,
    serviceUuids: Set<UUID> = emptySet(),
    manufacturerData: Map<Int, ByteArray> = emptyMap(),
    serviceData: Map<UUID, ByteArray> = emptyMap(),
): WearableScanCandidate = WearableScanCandidate(
    id = WearableId(id),
    displayName = name,
    rssi = rssi,
    advertisedServiceUuids = serviceUuids,
    manufacturerData = manufacturerData,
    serviceData = serviceData,
    raw = null,
)

internal fun deviceProfile(
    scan: WearableScanCandidate = scanCandidate(),
    services: Set<UUID> = emptySet(),
    characteristics: Set<UUID> = emptySet(),
    firmwareVersion: String? = null,
    deviceName: String? = null,
    handshakeProbes: Map<String, Any?> = emptyMap(),
): WearableConnectedDeviceProfile = WearableConnectedDeviceProfile(
    scan = scan,
    discoveredServices = services,
    discoveredCharacteristics = characteristics,
    firmwareVersion = firmwareVersion,
    deviceName = deviceName,
    handshakeProbes = handshakeProbes,
)
