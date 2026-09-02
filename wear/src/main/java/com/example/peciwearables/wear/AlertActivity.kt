package com.example.peciwearables.wear

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.example.peciwearables.wear.R


class AlertActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TITLE = "alert_title"
        const val EXTRA_BODY = "alert_body"
        const val EXTRA_DANGER = "alert_danger"
        const val EXTRA_SAFE = "alert_safe"
        private const val AUTO_DISMISS_MS = 8_000L
    }

    private val autoDismissHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mostra sobre o lockscreen e liga o ecrã se estiver desligado.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        // Mantém o ecrã ligado durante o aviso.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Aviso"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        val danger = intent.getBooleanExtra(EXTRA_DANGER, false)
        val safe = intent.getBooleanExtra(EXTRA_SAFE, false)

        // Vibra de imediato — reforça mesmo se o canal de notificação tiver
        // sido configurado em silencioso.
        Vibrations.play(
            this,
            when {
                danger -> WatchProtocol.VibratePattern.ALARM
                safe   -> WatchProtocol.VibratePattern.DOUBLE
                else   -> WatchProtocol.VibratePattern.DOUBLE
            },
        )

        autoDismissHandler.postDelayed({ if (!isFinishing) finish() }, AUTO_DISMISS_MS)

        setContent {
            AlertScreen(title, body, danger = danger, safe = safe, onDismiss = ::finish)
        }
    }

    override fun onDestroy() {
        autoDismissHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

@Composable
private fun AlertScreen(title: String, body: String, danger: Boolean, safe: Boolean = false, onDismiss: () -> Unit) {
    val accent = when {
        danger -> Color(0xFFEF4444)   // vermelho
        safe   -> Color(0xFF4CAF50)   // verde
        else   -> Color(0xFFFFB74D)   // laranja
    }
    val bg = when {
        danger -> Color(0xCC1A0A0A)   // fundo escuro vermelho
        safe   -> Color(0xCC0A1A0A)   // fundo escuro verde
        else   -> Color(0xCC1A130A)   // fundo escuro laranja
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Disco colorido com ícone — visualmente forte.
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                if (safe) {
                    Image(
                        painter = painterResource(R.drawable.ic_walk),
                        contentDescription = "Seguro para atravessar",
                        modifier = Modifier.size(36.dp),
                        colorFilter = ColorFilter.tint(Color.Black),
                    )
                } else {
                    Text(
                        text = if (danger) "!" else "i",
                        color = Color.Black,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.size(10.dp))
            Text(
                text = when {
                    danger -> "PERIGO"
                    safe   -> "SEGURO"
                    else   -> "AVISO"
                },
                color = accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (body.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = body,
                    color = Color(0xFFCCCCCC),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.size(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x33FFFFFF))
                    .clickable { onDismiss() }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "OK",
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
