package com.example.peciwearables.integration.depth


data class DepthResult(
    val values: FloatArray,
    val width: Int,
    val height: Int,
) {
    /** Média de profundidade num círculo central — usado em calibração. */
    fun sampleCenter(radiusFraction: Float = 0.1f): Float {
        val cx = width / 2
        val cy = height / 2
        val rx = (width * radiusFraction).toInt().coerceAtLeast(1)
        val ry = (height * radiusFraction).toInt().coerceAtLeast(1)
        var sum = 0f; var n = 0
        for (y in (cy - ry).coerceAtLeast(0)..(cy + ry).coerceAtMost(height - 1)) {
            for (x in (cx - rx).coerceAtLeast(0)..(cx + rx).coerceAtMost(width - 1)) {
                sum += values[y * width + x]; n++
            }
        }
        return if (n > 0) sum / n else 0.5f
    }

    /** Profundidade no pixel correspondente às coordenadas normalizadas (0..1). */
    fun sampleAt(nx: Float, ny: Float): Float {
        val px = (nx * width).toInt().coerceIn(0, width - 1)
        val py = (ny * height).toInt().coerceIn(0, height - 1)
        return values[py * width + px]
    }

    /** Grelha `cols × rows` de amostras com (nx, ny, depth). */
    fun sampleGrid(cols: Int = 3, rows: Int = 3): List<Triple<Float, Float, Float>> =
        (0 until rows).flatMap { row ->
            (0 until cols).map { col ->
                val nx = (col + 0.5f) / cols
                val ny = (row + 0.5f) / rows
                val px = (nx * width).toInt().coerceIn(0, width - 1)
                val py = (ny * height).toInt().coerceIn(0, height - 1)
                Triple(nx, ny, values[py * width + px])
            }
        }

    override fun equals(other: Any?) = other is DepthResult &&
        other.width == width && other.height == height && other.values.contentEquals(values)
    override fun hashCode() = 31 * (31 * values.contentHashCode() + width) + height
}
