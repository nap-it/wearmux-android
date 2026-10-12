package com.wearmux.android.integration.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuCoCoSinkConfigTest {

    @Test
    fun `disabled config is not usable even with valid host`() {
        val config = ImuCoCoSinkConfig(enabled = false, host = "192.168.1.50", port = 8001, userId = "u1")
        assertFalse(config.isUsable)
    }

    @Test
    fun `enabled config with blank host is not usable`() {
        val config = ImuCoCoSinkConfig(enabled = true, host = "", port = 8001, userId = "u1")
        assertFalse(config.isUsable)
    }

    @Test
    fun `enabled config with host is usable`() {
        val config = ImuCoCoSinkConfig(enabled = true, host = "192.168.1.50", port = 8001, userId = "u1")
        assertTrue(config.isUsable)
    }

    @Test
    fun `default userId is applied when blank`() {
        val config = ImuCoCoSinkConfig(enabled = true, host = "h", port = 8001, userId = "")
        assertEquals(ImuCoCoSinkConfig.DEFAULT_USER_ID, config.effectiveUserId())
    }
}
