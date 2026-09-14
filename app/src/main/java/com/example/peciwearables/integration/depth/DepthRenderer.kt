package com.example.peciwearables.integration.depth

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix

enum class DepthColorMap { THERMAL, GRAYSCALE, VIRIDIS, MAGMA }

object DepthRenderer {

    fun toColorBitmap(result: DepthResult, alpha: Int = 180, colorMap: DepthColorMap = DepthColorMap.THERMAL): Bitmap {
        val w = result.width
        val h = result.height
        val px = IntArray(w * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val v = result.values[row * w + col].coerceIn(0f, 1f)
                val (r, g, b) = when (colorMap) {
                    DepthColorMap.THERMAL -> thermalRgb(v)
                    DepthColorMap.GRAYSCALE -> Triple((v * 255).toInt(), (v * 255).toInt(), (v * 255).toInt())
                    DepthColorMap.VIRIDIS -> interpolateStops(v, VIRIDIS_STOPS)
                    DepthColorMap.MAGMA -> interpolateStops(v, MAGMA_STOPS)
                }
                // Mirror horizontally to match camera preview orientation.
                px[row * w + (w - 1 - col)] = Color.argb(alpha, r, g, b)
            }
        }
        val raw = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply { postRotate(270f); postScale(-1f, 1f) }
        return Bitmap.createBitmap(raw, 0, 0, w, h, matrix, false)
    }

    // Spectral_r — matches Depth Anything V2 paper visuals (close=red, far=blue/purple).
    private fun thermalRgb(v: Float): Triple<Int, Int, Int> = interpolateStops(v, SPECTRAL_R_STOPS)

    private val SPECTRAL_R_STOPS = arrayOf(
        Triple(158,  1,  66),  // 0.0  deep red (close)
        Triple(213, 62,  79),  // 0.17 red
        Triple(244,109,  67),  // 0.33 orange
        Triple(254,224, 139),  // 0.5  light yellow
        Triple(171,221, 164),  // 0.67 mint green
        Triple(102,194, 165),  // 0.83 teal
        Triple( 94, 79, 162),  // 1.0  blue-purple (far)
    )

    private fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt().coerceIn(0, 255)

    private fun interpolateStops(v: Float, stops: Array<Triple<Int, Int, Int>>): Triple<Int, Int, Int> {
        val n = stops.size - 1
        val scaled = v * n
        val i = scaled.toInt().coerceIn(0, n - 1)
        val t = scaled - i
        val (r1, g1, b1) = stops[i]
        val (r2, g2, b2) = stops[i + 1]
        return Triple(lerp(r1, r2, t), lerp(g1, g2, t), lerp(b1, b2, t))
    }

    private val VIRIDIS_STOPS = arrayOf(
        Triple(68, 1, 84), Triple(59, 82, 139), Triple(33, 145, 140),
        Triple(94, 201, 98), Triple(253, 231, 37)
    )

    private val MAGMA_STOPS = arrayOf(
        Triple(0, 0, 4), Triple(81, 18, 124), Triple(183, 55, 121),
        Triple(252, 140, 83), Triple(252, 253, 191)
    )
}
