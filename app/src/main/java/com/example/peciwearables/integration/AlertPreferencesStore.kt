package com.example.peciwearables.integration

import android.content.Context

/**
 * Persiste as preferências de alerta (vibração/som) mostradas em
 * Definições (modo Normal). Ainda não controla nenhum pipeline de alerta em
 * runtime — apenas guarda a preferência do utilizador para uso futuro.
 */
class AlertPreferencesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isVibrationEnabled(): Boolean = prefs.getBoolean(KEY_VIBRATION, true)

    fun setVibrationEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATION, value).apply()
    }

    fun isSoundEnabled(): Boolean = prefs.getBoolean(KEY_SOUND, true)

    fun setSoundEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND, value).apply()
    }

    private companion object {
        private const val PREFS_NAME = "peci_alert_preferences"
        private const val KEY_VIBRATION = "vibration_enabled"
        private const val KEY_SOUND = "sound_enabled"
    }
}
