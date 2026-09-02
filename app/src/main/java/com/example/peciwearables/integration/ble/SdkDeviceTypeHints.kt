package com.example.peciwearables.integration.ble

import java.util.UUID

internal const val SDK_DEVICE_TYPE_GLASSES_RAW = 4
internal val SDK_SERVICE_DATA_UUID: UUID = UUID.fromString("00000000-0000-1000-8000-00805f9b34fb")

internal fun sdkDeviceTypeHintsFromManufacturerPayloads(payloads: Iterable<ByteArray>): List<Int> {
    return payloads.mapNotNull { payload ->
        if (payload.size >= 3) payload[2].toInt() and 0xFF else null
    }
}

internal fun sdkDeviceTypeHintFromServiceData(serviceData: ByteArray?): Int? {
    return serviceData?.firstOrNull()?.toInt()?.and(0xFF)
}

internal fun hasGlassesSdkDeviceTypeHint(
    manufacturerPayloads: Iterable<ByteArray>,
    serviceData: ByteArray?,
): Boolean {
    return SDK_DEVICE_TYPE_GLASSES_RAW in sdkDeviceTypeHintsFromManufacturerPayloads(manufacturerPayloads) ||
        sdkDeviceTypeHintFromServiceData(serviceData) == SDK_DEVICE_TYPE_GLASSES_RAW
}
