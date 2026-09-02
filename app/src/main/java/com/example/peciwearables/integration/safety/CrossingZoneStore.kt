package com.example.peciwearables.integration.safety

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject


class CrossingZoneStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _zones = MutableStateFlow(load())
    val zones: StateFlow<List<CrossingZone>> = _zones

    fun add(zone: CrossingZone) {
        val updated = _zones.value.filter { it.id != zone.id } + zone
        _zones.value = updated
        save(updated)
    }

    fun remove(id: String) {
        val updated = _zones.value.filter { it.id != id }
        _zones.value = updated
        save(updated)
    }

    private fun load(): List<CrossingZone> {
        val raw = prefs.getString(KEY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CrossingZone(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    latitude = o.getDouble("lat"),
                    longitude = o.getDouble("lon"),
                    radiusMeters = o.optDouble("r", 15.0).toFloat(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<CrossingZone>) {
        val arr = JSONArray()
        list.forEach { z ->
            arr.put(
                JSONObject()
                    .put("id", z.id)
                    .put("name", z.name)
                    .put("lat", z.latitude)
                    .put("lon", z.longitude)
                    .put("r", z.radiusMeters)
            )
        }
        prefs.edit().putString(KEY_JSON, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "peci_safety_prefs"
        private const val KEY_JSON = "crossing_zones"
    }
}
