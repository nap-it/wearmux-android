package com.example.peciwearables.integration.protocol

import kotlin.math.sqrt

/** Operações puras sobre quaterniões representados como `[x, y, z, w]`. */
object QuaternionMath {

    val IDENTITY: FloatArray get() = floatArrayOf(0f, 0f, 0f, 1f)

    fun normalize(q: FloatArray): FloatArray? {
        if (q.size < 4) return null
        val (x, y, z, w) = listOf(q[0], q[1], q[2], q[3])
        val norm = sqrt(x * x + y * y + z * z + w * w)
        if (norm <= 0.0001f) return null
        return floatArrayOf(x / norm, y / norm, z / norm, w / norm)
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    /** Quaternião que leva `baseline` até `current` (`current * baseline^-1`). */
    fun relative(baseline: FloatArray, current: FloatArray): FloatArray {
        val inv = floatArrayOf(-baseline[0], -baseline[1], -baseline[2], baseline[3])
        return multiply(inv, current)
    }
}
