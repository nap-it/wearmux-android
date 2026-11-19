package com.wearmux.android.integration.protocol

data class WristbandImuSample(
    val sample: ImuSample,
    val timestampMs: Long,

    /*  MAC address da pulseira que enviou a amostra. */
    val deviceId: String = "",
)
