package com.wearmux.android.integration.modules.sensors

import kotlin.math.sqrt

/**
 * Hand-rolled Madgwick AHRS filter (Sebastian Madgwick, 2010). Fuses
 * gyro+accel+mag into an absolute, magnetic-north-aligned quaternion.
 * One instance per physical device — never share across devices, since
 * each has its own orientation history.
 */
class MadgwickAhrs(
    private val sampleFreqHz: Float = 50f,
    private val beta: Float = 0.1f,
) {
    private var q0 = 1f // w
    private var q1 = 0f // x
    private var q2 = 0f // y
    private var q3 = 0f // z

    fun reset() {
        q0 = 1f; q1 = 0f; q2 = 0f; q3 = 0f
    }

    fun quaternion(): FloatArray = floatArrayOf(q1, q2, q3, q0)

    fun gravityVector(): FloatArray {
        // 3rd row of the rotation matrix derived from the quaternion,
        // scaled by standard gravity.
        val gx = 2f * (q1 * q3 - q0 * q2)
        val gy = 2f * (q0 * q1 + q2 * q3)
        val gz = q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3
        return floatArrayOf(gx * 9.80665f, gy * 9.80665f, gz * 9.80665f)
    }

    fun update(
        gx: Float, gy: Float, gz: Float,
        ax: Float, ay: Float, az: Float,
        mx: Float, my: Float, mz: Float,
    ) {
        var ax1 = ax; var ay1 = ay; var az1 = az
        var mx1 = mx; var my1 = my; var mz1 = mz

        // Normalize accelerometer; skip accel/mag correction if degenerate (free-fall).
        val aNorm = sqrt(ax1 * ax1 + ay1 * ay1 + az1 * az1)
        val mNorm = sqrt(mx1 * mx1 + my1 * my1 + mz1 * mz1)
        if (aNorm <= 0.0001f || mNorm <= 0.0001f) {
            // Gyro-only integration as fallback.
            integrateGyro(gx, gy, gz)
            return
        }
        ax1 /= aNorm; ay1 /= aNorm; az1 /= aNorm
        mx1 /= mNorm; my1 /= mNorm; mz1 /= mNorm

        // Auxiliary variables to avoid repeated arithmetic.
        val q0 = this.q0; val q1 = this.q1; val q2 = this.q2; val q3 = this.q3
        val _2q0 = 2f * q0; val _2q1 = 2f * q1; val _2q2 = 2f * q2; val _2q3 = 2f * q3
        val _4bx: Float; val _4bz: Float
        val q0q0 = q0 * q0; val q1q1 = q1 * q1; val q2q2 = q2 * q2; val q3q3 = q3 * q3

        // Reference direction of Earth's magnetic field (computed each call —
        // cheap relative to the trig-free gradient descent below).
        val hx = mx1 * q0q0 - _2q0 * my1 * q3 + _2q0 * mz1 * q2 + mx1 * q1q1 +
            _2q1 * my1 * q2 + _2q1 * mz1 * q3 - mx1 * q2q2 - mx1 * q3q3
        val hy = _2q0 * mx1 * q3 + my1 * q0q0 - _2q0 * mz1 * q1 + _2q1 * mx1 * q2 -
            my1 * q1q1 + my1 * q2q2 + _2q2 * mz1 * q3 - my1 * q3q3
        val _2bx = sqrt(hx * hx + hy * hy)
        val _2bz = -_2q0 * mx1 * q2 + _2q0 * my1 * q1 + mz1 * q0q0 + _2q1 * mx1 * q3 -
            mz1 * q1q1 + _2q2 * my1 * q3 - mz1 * q2q2 + mz1 * q3q3
        _4bx = 2f * _2bx; _4bz = 2f * _2bz

        // Gradient descent algorithm corrective step.
        val s0 = -_2q2 * (2f * (q1 * q3 - q0 * q2) - ax1) + _2q1 * (2f * (q0 * q1 + q2 * q3) - ay1) -
            _2bz * q2 * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1 * q3 - q0 * q2) - mx1) +
            (-_2bx * q3 + _2bz * q1) * (_2bx * (q1 * q2 - q0 * q3) + _2bz * (q0 * q1 + q2 * q3) - my1) +
            _2bx * q2 * (_2bx * (q0 * q2 + q1 * q3) + _2bz * (0.5f - q1q1 - q2q2) - mz1)
        val s1 = _2q3 * (2f * (q1 * q3 - q0 * q2) - ax1) + _2q0 * (2f * (q0 * q1 + q2 * q3) - ay1) -
            4f * q1 * (1f - 2f * q1q1 - 2f * q2q2 - az1) +
            _2bz * q3 * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1 * q3 - q0 * q2) - mx1) +
            (_2bx * q2 + _2bz * q0) * (_2bx * (q1 * q2 - q0 * q3) + _2bz * (q0 * q1 + q2 * q3) - my1) +
            (_2bx * q3 - _4bz * q1) * (_2bx * (q0 * q2 + q1 * q3) + _2bz * (0.5f - q1q1 - q2q2) - mz1)
        val s2 = -_2q0 * (2f * (q1 * q3 - q0 * q2) - ax1) + _2q3 * (2f * (q0 * q1 + q2 * q3) - ay1) -
            4f * q2 * (1f - 2f * q1q1 - 2f * q2q2 - az1) +
            (-_4bx * q2 - _2bz * q0) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1 * q3 - q0 * q2) - mx1) +
            (_2bx * q1 + _2bz * q3) * (_2bx * (q1 * q2 - q0 * q3) + _2bz * (q0 * q1 + q2 * q3) - my1) +
            (_2bx * q0 - _4bz * q2) * (_2bx * (q0 * q2 + q1 * q3) + _2bz * (0.5f - q1q1 - q2q2) - mz1)
        val s3 = _2q1 * (2f * (q1 * q3 - q0 * q2) - ax1) + _2q2 * (2f * (q0 * q1 + q2 * q3) - ay1) +
            (-_4bx * q3 + _2bz * q1) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1 * q3 - q0 * q2) - mx1) +
            (-_2bx * q0 + _2bz * q2) * (_2bx * (q1 * q2 - q0 * q3) + _2bz * (q0 * q1 + q2 * q3) - my1) +
            _2bx * q1 * (_2bx * (q0 * q2 + q1 * q3) + _2bz * (0.5f - q1q1 - q2q2) - mz1)

        var sNorm = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
        if (sNorm <= 0.0001f) sNorm = 1f
        val sq0 = s0 / sNorm; val sq1 = s1 / sNorm; val sq2 = s2 / sNorm; val sq3 = s3 / sNorm

        // Rate of change of quaternion from gyroscope, minus the beta-weighted correction.
        val qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz) - beta * sq0
        val qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy) - beta * sq1
        val qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx) - beta * sq2
        val qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx) - beta * sq3

        val dt = 1f / sampleFreqHz
        var nq0 = q0 + qDot0 * dt
        var nq1 = q1 + qDot1 * dt
        var nq2 = q2 + qDot2 * dt
        var nq3 = q3 + qDot3 * dt

        val norm = sqrt(nq0 * nq0 + nq1 * nq1 + nq2 * nq2 + nq3 * nq3)
        if (norm > 0.0001f) {
            nq0 /= norm; nq1 /= norm; nq2 /= norm; nq3 /= norm
        } else {
            nq0 = 1f; nq1 = 0f; nq2 = 0f; nq3 = 0f
        }
        this.q0 = nq0; this.q1 = nq1; this.q2 = nq2; this.q3 = nq3
    }

    private fun integrateGyro(gx: Float, gy: Float, gz: Float) {
        val dt = 1f / sampleFreqHz
        val qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
        val qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
        val qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
        val qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)
        var nq0 = q0 + qDot0 * dt
        var nq1 = q1 + qDot1 * dt
        var nq2 = q2 + qDot2 * dt
        var nq3 = q3 + qDot3 * dt
        val norm = sqrt(nq0 * nq0 + nq1 * nq1 + nq2 * nq2 + nq3 * nq3)
        if (norm > 0.0001f) {
            nq0 /= norm; nq1 /= norm; nq2 /= norm; nq3 /= norm
        } else {
            nq0 = 1f; nq1 = 0f; nq2 = 0f; nq3 = 0f
        }
        q0 = nq0; q1 = nq1; q2 = nq2; q3 = nq3
    }
}
