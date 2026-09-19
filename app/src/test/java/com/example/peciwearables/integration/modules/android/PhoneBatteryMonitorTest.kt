package com.example.peciwearables.integration.modules.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneBatteryMonitorTest {

    @Test
    fun `currentPercent computes percentage from level and scale`() {
        val stickyIntent = mockk<Intent>()
        every { stickyIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns 45
        every { stickyIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns 100
        val context = mockk<Context>()
        every { context.registerReceiver(null, any<IntentFilter>()) } returns stickyIntent

        val result = PhoneBatteryMonitor.currentPercent(context)

        assertEquals(45, result)
    }

    @Test
    fun `currentPercent returns -1 when the sticky intent is unavailable`() {
        val context = mockk<Context>()
        every { context.registerReceiver(null, any<IntentFilter>()) } returns null

        val result = PhoneBatteryMonitor.currentPercent(context)

        assertEquals(-1, result)
    }
}
