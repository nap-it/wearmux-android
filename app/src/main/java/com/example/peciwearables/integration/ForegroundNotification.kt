package com.example.peciwearables.integration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.peciwearables.MainActivity
import com.example.peciwearables.R

object ForegroundNotification {
    const val CHANNEL_ID = "wearable_service"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wearable Streaming",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Streaming de dados dos wearables" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("PeciWearables")
            .setContentText("Streaming de wearables ativo")
            .setSmallIcon(R.drawable.glasses_solid_full)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
