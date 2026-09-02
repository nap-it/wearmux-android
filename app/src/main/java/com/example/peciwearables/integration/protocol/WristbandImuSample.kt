package com.example.peciwearables.integration.protocol

data class WristbandImuSample(
    val sample: ImuSample,
    val timestampMs: Long,
)
