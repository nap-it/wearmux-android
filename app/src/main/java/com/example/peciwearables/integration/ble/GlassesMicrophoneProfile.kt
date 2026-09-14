package com.example.peciwearables.integration.ble

data class GlassesMicrophoneProfile(
    val sampleRateHz: Int,
    val bitDepth: Int,
) {
    val profileId: String
        get() = "${sampleRateHz / 1000}k/$bitDepth"

    fun toConfigurationPayload(): ByteArray =
        byteArrayOf(
            TYPE_SAMPLE_RATE.toByte(),
            encodeSampleRate(sampleRateHz).toByte(),
            TYPE_BIT_DEPTH.toByte(),
            encodeBitDepth(bitDepth).toByte(),
        )

    companion object {
        private const val TYPE_SAMPLE_RATE = 0
        private const val TYPE_BIT_DEPTH = 1
        private const val SAMPLE_RATE_8K = 8_000
        private const val SAMPLE_RATE_16K = 16_000
        private const val BIT_DEPTH_8 = 8
        private const val BIT_DEPTH_16 = 16

        val P8K_8 = GlassesMicrophoneProfile(sampleRateHz = SAMPLE_RATE_8K, bitDepth = BIT_DEPTH_8)
        val P8K_16 = GlassesMicrophoneProfile(sampleRateHz = SAMPLE_RATE_8K, bitDepth = BIT_DEPTH_16)
        val P16K_8 = GlassesMicrophoneProfile(sampleRateHz = SAMPLE_RATE_16K, bitDepth = BIT_DEPTH_8)
        val P16K_16 = GlassesMicrophoneProfile(sampleRateHz = SAMPLE_RATE_16K, bitDepth = BIT_DEPTH_16)

        val supportedProfiles: List<GlassesMicrophoneProfile> = listOf(
            P8K_8,
            P8K_16,
            P16K_8,
            P16K_16,
        )

        // Keep parser defaults aligned with the previous behavior in OmiRxHandler.
        private val parserDefault = P16K_8

        fun fromAppValues(sampleRateHz: Int, bitDepth: Int): GlassesMicrophoneProfile? =
            supportedProfiles.firstOrNull { it.sampleRateHz == sampleRateHz && it.bitDepth == bitDepth }

        fun fromProtocolValues(sampleRateValue: Int, bitDepthValue: Int): GlassesMicrophoneProfile {
            val sampleRateHz = decodeSampleRate(sampleRateValue)
            val bitDepth = decodeBitDepth(bitDepthValue)
            return fromAppValues(sampleRateHz, bitDepth) ?: parserDefault
        }

        fun parseFromConfigurationPayload(payload: ByteArray): GlassesMicrophoneProfile? {
            if (payload.size < 2) return null
            var sampleRateHz = parserDefault.sampleRateHz
            var bitDepth = parserDefault.bitDepth
            var foundAny = false
            var offset = 0
            while (offset + 1 < payload.size) {
                val type = payload[offset].toInt() and 0xFF
                val value = payload[offset + 1].toInt() and 0xFF
                when (type) {
                    TYPE_SAMPLE_RATE -> {
                        sampleRateHz = decodeSampleRate(value)
                        foundAny = true
                    }
                    TYPE_BIT_DEPTH -> {
                        bitDepth = decodeBitDepth(value)
                        foundAny = true
                    }
                }
                offset += 2
            }
            if (!foundAny) return null
            return fromAppValues(sampleRateHz, bitDepth) ?: parserDefault
        }

        fun resolveSelectedProfile(
            selectedProfileId: String?,
            confirmedSampleRateHz: Int,
            confirmedBitDepth: Int,
            fallback: GlassesMicrophoneProfile = P16K_8,
        ): GlassesMicrophoneProfile {
            val explicit = supportedProfiles.firstOrNull { it.profileId == selectedProfileId }
            if (explicit != null) return explicit
            val confirmed = fromAppValues(confirmedSampleRateHz, confirmedBitDepth)
            return confirmed ?: fallback
        }

        private fun decodeSampleRate(value: Int): Int =
            if (value == 0) SAMPLE_RATE_8K else SAMPLE_RATE_16K

        private fun decodeBitDepth(value: Int): Int =
            if (value == 0) BIT_DEPTH_8 else BIT_DEPTH_16

        private fun encodeSampleRate(sampleRateHz: Int): Int =
            if (sampleRateHz == SAMPLE_RATE_8K) 0 else 1

        private fun encodeBitDepth(bitDepth: Int): Int =
            if (bitDepth == BIT_DEPTH_8) 0 else 1
    }
}
