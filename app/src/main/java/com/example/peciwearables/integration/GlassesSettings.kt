package com.example.peciwearables.integration

const val CAMERA_RESOLUTION_MIN = 480
const val CAMERA_RESOLUTION_MAX = 1600
const val CAMERA_QUALITY_FACTOR_MIN = 0
const val CAMERA_QUALITY_FACTOR_MAX = 74
const val SENSOR_RATE_STEP_MS = 5
const val CAMERA_RATE_MAX_MS = 20
const val IMU_RATE_MAX_MS = 1000

fun normalizeSensorRateMsForSdk(rateMs: Int): Int {
    val clampedRate = rateMs.coerceIn(SENSOR_RATE_STEP_MS, CAMERA_RATE_MAX_MS)
    return (clampedRate / SENSOR_RATE_STEP_MS) * SENSOR_RATE_STEP_MS
}

fun normalizeImuSensorRateMs(rateMs: Int): Int {
    val clampedRate = rateMs.coerceIn(SENSOR_RATE_STEP_MS, IMU_RATE_MAX_MS)
    return (clampedRate / SENSOR_RATE_STEP_MS) * SENSOR_RATE_STEP_MS
}

data class GlassesSettings(
    val bleNegotiatedMtu: Int? = null,
    val sdkMtu: Int? = null,
    val resolution: Int? = null,
    val qualityFactor: Int? = null,
    val cameraRateMs: Int? = null,
    val microphoneRateMs: Int? = null,
) {
    val preferredMtu: Int?
        get() = sdkMtu ?: bleNegotiatedMtu

    val hasAnyValue: Boolean
        get() = preferredMtu != null ||
            resolution != null ||
            qualityFactor != null ||
            cameraRateMs != null ||
            microphoneRateMs != null

    val hasCameraTuningValue: Boolean
        get() = resolution != null ||
            qualityFactor != null ||
            cameraRateMs != null
}
