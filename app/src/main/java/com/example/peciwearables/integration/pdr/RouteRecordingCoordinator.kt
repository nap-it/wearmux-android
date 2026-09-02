package com.example.peciwearables.integration.pdr

import com.example.peciwearables.integration.sensors.PhoneGpsLocation
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
            onLog("🛣 Trajeto iniciado (anchor GPS ok, só IMU a partir daqui)")
        } else {
            _waitingForAnchor.value = true
            onLog("🛣 A aguardar primeiro fix GPS para ancorar o trajeto (depois só IMU)")
        }
    }

    /** Chamado quando chega o primeiro fix GPS depois do start. */
    fun onAnchorObtained(gps: PhoneGpsLocation, onLog: (String) -> Unit, onWaypointCount: (Int) -> Unit) {
        if (!_waitingForAnchor.value) return
        _waitingForAnchor.value = false
        _imuOnly.value = true
        routeManager.addWaypoint(gps.latitude, gps.longitude)
        onWaypointCount(1)
        onLog("🎯 Anchor GPS obtido — trajeto a seguir só com IMU")
    }

    fun finish(name: String): SavedRoute? {
        _imuOnly.value = false; _waitingForAnchor.value = false
        return routeManager.finishRecording(name.ifBlank { "trajeto-${System.currentTimeMillis() / 1000}" })
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
