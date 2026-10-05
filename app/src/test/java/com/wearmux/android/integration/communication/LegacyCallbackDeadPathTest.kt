package com.wearmux.android.integration.communication

import com.wearmux.android.integration.adapters.devices.omi.FakeOmiGlassesBleClient
import org.junit.Assert.assertNull
import org.junit.Test


class LegacyCallbackDeadPathTest {

    @Test
    fun `FakeOmiGlassesBleClient has no photo callback by default`() {
        val client = FakeOmiGlassesBleClient()
        assertNull(client.onPhotoReceived)
    }

    @Test
    fun `FakeOmiGlassesBleClient has no audio callback by default`() {
        val client = FakeOmiGlassesBleClient()
        assertNull(client.onAudioPacket)
    }

    @Test
    fun `FakeOmiGlassesBleClient has no IMU callback by default`() {
        val client = FakeOmiGlassesBleClient()
        assertNull(client.onImuData)
    }
}
