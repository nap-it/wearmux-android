package com.wearmux.android.integration.protocol

/**
 * One fused IMU sample from the telemóvel, already processed by Android's
 * own sensor fusion -- no Madgwick/magnetometer involved.
 *
 * [ax, ay, az] comes from TYPE_LINEAR_ACCELERATION (gravity already removed).
 * [qx, qy, qz, qw] comes from TYPE_GAME_ROTATION_VECTOR (relative orientation,
 * no magnetic-north reference -- confirmed sufficient for IMUCoCo, whose
 * T-pose/floor-align calibration absorbs any consistent yaw offset).
 */
data class PhoneFusedImuSample(
    val ax: Float, val ay: Float, val az: Float,  // m/s², gravity-free
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
)
