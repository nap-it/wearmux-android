package com.wearmux.android.integration.protocol

/**
 *
 * A informação é por dispositivo
 */
data class DeviceImuState(
    /** Última amostra recebida (unidades do protocolo: accel em mg, gyro em mdps). */
    val latest: ImuSample?,
    /** Total de amostras recebidas desde a ligação. */
    val sampleCount: Long,
    /** Taxa efectiva medida sobre a janela recente (Hz). */
    val rateHz: Int,
    /** Timestamp da última amostra (epoch ms). */
    val lastUpdateMs: Long,
)
