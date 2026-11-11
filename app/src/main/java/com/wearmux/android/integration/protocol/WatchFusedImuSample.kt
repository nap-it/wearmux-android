package com.wearmux.android.integration.protocol

import com.wearmux.android.integration.modules.wearos.WatchProtocol
import kotlin.math.sqrt

/**
 * One fused IMU sample from the watch, already processed by its own chip's
 * sensor fusion (TYPE_GAME_ROTATION_VECTOR) -- no Madgwick/magnetometer involved.
 *
 * [ax, ay, az] comes straight from the raw accelerometer (mg), converted to SI.
 * [qx, qy, qz, qw] comes from the watch's native game rotation vector, sent over
 * the binary protocol as Q14-quantized x/y/z; w is reconstructed on the phone
 * side since only x, y, z are transmitted (see Task 1/2).
 */
data class WatchFusedImuSample(
    val ax: Float, val ay: Float, val az: Float,  // m/s²
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
)

/**
 * Decodes a raw [WatchProtocol.WatchImuSample] (accel in mg, quaternion x/y/z in
 * Q14) into a [WatchFusedImuSample]. `w` is reconstructed from the unit-quaternion
 * identity `w = sqrt(max(0, 1 - x² - y² - z²))`, clamped to protect against NaN
 * when quantization noise pushes the sum of squares slightly above 1.
 */
fun watchFusedFromRaw(
    ax: Short, ay: Short, az: Short,
    qxQ14: Short, qyQ14: Short, qzQ14: Short,
): WatchFusedImuSample {
    val scale = WatchProtocol.QUAT_Q14_SCALE
    val qx = qxQ14 * scale
    val qy = qyQ14 * scale
    val qz = qzQ14 * scale
    val sumSq = qx * qx + qy * qy + qz * qz
    val qw = sqrt((1f - sumSq).coerceAtLeast(0f))
    // ax/ay/az em mg -> m/s^2, mesma conversão que CoAxialImuFusion.toSiSnap/ImuUdpTriStreamSink usam hoje.
    return WatchFusedImuSample(
        ax = ax / 1000f * 9.80665f, ay = ay / 1000f * 9.80665f, az = az / 1000f * 9.80665f,
        qx = qx, qy = qy, qz = qz, qw = qw,
    )
}
