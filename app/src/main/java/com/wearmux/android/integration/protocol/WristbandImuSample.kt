package com.wearmux.android.integration.protocol

data class WristbandImuSample(
    val sample: ImuSample,
    val timestampMs: Long,
)
