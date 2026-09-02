package com.example.peciwearables

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.peciwearables.integration.WearableService


class DebugBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DebugBroadcastReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "broadcast recebido: $action extras=${intent.extras?.keySet()}")

        // Reencaminha directamente para o WearableService — mesmas actions,
        // mesmos extras. O serviço já sabe como tratar cada uma.
        val forward = Intent(context, WearableService::class.java).apply {
            this.action = action
            intent.extras?.let { putExtras(it) }
        }
        try {
            context.startService(forward)
        } catch (e: Exception) {
            Log.w(TAG, "startService falhou (serviço parado?): ${e.message}")
        }
    }
}
