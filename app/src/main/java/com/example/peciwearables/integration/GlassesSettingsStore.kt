package com.example.peciwearables.integration

import android.content.Context

class GlassesSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadDesiredSettings(): GlassesSettings {
        return GlassesSettings(
            resolution = prefs.readIntOrNull(KEY_RESOLUTION),
            qualityFactor = prefs.readIntOrNull(KEY_QUALITY_FACTOR),
            cameraRateMs = prefs.readIntOrNull(KEY_CAMERA_RATE_MS),
        )
    }

    fun saveDesiredSettings(settings: GlassesSettings) {
        prefs.edit()
            .writeNullableInt(KEY_RESOLUTION, settings.resolution)
            .writeNullableInt(KEY_QUALITY_FACTOR, settings.qualityFactor)
            .writeNullableInt(KEY_CAMERA_RATE_MS, settings.cameraRateMs)
            .apply()
    }

    private fun android.content.SharedPreferences.readIntOrNull(key: String): Int? {
        return if (contains(key)) getInt(key, 0) else null
    }

    private fun android.content.SharedPreferences.Editor.writeNullableInt(
        key: String,
        value: Int?
    ): android.content.SharedPreferences.Editor {
        return if (value == null) remove(key) else putInt(key, value)
    }

    private companion object {
        private const val PREFS_NAME = "omi_glasses_settings"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_QUALITY_FACTOR = "quality_factor"
        private const val KEY_CAMERA_RATE_MS = "camera_rate_ms"
    }
}
