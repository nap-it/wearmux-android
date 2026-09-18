package com.example.peciwearables.integration.modules.sensors

import com.example.peciwearables.integration.MlProcessingLocation

/**
 * Escolhe a taxa de amostragem dos sensores do wearable.
 *
 * A taxa não é fixa: com inferência no próprio wearable é preciso o ritmo alto
 * que o modelo espera; quando a câmara ou o microfone estão a ocupar o canal, a
 * taxa desce para não competir com eles pelo rádio.
 */
object SensorRatePolicy {

    const val ON_DEVICE_ML_RATE_MS = 10
    const val CONTENDED_RATE_MS = 200
    const val DEFAULT_RATE_MS = 50

    /** [contended]: câmara ou microfone a ocupar o canal. */
    fun wristbandRateMs(location: MlProcessingLocation, contended: Boolean): Int = when (location) {
        MlProcessingLocation.WRISTBAND -> ON_DEVICE_ML_RATE_MS
        MlProcessingLocation.APP, MlProcessingLocation.SERVER ->
            if (contended) CONTENDED_RATE_MS else DEFAULT_RATE_MS
    }

    /** O magnetómetro só é dispensado quando o modelo corre no próprio wearable. */
    fun includeMagnetometer(location: MlProcessingLocation): Boolean =
        location != MlProcessingLocation.WRISTBAND
}
