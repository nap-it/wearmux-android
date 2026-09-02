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
        appendLog("🎯 Anchor GPS obtido — trajeto a seguir só com IMU")
    }

    fun start() {
        if (routeManager.isRecording.value) return
        if (!phoneSensorsActive()) {
            startPhoneSensors()
            appendLog("📡 Sensores do telemóvel ativados para gravação de trajeto")
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
            appendLog("🛣 Trajeto iniciado (anchor GPS ok, só IMU a partir daqui)")
        } else {
            waitingForAnchorFix = true
            appendLog("🛣 A aguardar primeiro fix GPS para ancorar o trajeto (depois só IMU)")
        }
    }

    fun finish(name: String) {
        val finalName = name.ifBlank { "trajeto-${System.currentTimeMillis() / 1000}" }
        routeImuOnly = false; waitingForAnchorFix = false
        val route = routeManager.finishRecording(finalName)
        recordingWaypointCount.value = 0
        if (route == null) appendLog("⚠  Trajeto não guardado: precisa ≥2 waypoints")
        else appendLog("✅ Trajeto '${route.name}' guardado (${route.waypoints.size} pts)")
    }

    fun cancel() {
        routeImuOnly = false; waitingForAnchorFix = false
        routeManager.cancelRecording()
        recordingWaypointCount.value = 0
        appendLog("Gravação de trajeto cancelada")
    }

    fun activate(id: String) {
        routeManager.activateRoute(id)
        appendLog("🛣 Trajeto activo: ${routeManager.activeRoute.value?.name ?: "?"}")
    }

    fun deactivate() {
        routeManager.deactivateRoute()
        appendLog("Trajeto activo removido")
    }

    fun delete(id: String) {
        routeManager.deleteRoute(id)
        appendLog("Trajeto $id apagado")
    }
}
