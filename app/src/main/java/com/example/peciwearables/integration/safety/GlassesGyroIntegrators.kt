package com.example.peciwearables.integration.safety

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Encapsula os 3 [GyIntegralCalculator] (eixos gx/gy/gz) que integram velocidade angular
 * e expõem o spread/yaw para a UI e telemetria.
 */
class GlassesGyroIntegrators(
    private val latestGy: MutableStateFlow<Float?>,
    private val latestGySpread: MutableStateFlow<Float?>,
    private val latestGx: MutableStateFlow<Float?>,
    private val latestGz: MutableStateFlow<Float?>,
) {
    private val gx = GyIntegralCalculator()
    private val gy = GyIntegralCalculator()
    private val gz = GyIntegralCalculator()

    fun onSample(sampleGx: Float, sampleGy: Float, sampleGz: Float, nowMs: Long) {
        gy.onGySample(sampleGy, nowMs); latestGy.value = gy.integralDeg(); latestGySpread.value = gy.spreadDeg()
        gx.onGySample(sampleGx, nowMs); latestGx.value = gx.integralDeg()
        gz.onGySample(sampleGz, nowMs); latestGz.value = gz.integralDeg()
    }

    fun reset() {
        gx.reset(); gy.reset(); gz.reset()
        latestGy.value = null; latestGySpread.value = null
        latestGx.value = null; latestGz.value = null
    }
}
