package com.example.peciwearables.integration.wearable

import com.example.peciwearables.integration.ble.CapabilityScanCandidate
import com.example.peciwearables.integration.ble.WearableKind
import java.util.UUID

private val OMI_SERVICE_UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
private val BS_SERVICE_UUID = UUID.fromString("ea6d0000-a725-4f9b-893d-c3913e33b39f")

/**
 * Converte um [CapabilityScanCandidate] (produto do [CapabilityDiscoveryScanner])
 * num [WearableScanCandidate] genérico, sem alterar o scanner existente.
 *
 * O scanner filtra por UUID (OMI ou BS), por isso o kind é suficiente para reconstruir
 * o UUID provável. O sdkDeviceType é codificado em manufacturerData para que os adapters
 * possam fazer `probableMatch` da mesma forma que fariam com dados reais.
 */
fun CapabilityScanCandidate.toWearableScanCandidate(): WearableScanCandidate {
    val uuids = if (advertisedServiceUuids.isNotEmpty()) {
        advertisedServiceUuids
    } else {
        when (kind) {
            WearableKind.GLASSES -> setOf(OMI_SERVICE_UUID)
            WearableKind.WRIST_OR_SOLE -> setOf(BS_SERVICE_UUID)
            WearableKind.UNKNOWN -> emptySet()
        }
    }
    // Manufacturer data sintético com sdkDeviceType em byte[2] (formato real do firmware)
    val mfgData = sdkDeviceType?.let {
        mapOf(-1 to byteArrayOf(0x00, 0x00, it.rawValue.toByte()))
    } ?: emptyMap()

    return WearableScanCandidate(
        id = WearableId(address),
        displayName = name,
        rssi = rssi,
        advertisedServiceUuids = uuids,
        manufacturerData = mfgData,
        serviceData = emptyMap(),
        raw = device,
    )
}
