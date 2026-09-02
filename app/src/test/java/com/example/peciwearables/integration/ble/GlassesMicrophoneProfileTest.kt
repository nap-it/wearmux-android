package com.example.peciwearables.integration.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesMicrophoneProfileTest {

    @Test
    fun `fromAppValues accepts all supported profiles`() {
        val profiles = listOf(
            8_000 to 8,
            8_000 to 16,
            16_000 to 8,
            16_000 to 16,
        )

        for ((sampleRateHz, bitDepth) in profiles) {
            val profile = GlassesMicrophoneProfile.fromAppValues(sampleRateHz, bitDepth)
            assertNotNull(profile)
            assertEquals(sampleRateHz, profile?.sampleRateHz)
            assertEquals(bitDepth, profile?.bitDepth)
        }
    }

    @Test
    fun `fromAppValues rejects unsupported combinations`() {
        assertNull(GlassesMicrophoneProfile.fromAppValues(sampleRateHz = 44_100, bitDepth = 16))
        assertNull(GlassesMicrophoneProfile.fromAppValues(sampleRateHz = 16_000, bitDepth = 24))
        assertNull(GlassesMicrophoneProfile.fromAppValues(sampleRateHz = 11_025, bitDepth = 8))
    }

    @Test
    fun `toConfigurationPayload maps each profile to canonical tlv values`() {
        val expectations = listOf(
            GlassesMicrophoneProfile.P8K_8 to byteArrayOf(0, 0, 1, 0),
            GlassesMicrophoneProfile.P8K_16 to byteArrayOf(0, 0, 1, 1),
            GlassesMicrophoneProfile.P16K_8 to byteArrayOf(0, 1, 1, 0),
            GlassesMicrophoneProfile.P16K_16 to byteArrayOf(0, 1, 1, 1),
        )

        for ((profile, expectedPayload) in expectations) {
            assertArrayEquals(expectedPayload, profile.toConfigurationPayload())
        }
    }

    @Test
    fun `round trip payload parse keeps profile values`() {
        val profiles = GlassesMicrophoneProfile.supportedProfiles
        for (profile in profiles) {
            val payload = profile.toConfigurationPayload()
            val parsed = GlassesMicrophoneProfile.parseFromConfigurationPayload(payload)
            assertNotNull(parsed)
            assertEquals(profile.sampleRateHz, parsed?.sampleRateHz)
            assertEquals(profile.bitDepth, parsed?.bitDepth)
        }
    }

    @Test
    fun `parseFromConfigurationPayload returns null for invalid payload`() {
        assertNull(GlassesMicrophoneProfile.parseFromConfigurationPayload(byteArrayOf()))
        assertNull(GlassesMicrophoneProfile.parseFromConfigurationPayload(byteArrayOf(7)))
        assertNull(GlassesMicrophoneProfile.parseFromConfigurationPayload(byteArrayOf(9, 1, 9, 0)))
    }

    @Test
    fun `resolveSelectedProfile prefers explicit selected profile id`() {
        val resolved = GlassesMicrophoneProfile.resolveSelectedProfile(
            selectedProfileId = GlassesMicrophoneProfile.P8K_16.profileId,
            confirmedSampleRateHz = 16_000,
            confirmedBitDepth = 8,
        )

        assertEquals(GlassesMicrophoneProfile.P8K_16, resolved)
    }

    @Test
    fun `resolveSelectedProfile falls back to confirmed values`() {
        val resolved = GlassesMicrophoneProfile.resolveSelectedProfile(
            selectedProfileId = "invalid",
            confirmedSampleRateHz = 8_000,
            confirmedBitDepth = 16,
        )

        assertEquals(GlassesMicrophoneProfile.P8K_16, resolved)
    }
}
