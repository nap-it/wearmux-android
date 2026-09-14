package com.example.peciwearables.integration.wearable

import java.util.UUID

/**
 * Perfil real do dispositivo após connectGatt + discoverServices + (opcionalmente)
 * probes de handshake. É a base do *match confirmado* — o que o adapter usa para
 * decidir definitivamente se é dele e que [WearableCapability]s declarar.
 */
data class WearableConnectedDeviceProfile(
    val scan: WearableScanCandidate,
    val discoveredServices: Set<UUID>,
    val discoveredCharacteristics: Set<UUID>,
    val firmwareVersion: String?,
    val deviceName: String?,
    /** Resultados arbitrários de probes pós-handshake (opcional, depende do adapter). */
    val handshakeProbes: Map<String, Any?> = emptyMap(),
) {
    val id: WearableId get() = scan.id
}
