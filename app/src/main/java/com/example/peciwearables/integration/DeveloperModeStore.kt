package com.example.peciwearables.integration

import android.content.Context

/**
 * Persiste o estado do Modo desenvolvimento (mostrar/esconder ferramentas
 * técnicas na UI). Não controla nenhum pipeline em runtime — apenas
 * visibilidade/acesso a ecrãs e controlos de debug.
 */
class DeveloperModeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private companion object {
        private const val PREFS_NAME = "peci_developer_settings"
        private const val KEY_ENABLED = "developer_mode_enabled"
    }
}
