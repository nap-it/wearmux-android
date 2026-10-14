package com.wearmux.android.integration.adapters

import org.junit.Assert.*
import org.junit.Test

class SdkSensorTypesTest {

    @Test
    fun `SdkSensorType has a LINEAR_ACCELERATION case distinct from the existing four`() {
        val types = SdkSensorType.values().toSet()
        assertTrue(types.contains(SdkSensorType.LINEAR_ACCELERATION))
        assertEquals(5, types.size)
    }
}
