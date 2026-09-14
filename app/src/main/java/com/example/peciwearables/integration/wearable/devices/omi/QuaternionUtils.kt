package com.example.peciwearables.integration.wearable.devices.omi

import kotlin.math.*

fun quaternionToYawDeg(qx: Float, qy: Float, qz: Float, qw: Float): Float {
    // O BNO085 nos óculos tem Y=vertical, por isso o yaw físico (rotação
    // esquerda/direita à volta da vertical) é a rotação em torno do eixo Y do
    // chip. Usamos extracção Tait-Bryan YXZ para manter alcance ±180°.
    val m13 = 2f * (qx * qz + qw * qy)
    val m33 = 1f - 2f * (qx * qx + qy * qy)
    return Math.toDegrees(atan2(m13, m33).toDouble()).toFloat()
}
