package com.wearmux.android.integration.protocol

import com.wearmux.android.integration.adapters.SdkSensorSample
import com.wearmux.android.integration.adapters.SdkSensorType

/**
 * One fused IMU sample from the glasses (BNO085), already processed by the
 * chip's own on-board sensor fusion -- no Madgwick on the Android side.
 *
 * [ax, ay, az] comes from the chip's linearAcceleration report (gravity already removed).
 * [qx, qy, qz, qw] comes from the chip's gameRotation report (relative orientation,
 * no magnetic-north reference -- confirmed sufficient for IMUCoCo, same reasoning
 * as PhoneFusedImuSample).
 */
data class GlassesFusedImuSample(
    val ax: Float, val ay: Float, val az: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
)

/** Same Q-point scale OmiGlassesBleClient already applies to GAME_ROTATION samples. */
const val GLASSES_SDK_SAMPLE_SCALE = 1f / 16384f

/**
 * Pairs the newest linearAcceleration sample with a just-arrived gameRotation sample
 * into one [GlassesFusedImuSample]. Returns null until a linearAcceleration sample has
 * arrived at least once; does not block waiting for a specific arrival order otherwise.
 */
fun mergeGlassesFusedSample(
    lastLinearAccel: SdkSensorSample?,
    gameRotation: SdkSensorSample,
): GlassesFusedImuSample? {
    require(gameRotation.sensorType == SdkSensorType.GAME_ROTATION)
    val accel = lastLinearAccel ?: return null
    val scale = GLASSES_SDK_SAMPLE_SCALE
    return GlassesFusedImuSample(
        ax = accel.x * scale, ay = accel.y * scale, az = accel.z * scale,
        qx = gameRotation.x * scale, qy = gameRotation.y * scale,
        qz = gameRotation.z * scale, qw = gameRotation.w * scale,
    )
}
