package com.example.peciwearables.integration.ble

/** Configuração de câmara parseada do firmware. */
data class ParsedCameraConfiguration(
    val resolution: Int? = null,
    val qualityFactor: Int? = null,
)

/** Rates de sensores parseados do firmware. */
data class ParsedSensorConfiguration(
    val cameraRateMs: Int? = null,
    val microphoneRateMs: Int? = null,
)

enum class SdkSensorType {
    ACCELEROMETER,
    GYROSCOPE,
    MAGNETOMETER,
    GAME_ROTATION,
}

data class SdkSensorSample(
    val sensorType: SdkSensorType,
    val x: Short,
    val y: Short,
    val z: Short,
    val w: Short = 0,
    val timestampRaw16: Int,
)
