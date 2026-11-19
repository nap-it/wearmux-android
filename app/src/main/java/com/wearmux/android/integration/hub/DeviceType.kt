package com.wearmux.android.integration.hub

import java.util.UUID // Para o UUID.fromString
import com.wearmux.android.integration.adapters.WearableScanCandidate // Para a info do scan
// Esta importação abaixo é importante, verifica se o package está correto no teu projeto
import com.wearmux.android.integration.adapters.sdkDeviceTypeHintsFromManufacturerPayloads 
import com.wearmux.android.integration.adapters.sdkDeviceTypeHintFromServiceData
import com.wearmux.android.integration.adapters.SDK_SERVICE_DATA_UUID
import com.wearmux.android.integration.adapters.SDK_DEVICE_TYPE_GLASSES_RAW

/**
 * Categorias de dispositivos suportados pelo WearMux.
 */


enum class DeviceType {
    WRISTBAND_BRILLIANT_SOLE,
    GLASSES_OMI,
    WATCH_GALAXY,
    ESP32_CAMERA,
    UNKNOWN;

    companion object {
        fun fromScan(scan: WearableScanCandidate): DeviceType {
            // O ESP32-CAM é sintético (ligação Wi-Fi manual) — não anuncia BLE,
            // por isso só se reconhece pelo id/nome que o próprio app atribui.
            if (scan.id.raw.startsWith("esp32-cam-") || scan.displayName.contains("ESP32-CAM", ignoreCase = true)) {
                return ESP32_CAMERA
            }

            // Omi tem o SDK Type 4 (SDK_DEVICE_TYPE_GLASSES_RAW)
            val hints = sdkDeviceTypeHintsFromManufacturerPayloads(scan.manufacturerData.values) + 
                        listOfNotNull(sdkDeviceTypeHintFromServiceData(scan.serviceData[SDK_SERVICE_DATA_UUID]))
            
            if (hints.contains(SDK_DEVICE_TYPE_GLASSES_RAW)) return GLASSES_OMI
            
            // As wristbands Brilliant têm este UUID de serviço específico
            val bsUuid = UUID.fromString("ea6d0000-a725-4f9b-893d-c3913e33b39f")
            if (scan.advertisedServiceUuids.contains(bsUuid)) return WRISTBAND_BRILLIANT_SOLE
            
            return UNKNOWN
        }
    }
}
