package com.example.peciwearables.integration.sensors

import com.example.peciwearables.integration.protocol.GlassesImuSample
import com.example.peciwearables.integration.protocol.ImuSample

/**
 * Converte uma amostra SI fundida (m/s² · deg/s · µT) para o formato raw
 * (mg · mdps · µT×10) usado nos detectores partilhados.
 * Inversa das fórmulas em [CoAxialImuFusion.toSiSnap].
 */
object FusedImuConverter {
    fun toImuSample(s: GlassesImuSample): ImuSample = ImuSample(
        siToMg(s.ax), siToMg(s.ay), siToMg(s.az),
        dpsToMdps(s.gx), dpsToMdps(s.gy), dpsToMdps(s.gz),
        utToShortX10(s.mx), utToShortX10(s.my), utToShortX10(s.mz),
        ShortArray(8),
    )

    private fun siToMg(a: Float): Short = (a / 9.80665f * 1000f).toInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    private fun dpsToMdps(d: Float): Short = (d * 1000f).toInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    private fun utToShortX10(m: Float): Short = (m * 10f).toInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
