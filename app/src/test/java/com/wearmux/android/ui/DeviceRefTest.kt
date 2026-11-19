package com.wearmux.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceRefTest {

    @Test
    fun macAddressRoundTripsThroughRouteSegment() {
        val mac = "AA:BB:CC:DD:EE:FF"
        val ref = DeviceRef(DeviceId.WRISTBAND, mac)

        // O ':' não é válido num path segment — tem de ser escapado.
        assertNotEquals(mac, ref.routeInstance)
        assertEquals(mac, DeviceRef.percentDecode(ref.routeInstance))
    }

    @Test
    fun twoWristbandsWithDifferentMacsAreDifferentObjects() {
        val first = DeviceRef(DeviceId.WRISTBAND, "AA:BB:CC:DD:EE:FF")
        val second = DeviceRef(DeviceId.WRISTBAND, "11:22:33:44:55:66")

        assertEquals(first, DeviceRef(DeviceId.WRISTBAND, "AA:BB:CC:DD:EE:FF"))
        assertNotEquals(first, second)
        assertNotEquals(first.routeInstance, second.routeInstance)
    }

    @Test
    fun deviceWithoutInstanceUsesSentinelSegment() {
        val ref = DeviceRef(DeviceId.PHONE)

        assertEquals(DeviceRef.NO_INSTANCE, ref.routeInstance)
    }

    @Test
    fun percentEncodeLeavesUnreservedCharactersUntouched() {
        assertEquals("abcXYZ-123_~", DeviceRef.percentEncode("abcXYZ-123_~"))
    }

    @Test
    fun percentDecodeReturnsPlainTextWhenNothingIsEscaped() {
        assertEquals("AA-BB", DeviceRef.percentDecode("AA-BB"))
    }
}
