package com.wearmux.android.integration.safety

import com.wearmux.android.integration.api.AtcllClient
import com.wearmux.android.integration.modules.android.PhoneGpsLocation


class AtcllSafetyAlerts(
    private val dangerRadiusM: Float = 80f,
    private val emergencyRadiusM: Float = 200f,
) {
    sealed interface Alert {
        val timestampMs: Long
        data class Speeding(
            val speedKmh: Float,
            val distanceMeters: Float,
            override val timestampMs: Long,
        ) : Alert

        data class Emergency(
            val type: String,
            val speedKmh: Float,
            val distanceMeters: Float,
            override val timestampMs: Long,
        ) : Alert
    }

    fun evaluate(
        msg: AtcllClient.IncomingMessage,
        userGps: PhoneGpsLocation?,
    ): Alert? {
        val gps = userGps ?: return null
        return when (msg) {
            is AtcllClient.IncomingMessage.SpeedingVehicle -> {
                val d = haversineMeters(msg.latitude, msg.longitude, gps.latitude, gps.longitude).toFloat()
                if (d <= dangerRadiusM) Alert.Speeding(msg.speedKmh, d, msg.timestampMs) else null
            }
            is AtcllClient.IncomingMessage.EmergencyVehicle -> {
                val d = haversineMeters(msg.latitude, msg.longitude, gps.latitude, gps.longitude).toFloat()
                if (d <= emergencyRadiusM) {
                    Alert.Emergency(msg.type, msg.speedKmh, d, msg.timestampMs)
                } else null
            }
        }
    }
}
