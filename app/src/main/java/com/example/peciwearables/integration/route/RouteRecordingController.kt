package com.example.peciwearables.integration.route

import com.example.peciwearables.integration.pdr.RouteManager
import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Controla a gravação de trajectos: arranque com âncora GPS, transição para IMU-only,
 * cancelamento e activação/desactivação. Encapsula as flags `routeImuOnly` e
 * `waitingForAnchorFix` que antes viviam no [WearableService].
 */
class RouteRecordingController(
    private val routeManager: RouteManager,
    private val phoneSensorsActive: () -> Boolean,
    private val phoneGps: () -> PhoneGpsLocation?,
    private val recordingWaypointCount: MutableStateFlow<Int>,
    private val startPhoneSensors: () -> Unit,
    private val appendLog: (String) -> Unit,
) {
    @Volatile var routeImuOnly: Boolean = false; private set
    @Volatile var waitingForAnchorFix: Boolean = false; private set

    fun consumeAnchor(latitude: Double, longitude: Double) {
        waitingForAnchorFix = false
        routeImuOnly = true
        routeManager.addWaypoint(latitude, longitude)
        recordingWaypointCount.value = recordingWaypointCount.value + 1
        appendLog("🎯 GPS anchor acquired — trajectory now follows IMU only")
    }

    fun start() {
        if (routeManager.isRecording.value) return
        if (!phoneSensorsActive()) {
            startPhoneSensors()
            appendLog("📡 Phone sensors enabled for trajectory recording")
        }
        routeImuOnly = false
        waitingForAnchorFix = false
        recordingWaypointCount.value = 0
        routeManager.startRecording()
        val anchor = phoneGps()
        if (anchor != null) {
            routeImuOnly = true
            routeManager.addWaypoint(anchor.latitude, anchor.longitude)
            recordingWaypointCount.value = 1
            appendLog("🛣 Trajectory started (GPS anchor ok, IMU only from here on)")
        } else {
            waitingForAnchorFix = true
            appendLog("🛣 Waiting for the first GPS fix to anchor the trajectory (IMU only afterwards)")
        }
    }

    fun finish(name: String) {
        val finalName = name.ifBlank { "route-${System.currentTimeMillis() / 1000}" }
        routeImuOnly = false; waitingForAnchorFix = false
        val route = routeManager.finishRecording(finalName)
        recordingWaypointCount.value = 0
        if (route == null) appendLog("⚠  Trajectory not saved: needs ≥2 waypoints")
        else appendLog("✅ Trajectory '${route.name}' saved (${route.waypoints.size} pts)")
    }

    fun cancel() {
        routeImuOnly = false; waitingForAnchorFix = false
        routeManager.cancelRecording()
        recordingWaypointCount.value = 0
        appendLog("Trajectory recording cancelled")
    }

    fun activate(id: String) {
        routeManager.activateRoute(id)
        appendLog("🛣 Active trajectory: ${routeManager.activeRoute.value?.name ?: "?"}")
    }

    fun deactivate() {
        routeManager.deactivateRoute()
        appendLog("Active trajectory removed")
    }

    fun delete(id: String) {
        routeManager.deleteRoute(id)
        appendLog("Trajectory $id deleted")
    }
}
