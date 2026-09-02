package com.example.peciwearables.integration.protocol

/** Yaw/Pitch/Roll em graus, eixos no referencial da app. */
data class HeadEulerDeg(val yaw: Float, val pitch: Float, val roll: Float)

object QuaternionToEuler {
    fun fromQuaternion(quat: FloatArray): HeadEulerDeg? {
        if (quat.size < 4) return null
        val qx = quat[0]; val qy = quat[1]; val qz = quat[2]; val qw = quat[3]
        val norm = kotlin.math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        if (norm <= 0.0001f) return null
        val x = qx / norm; val y = qy / norm; val z = qz / norm; val w = qw / norm

        val sinrCosp = 2f * (w * x + y * z)
        val cosrCosp = 1f - 2f * (x * x + y * y)
        val roll = Math.toDegrees(kotlin.math.atan2(sinrCosp, cosrCosp).toDouble()).toFloat()

        val sinp = 2f * (w * y - z * x)
        val pitchRad = if (kotlin.math.abs(sinp) >= 1f) {
            kotlin.math.PI / 2.0 * kotlin.math.sign(sinp.toDouble())
        } else {
            kotlin.math.asin(sinp.toDouble())
        }
        val pitch = Math.toDegrees(pitchRad).toFloat()

        val sinyCosp = 2f * (w * z + x * y)
        val cosyCosp = 1f - 2f * (y * y + z * z)
        val yaw = Math.toDegrees(kotlin.math.atan2(sinyCosp, cosyCosp).toDouble()).toFloat()

        return HeadEulerDeg(yaw = yaw, pitch = pitch, roll = roll)
    }
}
