package com.example.peciwearables.integration.adapters.devices.brilliantsole

import android.content.Context
import com.example.peciwearables.integration.adapters.WearableId

fun interface BrilliantSoleWristbandClientFactory {
    fun create(id: WearableId): BrilliantSoleWristbandBleClientApi
}

fun defaultBrilliantSoleClientFactory(context: Context): BrilliantSoleWristbandClientFactory =
    BrilliantSoleWristbandClientFactory { BrilliantSoleWristbandBleClient(context) }
