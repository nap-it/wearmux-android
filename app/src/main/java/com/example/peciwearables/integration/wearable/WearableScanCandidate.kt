package com.example.peciwearables.integration.wearable

import android.bluetooth.BluetoothDevice
import java.util.UUID

/**
 * Resultado de um BLE scan, antes de qualquer connect/discoverServices.
 *
 * Os campos espelham o que está disponível em advertising data. Não há garantias
 * de que o dispositivo realmente exporá os services anunciados (scan record pode
 * estar truncado ou desatualizado), por isso este candidate só é suficiente para
 * um match *provável* — a confirmação acontece em [WearableConnectedDeviceProfile].
 */
data class WearableScanCandidate(
    val id: WearableId,
    val displayName: String,
    val rssi: Int,
    val advertisedServiceUuids: Set<UUID>,
    val manufacturerData: Map<Int, ByteArray>,
    val serviceData: Map<UUID, ByteArray>,
    val raw: BluetoothDevice?,
)
