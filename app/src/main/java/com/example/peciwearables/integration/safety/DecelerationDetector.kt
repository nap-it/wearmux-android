package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.sensors.PhoneAccelSample
import kotlin.math.sqrt


class DecelerationDetector(
    private val decelRatio: Float = 0.55f,
    private val minSlowEmaG: Float = 0.10f,
) {
    private var emaFast = 0f
    private var emaSlow = 0f
    private val alphaFast = 0.30f   // ~ 500 ms
    private val alphaSlow = 0.05f   // ~ 3 s
    private var samples = 0

    @Synchronized
    fun onSample(sample: PhoneAccelSample) {
        // PhoneAccelSample tem .x/.y/.z em m/s² (a app já remove gravidade
        // via TYPE_LINEAR_ACCELERATION no PhoneSensorManager).
        val mag = sqrt(sample.x * sample.x + sample.y * sample.y + sample.z * sample.z) / 9.80665f
        emaFast = alphaFast * mag + (1 - alphaFast) * emaFast
        emaSlow = alphaSlow * mag + (1 - alphaSlow) * emaSlow
        samples++
    }

    /**
     * True quando há sinal de desaceleração genuína. Devolve **false** se:
     *  - estamos a aquecer (poucas amostras)
     *  - o utilizador estava parado de origem (slow EMA muito baixa)
     */
    @Synchronized
    fun isDecelerating(): Boolean {
        if (samples < 10) return false
        if (emaSlow < minSlowEmaG) return false
        return emaFast < emaSlow * decelRatio
    }

    @Synchronized
    fun ratio(): Float = if (emaSlow == 0f) 0f else emaFast / emaSlow

    @Synchronized
    fun reset() {
        emaFast = 0f
        emaSlow = 0f
        samples = 0
    }
}
