package com.wearmux.android.integration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.wearmux.android.MainActivity
import com.wearmux.android.R

object ForegroundNotification {
    const val CHANNEL_ID = "wearable_service"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wearable Streaming",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Wearable data streaming" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("WearMux")
            .setContentText("Wearable streaming active")
            .setSmallIcon(R.drawable.glasses_solid_full)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
