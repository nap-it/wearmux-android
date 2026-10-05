package com.wearmux.android.integration.consumer

import com.wearmux.android.integration.adapters.BleDeviceState
import com.wearmux.android.integration.modules.android.PhoneAccelSample
import com.wearmux.android.integration.modules.android.PhoneGpsLocation
import com.wearmux.android.integration.modules.context.PdrPosition
import com.wearmux.android.integration.modules.wearos.WatchClient
import com.wearmux.android.integration.protocol.GlassesImuSample
import com.wearmux.android.integration.safety.CrossingZone
import com.wearmux.android.integration.safety.CrossingZoneTracker

/**
 * Compõe o [TelemetryReporter.Payload] a partir dos snapshots de sensores/state-flows do service.
 * Encapsula ~35 linhas com a lógica de fusão GPS/PDR/zone-near e mapping de online states.
 */
class TelemetryPayloadBuilder(
    private val crossingZoneTracker: CrossingZoneTracker,
    private val snap: () -> Snapshot,
) {
    data class Snapshot(
        val phoneAccel: PhoneAccelSample?,
        val phoneGps: PhoneGpsLocation?,
        val pdrPos: PdrPosition?,
        val crossingZones: List<CrossingZone>,
        val glassesState: BleDeviceState,
        val wristbandState: BleDeviceState,
        val watchState: WatchClient.State,
        val cyclistModeName: String,
        val latestGlassesImu: GlassesImuSample?,
        val fusedImuCount: Int,
        val yawSpreadDeg: Float?,
    )

    fun build(): TelemetryReporter.Payload? {
        val s = snap()
        val accel = s.phoneAccel ?: return null
        val gps = s.phoneGps
        val pdr = s.pdrPos
        val now = System.currentTimeMillis()
        val gpsAge = if (gps != null) now - gps.timestampMs else Long.MAX_VALUE
        val pdrAge = if (pdr != null) now - pdr.timestampMs else Long.MAX_VALUE
        val nearZone = gps?.let { g ->
            crossingZoneTracker.nearestZone(s.crossingZones, g)?.let { (z, d) -> (d - z.radiusMeters) <= 15.0 }
        } ?: false
        val (lat, lon, src) = when {
            gps != null && nearZone && gpsAge <= 6_000L -> Triple(gps.latitude, gps.longitude, "gps_zone_confirm")
            gps != null && gpsAge <= 2_000L -> Triple(gps.latitude, gps.longitude, "gps")
            pdr != null && pdrAge <= 2_000L -> Triple(pdr.latitude, pdr.longitude, "pdr")
            gps != null -> Triple(gps.latitude, gps.longitude, "gps_stale")
            else -> Triple(null as Double?, null as Double?, null as String?)
        }
        val mag = Math.sqrt((accel.x * accel.x + accel.y * accel.y + accel.z * accel.z).toDouble()).toFloat()
        return TelemetryReporter.Payload(
            lat = lat, lon = lon, speedMps = gps?.speed, bearingDeg = gps?.bearing,
            glassesOnline = s.glassesState.isOnline(),
            watchOnline = s.watchState !in setOf(WatchClient.State.DISCONNECTED, WatchClient.State.ERROR),
            wristbandOnline = s.wristbandState.isOnline(),
            cyclistMode = s.cyclistModeName,
            motionState = if (mag < 0.3f) "stationary" else "walking", accelMean = mag,
            glassesImu = s.latestGlassesImu?.let { g ->
                TelemetryReporter.ImuVector(g.ax, g.ay, g.az, g.gx, g.gy, g.gz, g.mx, g.my, g.mz)
            },
            positionSource = src,
            fusedImuCount = s.fusedImuCount,
            yawSpreadDeg = s.yawSpreadDeg,
            hubTimestampMs = now,
        )
    }
    private fun BleDeviceState.isOnline() = this == BleDeviceState.READY || this == BleDeviceState.CONNECTED
}
