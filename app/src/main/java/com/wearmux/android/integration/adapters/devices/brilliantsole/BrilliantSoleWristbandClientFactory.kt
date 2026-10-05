package com.wearmux.android.integration.adapters.devices.brilliantsole

import android.content.Context
import com.wearmux.android.integration.adapters.WearableId

fun interface BrilliantSoleWristbandClientFactory {
    fun create(id: WearableId): BrilliantSoleWristbandBleClientApi
}

fun defaultBrilliantSoleClientFactory(context: Context): BrilliantSoleWristbandClientFactory =
    BrilliantSoleWristbandClientFactory { BrilliantSoleWristbandBleClient(context) }
