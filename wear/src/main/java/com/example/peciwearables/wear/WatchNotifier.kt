package com.example.peciwearables.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log


object WatchNotifier {

    private const val TAG = "WatchNotifier"

 
    private const val CHANNEL_WARNING_ID = "peci_warning_v3"
    private const val CHANNEL_DANGER_ID = "peci_danger_v3"
    private const val CHANNEL_SAFE_ID = "peci_safe_v1"

    private const val NOTIF_BASE_WARNING = 5100
    private const val NOTIF_BASE_DANGER = 5200
    private const val NOTIF_BASE_SAFE = 5300
    @Volatile private var seq = 0

    fun showWarning(context: Context, title: String, body: String) {
        launchAlert(context, title, body, danger = false)
        ensureChannel(context, CHANNEL_WARNING_ID)
        showInternal(context, CHANNEL_WARNING_ID, NOTIF_BASE_WARNING + (seq++ and 0x3F), title, body, danger = false)
    }

    fun showDanger(context: Context, title: String, body: String) {
        launchAlert(context, title, body, danger = true)
        ensureChannel(context, CHANNEL_DANGER_ID)
        showInternal(context, CHANNEL_DANGER_ID, NOTIF_BASE_DANGER + (seq++ and 0x3F), title, body, danger = true)
    }

    fun showSafe(context: Context, title: String, body: String) {
        launchAlertSafe(context, title, body)
        ensureChannel(context, CHANNEL_SAFE_ID)
        showInternal(context, CHANNEL_SAFE_ID, NOTIF_BASE_SAFE + (seq++ and 0x3F), title, body, danger = false)
    }

    private fun launchAlertSafe(context: Context, title: String, body: String) {
        try {
            val intent = Intent(context, AlertActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_NO_HISTORY
                        or Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                )
                putExtra(AlertActivity.EXTRA_TITLE, title)
                putExtra(AlertActivity.EXTRA_BODY, body)
                putExtra(AlertActivity.EXTRA_DANGER, false)
                putExtra(AlertActivity.EXTRA_SAFE, true)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "launchAlertSafe falhou: ${e.message}")
        }
    }

    /** Lança a Activity de overlay que aparece sobre o watchface. */
    private fun launchAlert(context: Context, title: String, body: String, danger: Boolean) {
        try {
            val intent = Intent(context, AlertActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_NO_HISTORY
                        or Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                )
                putExtra(AlertActivity.EXTRA_TITLE, title)
                putExtra(AlertActivity.EXTRA_BODY, body)
                putExtra(AlertActivity.EXTRA_DANGER, danger)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "launchAlert falhou: ${e.message}")
        }
    }

    private fun showInternal(
        context: Context,
        channelId: String,
        id: Int,
        title: String,
        body: String,
        danger: Boolean,
    ) {
        try {
            val tap = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val builder = Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setCategory(if (danger) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
                .setStyle(Notification.BigTextStyle().bigText(body))

            if (danger) {
                builder.setColor(0xFFEF4444.toInt())
                builder.setColorized(true)
                @Suppress("DEPRECATION")
                builder.setPriority(Notification.PRIORITY_MAX)
                builder.setVisibility(Notification.VISIBILITY_PUBLIC)
                // Ongoing impede o sistema de descartar a heads-up cedo
                // demais — equivalente a "alarm que persiste".
                builder.setOngoing(false)
            } else {
                builder.setColor(0xFFFFB74D.toInt())
                @Suppress("DEPRECATION")
                builder.setPriority(Notification.PRIORITY_HIGH)
                builder.setVisibility(Notification.VISIBILITY_PUBLIC)
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, builder.build())

            // O canal já tem vibração, mas reforçamos com um pulse extra
            // para chamar mesmo a atenção em caso de PERIGO.
            if (danger) {
                Vibrations.play(context, WatchProtocol.VibratePattern.ALARM)
            } else {
                Vibrations.play(context, WatchProtocol.VibratePattern.DOUBLE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "showInternal($channelId) falhou: ${e.message}")
        }
    }

    private fun ensureChannel(context: Context, channelId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) != null) return

        val danger = channelId == CHANNEL_DANGER_ID
        val safe   = channelId == CHANNEL_SAFE_ID
        val name = when {
            danger -> "PECI · Perigo"
            safe   -> "PECI · Seguro"
            else   -> "PECI · Aviso"
        }
        val description = when {
            danger -> "Alertas críticos com vibração intensa."
            safe   -> "Confirmações de travessia segura."
            else   -> "Avisos da app PECI com vibração subtil."
        }

        val importance = if (danger)
            NotificationManager.IMPORTANCE_HIGH // dispara heads-up automático
        else
            NotificationManager.IMPORTANCE_HIGH // AVISO também heads-up (antes era DEFAULT, ficava só no shade)

        // Patterns de vibração — Android NotificationChannel.setVibrationPattern
        // espera long[] com tempos alternados (off, on, off, on, …).
        val vibration: LongArray = if (danger)
            longArrayOf(0, 350, 150, 350, 150, 350, 150, 500)
        else
            longArrayOf(0, 100, 80, 100)

        val attrs = AudioAttributes.Builder()
            .setUsage(if (danger) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(channelId, name, importance).apply {
            this.description = description
            enableVibration(true)
            vibrationPattern = vibration
            enableLights(true)
            lightColor = when {
                danger -> 0xFFEF4444.toInt()  // vermelho
                safe   -> 0xFF4CAF50.toInt()  // verde
                else   -> 0xFFFFB74D.toInt()  // laranja
            }
            // Bypass do "Não Incomodar" — para PERIGO ser visível mesmo com
            // o relógio em modo silencioso/cinema.
            if (danger) setBypassDnd(true)
            // Lockscreen: PERIGO mostra título+texto, AVISO só ícone.
            lockscreenVisibility = if (danger)
                Notification.VISIBILITY_PUBLIC
            else
                Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
            setSound(
                RingtoneManager.getDefaultUri(
                    if (danger) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
                ),
                attrs,
            )
        }
        nm.createNotificationChannel(channel)
    }
}
