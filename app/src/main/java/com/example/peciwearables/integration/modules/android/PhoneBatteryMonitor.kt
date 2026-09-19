package com.example.peciwearables.integration.modules.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Reads the phone's own battery level for display in the device list/detail screens. */
object PhoneBatteryMonitor {

    /** Returns the battery percentage (0-100), or -1 if it cannot be read. */
    fun currentPercent(context: Context): Int {
        val batteryStatus = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return -1

        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1

        return (level * 100 / scale)
    }
}
