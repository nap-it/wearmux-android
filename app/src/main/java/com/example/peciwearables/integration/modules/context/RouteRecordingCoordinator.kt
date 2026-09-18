package com.example.peciwearables.integration.modules.context

import com.example.peciwearables.integration.modules.android.PhoneGpsLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


class RouteRecordingCoordinator(private val routeManager: RouteManager) {

    private val _imuOnly = MutableStateFlow(false)
    val imuOnly: StateFlow<Boolean> = _imuOnly.asStateFlow()
    private val _waitingForAnchor = MutableStateFlow(false)
    val waitingForAnchor: StateFlow<Boolean> = _waitingForAnchor.asStateFlow()

    val isRecording: StateFlow<Boolean> get() = routeManager.isRecording
    val activeRoute: StateFlow<SavedRoute?> get() = routeManager.activeRoute
    val savedRoutes: StateFlow<List<SavedRoute>> get() = routeManager.routes

    fun start(initialGps: PhoneGpsLocation?, onLog: (String) -> Unit, onWaypointCount: (Int) -> Unit) {
        if (routeManager.isRecording.value) return
        _imuOnly.value = false
        _waitingForAnchor.value = false
        onWaypointCount(0)
        routeManager.startRecording()
        if (initialGps != null) {
            _imuOnly.value = true
            routeManager.addWaypoint(initialGps.latitude, initialGps.longitude)
            onWaypointCount(1)
            onLog("🛣 Trajectory started (GPS anchor ok, IMU only from here on)")
        } else {
            _waitingForAnchor.value = true
            onLog("🛣 Waiting for the first GPS fix to anchor the trajectory (IMU only afterwards)")
        }
    }

    /** Chamado quando chega o primeiro fix GPS depois do start. */
    fun onAnchorObtained(gps: PhoneGpsLocation, onLog: (String) -> Unit, onWaypointCount: (Int) -> Unit) {
        if (!_waitingForAnchor.value) return
        _waitingForAnchor.value = false
        _imuOnly.value = true
        routeManager.addWaypoint(gps.latitude, gps.longitude)
        onWaypointCount(1)
        onLog("🎯 GPS anchor acquired — trajectory now follows IMU only")
    }

    fun finish(name: String): SavedRoute? {
        _imuOnly.value = false; _waitingForAnchor.value = false
        return routeManager.finishRecording(name.ifBlank { "route-${System.currentTimeMillis() / 1000}" })
    }

    fun cancel() {
        _imuOnly.value = false; _waitingForAnchor.value = false
        routeManager.cancelRecording()
    }

    fun activate(id: String) = routeManager.activateRoute(id)
    fun deactivate() = routeManager.deactivateRoute()
    fun delete(id: String) = routeManager.deleteRoute(id)
    fun addWaypoint(lat: Double, lon: Double) = routeManager.addWaypoint(lat, lon)
}
