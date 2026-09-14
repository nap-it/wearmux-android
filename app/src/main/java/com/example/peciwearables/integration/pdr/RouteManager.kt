package com.example.peciwearables.integration.pdr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Waypoint de um trajeto definido pelo utilizador.
 */
data class RouteWaypoint(
    val latitude: Double,
    val longitude: Double,
    val label: String = ""
)

/**
 * Trajeto guardado.
 */
data class SavedRoute(
    val id: String,
    val name: String,
    val waypoints: List<RouteWaypoint>,
    val createdAt: Long = System.currentTimeMillis()
)


class RouteManager(context: Context) {

    companion object {
        private const val TAG = "RouteManager"
        private const val PREFS_NAME = "peci_routes"
        private const val KEY_ROUTES = "saved_routes"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _routes = MutableStateFlow<List<SavedRoute>>(emptyList())
    val routes: StateFlow<List<SavedRoute>> = _routes

    private val _activeRoute = MutableStateFlow<SavedRoute?>(null)
    val activeRoute: StateFlow<SavedRoute?> = _activeRoute

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording


    private val _recordingWaypoints = MutableStateFlow<List<RouteWaypoint>>(emptyList())
    val recordingWaypoints: StateFlow<List<RouteWaypoint>> = _recordingWaypoints

    // Buffer de waypoints enquanto o utilizador está a desenhar um trajeto
    private val recordingBuffer = mutableListOf<RouteWaypoint>()

    init {
        loadRoutes()
    }

    /**
     * Começa a gravar um novo trajeto. O utilizador toca no mapa para adicionar waypoints.
     */
    fun startRecording() {
        recordingBuffer.clear()
        _recordingWaypoints.value = emptyList()
        _isRecording.value = true
        Log.d(TAG, "Route recording started")
    }

    /**
     * Adiciona um waypoint ao trajeto em gravação.
     */
    fun addWaypoint(lat: Double, lon: Double, label: String = "") {
        if (!_isRecording.value) return
        recordingBuffer.add(RouteWaypoint(lat, lon, label))
        _recordingWaypoints.value = recordingBuffer.toList()
        Log.d(TAG, "Waypoint added: ($lat, $lon) — total: ${recordingBuffer.size}")
    }

    /**
     * Termina a gravação e guarda o trajeto.
     */
    fun finishRecording(name: String): SavedRoute? {
        if (recordingBuffer.size < 2) {
            Log.w(TAG, "Route needs at least 2 waypoints")
            _isRecording.value = false
            return null
        }

        val route = SavedRoute(
            id = "route_${System.currentTimeMillis()}",
            name = name,
            waypoints = recordingBuffer.toList()
        )

        val current = _routes.value.toMutableList()
        current.add(route)
        _routes.value = current
        saveRoutes()

        recordingBuffer.clear()
        _recordingWaypoints.value = emptyList()
        _isRecording.value = false
        Log.d(TAG, "Route saved: ${route.name} (${route.waypoints.size} waypoints)")
        return route
    }

    fun cancelRecording() {
        recordingBuffer.clear()
        _recordingWaypoints.value = emptyList()
        _isRecording.value = false
    }

    /**
     * Ativa um trajeto para uso no PDR (map-matching).
     */
    fun activateRoute(routeId: String) {
        _activeRoute.value = _routes.value.find { it.id == routeId }
        Log.d(TAG, "Active route: ${_activeRoute.value?.name}")
    }

    fun deactivateRoute() {
        _activeRoute.value = null
    }

    fun deleteRoute(routeId: String) {
        _routes.value = _routes.value.filter { it.id != routeId }
        if (_activeRoute.value?.id == routeId) _activeRoute.value = null
        saveRoutes()
    }

    /**
     * Retorna os waypoints do trajeto ativo como pares (lat, lon)
     * para uso direto no PDR.
     */
    fun getActiveRouteWaypoints(): List<Pair<Double, Double>>? {
        return _activeRoute.value?.waypoints?.map { Pair(it.latitude, it.longitude) }
    }

    // ── Persistência ─────────────────────────────────────────────────────────

    private fun saveRoutes() {
        val arr = JSONArray()
        for (route in _routes.value) {
            val obj = JSONObject()
            obj.put("id", route.id)
            obj.put("name", route.name)
            obj.put("createdAt", route.createdAt)
            val wps = JSONArray()
            for (wp in route.waypoints) {
                val wpObj = JSONObject()
                wpObj.put("lat", wp.latitude)
                wpObj.put("lon", wp.longitude)
                wpObj.put("label", wp.label)
                wps.put(wpObj)
            }
            obj.put("waypoints", wps)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_ROUTES, arr.toString()).apply()
    }

    private fun loadRoutes() {
        val json = prefs.getString(KEY_ROUTES, null) ?: return
        try {
            val arr = JSONArray(json)
            val list = mutableListOf<SavedRoute>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val wps = obj.getJSONArray("waypoints")
                val waypoints = (0 until wps.length()).map { j ->
                    val wp = wps.getJSONObject(j)
                    RouteWaypoint(wp.getDouble("lat"), wp.getDouble("lon"), wp.optString("label", ""))
                }
                list.add(SavedRoute(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    waypoints = waypoints,
                    createdAt = obj.optLong("createdAt", 0)
                ))
            }
            _routes.value = list
            Log.d(TAG, "Loaded ${list.size} routes")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading routes: ${e.message}")
        }
    }
}
