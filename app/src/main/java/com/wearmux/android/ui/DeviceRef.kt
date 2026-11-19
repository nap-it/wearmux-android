package com.wearmux.android.ui

enum class DeviceId {
    PHONE, GLASSES, WRISTBAND, GALAXY_WATCH, ESP32_CAM
}

/**
 * Referência a um dispositivo **concreto**: o tipo mais, opcionalmente, a
 * instanceId é o MAC address do dispositivo e é `null` para os que só podem
 * existir em singular. 
 */
data class DeviceRef(
    val id: DeviceId,
    val instanceId: String? = null,
) {
    val routeInstance: String get() = instanceId?.let { percentEncode(it) } ?: NO_INSTANCE

    companion object {
        const val NO_INSTANCE = "-"

        private const val UNRESERVED = "-_.~"

        fun percentEncode(value: String): String = buildString {
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val char = byte.toInt().toChar()
                if (byte >= 0 && (char.isLetterOrDigit() || char in UNRESERVED)) {
                    append(char)
                } else {
                    append('%')
                    append("%02X".format(byte.toInt() and 0xFF))
                }
            }
        }

        fun percentDecode(value: String): String {
            if (!value.contains('%')) return value
            val out = ArrayList<Byte>()
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '%' && index + 2 < value.length) {
                    val hex = value.substring(index + 1, index + 3)
                    val decoded = hex.toIntOrNull(16)
                    if (decoded == null) {
                        char.toString().toByteArray(Charsets.UTF_8).forEach { out.add(it) }
                        index++
                    } else {
                        out.add(decoded.toByte())
                        index += 3
                    }
                } else {
                    char.toString().toByteArray(Charsets.UTF_8).forEach { out.add(it) }
                    index++
                }
            }
            return out.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
