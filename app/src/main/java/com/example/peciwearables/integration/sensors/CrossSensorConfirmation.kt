package com.example.peciwearables.integration.sensors

import com.example.peciwearables.integration.protocol.GlassesImuSample
import com.example.peciwearables.integration.protocol.ImuSample
import kotlin.math.sqrt


class CrossSensorConfirmation(
    /** Acel total (m/s²) abaixo deste valor → "calmo" (phone). */
    private val phoneCalmAccelMps2: Float = 0.30f,
    /** Magnitude gyro (deg/s) abaixo deste valor → "cabeça quieta" (glasses). */
    private val glassesCalmGyroDps: Float = 12f,
    /** Acel total no pulso (m/s²) acima deste valor → "braço a balançar". */
    private val wristActiveAccelMps2: Float = 2.2f,
    /** Acel total telemóvel (m/s²) acima deste valor → "corpo em movimento". */
    private val phoneActiveAccelMps2: Float = 1.4f,
    /** Gyro Z dos óculos (deg/s) acima deste valor → "rotação de cabeça". */
    private val glassesLookGyroZDps: Float = 35f,
) {

    data class Inputs(
        val glasses: GlassesImuSample? = null,
        val watch: ImuSample? = null,
        val wristband: ImuSample? = null,
        val phoneAx: Float? = null,
        val phoneAy: Float? = null,
        val phoneAz: Float? = null,
    )

    /** Saída detalhada — útil para debug/UI. */
    data class Result(
        val isStationary: Boolean,
        val isWalking: Boolean,
        val isLookingAround: Boolean,
        val phoneAccelMag: Float,
        val glassesGyroMag: Float,
        val wristAccelMag: Float,
        val glassesGyroZ: Float,
        val confirmedBy: Int,   // quantos sensores confirmaram pelo menos uma das acções
    )

    fun evaluate(i: Inputs): Result {
        val phoneMag = if (i.phoneAx != null && i.phoneAy != null && i.phoneAz != null) {
            sqrt(i.phoneAx * i.phoneAx + i.phoneAy * i.phoneAy + i.phoneAz * i.phoneAz)
        } else Float.NaN

        val glassesGyroMag = i.glasses?.let {
            sqrt(it.gx * it.gx + it.gy * it.gy + it.gz * it.gz)
        } ?: Float.NaN
        val glassesGyroZ = i.glasses?.gz?.let { kotlin.math.abs(it) } ?: 0f

        // Acel total do pulso — preferimos o sensor que estiver disponível.
        val wristMag = listOfNotNull(i.watch, i.wristband)
            .map { sampleAccelMagMps2(it) }
            .maxOrNull() ?: Float.NaN

        // Stationary = phone calmo E (glasses calmo OU sem glasses)
        val stationary = phoneMag.isFinite() && phoneMag < phoneCalmAccelMps2 &&
            (glassesGyroMag.isNaN() || glassesGyroMag < glassesCalmGyroDps)

        // Walking = pulso a oscilar E corpo a oscilar (confirmação cruzada).
        // Exige ambos para evitar "só mexer braço" ou "só telemóvel ao bolso".
        val walking = wristMag.isFinite() && wristMag > wristActiveAccelMps2 &&
            phoneMag.isFinite() && phoneMag > phoneActiveAccelMps2

        // Lookingaround = só depende do gyro Z dos óculos. Não há
        // confirmação cruzada possível (única fonte para movimento de
        // cabeça); incluí-lo aqui é só para conveniência.
        val lookingAround = glassesGyroZ > glassesLookGyroZDps

        val confirmedBy = listOf(
            phoneMag.isFinite(),
            glassesGyroMag.isFinite(),
            wristMag.isFinite(),
        ).count { it }

        return Result(
            isStationary = stationary,
            isWalking = walking,
            isLookingAround = lookingAround,
            phoneAccelMag = phoneMag,
            glassesGyroMag = glassesGyroMag,
            wristAccelMag = wristMag,
            glassesGyroZ = glassesGyroZ,
            confirmedBy = confirmedBy,
        )
    }

    private fun sampleAccelMagMps2(s: ImuSample): Float {
        val ax = s.ax / 1000f * 9.80665f
        val ay = s.ay / 1000f * 9.80665f
        val az = s.az / 1000f * 9.80665f
        return sqrt(ax * ax + ay * ay + az * az)
    }
}
