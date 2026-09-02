package com.example.peciwearables.wear

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log


object Vibrations {

    private const val TAG = "Vibrations"

    fun play(context: Context, patternId: Int, amplitude: Int = 0) {
        val v = vibrator(context) ?: return
        val effect = effectFor(patternId, amplitude)
        try {
            v.vibrate(effect)
        } catch (e: Exception) {
            Log.w(TAG, "vibrate falhou: ${e.message}")
        }
    }

    private fun effectFor(patternId: Int, amplitude: Int): VibrationEffect {
        // amplitude 0 = default; clamping a 1..255
        val amp = if (amplitude == 0) VibrationEffect.DEFAULT_AMPLITUDE else amplitude.coerceIn(1, 255)
        return when (patternId) {
            WatchProtocol.VibratePattern.DOUBLE -> {
                VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 80, 80),
                    intArrayOf(0, amp, 0, amp),
                    -1,
                )
            }
            WatchProtocol.VibratePattern.LONG -> {
                VibrationEffect.createOneShot(450, amp)
            }
            WatchProtocol.VibratePattern.TRIPLE -> {
                VibrationEffect.createWaveform(
                    longArrayOf(0, 60, 70, 60, 70, 60),
                    intArrayOf(0, amp, 0, amp, 0, amp),
                    -1,
                )
            }
            WatchProtocol.VibratePattern.HEARTBEAT -> {
                // Imita ritmo cardíaco — pulse forte + pulse mais leve.
                val light = (amp * 0.55f).toInt().coerceAtLeast(60)
                VibrationEffect.createWaveform(
                    longArrayOf(0, 110, 100, 70),
                    intArrayOf(0, amp, 0, light),
                    -1,
                )
            }
            WatchProtocol.VibratePattern.ALARM -> {
                // Alarme contínuo — três rajadas longas com curtas pausas.
                VibrationEffect.createWaveform(
                    longArrayOf(0, 250, 100, 250, 100, 250),
                    intArrayOf(0, amp, 0, amp, 0, amp),
                    -1,
                )
            }
            else -> VibrationEffect.createOneShot(120, amp) // SHORT default
        }
    }

    private fun vibrator(context: Context): Vibrator? {
        return try {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
                ?: @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        } catch (e: Exception) {
            Log.w(TAG, "vibrator() falhou: ${e.message}")
            null
        }
    }
}
