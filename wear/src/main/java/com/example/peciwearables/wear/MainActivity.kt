package com.example.peciwearables.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* utilizador volta a tocar se negar */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissions()
        setContent {
            MaterialTheme {
                WatchHomeScreen(
                    onStartImu = { startImuService() },
                    onStopImu = { stopImuService() },
                    onSetRate = { rate -> startImuService(rate) },
                    onTestBeep = { BeepPlayer.playLocal(this, 880, 250) },
                    onTestVibrate = { Vibrations.play(this, WatchProtocol.VibratePattern.DOUBLE) },
                    onTestWarning = { WatchNotifier.showWarning(this, "PECI", "Aviso de teste") },
                    onTestDanger = { WatchNotifier.showDanger(this, "PECI", "Alerta crítico de teste") },
                    onSendAction = { actionId -> sendActionToPhone(actionId) },
                )
            }
        }
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        listOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).forEach { p ->
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed += p
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startImuService(rateHz: Int? = null) {
        val intent = Intent(this, ImuStreamingService::class.java).setAction(ImuStreamingService.ACTION_START)
        if (rateHz != null) {
            intent.putExtra(ImuStreamingService.EXTRA_SAMPLE_RATE_HZ, rateHz)
        }
        startForegroundService(intent)
    }

    private fun stopImuService() {
        startService(
            Intent(this, ImuStreamingService::class.java).setAction(ImuStreamingService.ACTION_STOP)
        )
    }

    private fun sendActionToPhone(actionId: Int) {
        val client = Wearable.getMessageClient(this)
        val cap = Wearable.getCapabilityClient(this)
        // lifecycleScope cancela com a Activity — evita fugas e Tasks
        // pendentes quando o utilizador fecha a app a meio de um envio.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val info = withTimeoutOrNull(3_000) {
                        Tasks.await(cap.getCapability(WatchProtocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE))
                    } ?: return@withContext
                    val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull() ?: return@withContext
                    client.sendMessage(node.id, WatchProtocol.PATH_WATCH_ACTION, byteArrayOf(actionId.toByte()))
                } catch (_: Exception) { /* ignore */ }
            }
        }
    }
}

// ---------------- Paleta (alinhada com :app/Constants.kt) ----------------

private val Background = Color(0xFF0D0D11)
private val CardBg = Color(0xFF161821)
private val CardElevated = Color(0xFF1F2230)
private val Accent = Color(0xFF7B61FF)
private val AccentMuted = Color(0xFF5B45D9)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFFB0B3C0)
private val TextMuted = Color(0xFF6B7280)
private val Success = Color(0xFF34D399)
private val Warning = Color(0xFFFFB74D)
private val Error = Color(0xFFEF4444)

@Composable
private fun WatchHomeScreen(
    onStartImu: () -> Unit,
    onStopImu: () -> Unit,
    onSetRate: (Int) -> Unit,
    onTestBeep: () -> Unit,
    onTestVibrate: () -> Unit,
    onTestWarning: () -> Unit,
    onTestDanger: () -> Unit,
    onSendAction: (Int) -> Unit,
) {
    val streaming by ImuStreamingService.isStreaming.collectAsStateWithLifecycle()
    val sampleRate by ImuStreamingService.sampleRateHz.collectAsStateWithLifecycle()
    val battery by ImuStreamingService.batteryPct.collectAsStateWithLifecycle()
    val heartRate by ImuStreamingService.heartRateBpm.collectAsStateWithLifecycle()
    val sps by ImuStreamingService.sentSampleCount.collectAsStateWithLifecycle()
    val lastError by ImuStreamingService.lastError.collectAsStateWithLifecycle()

    val glassesState by PhoneStateMirror.glassesState.collectAsStateWithLifecycle()
    val wristbandState by PhoneStateMirror.wristbandState.collectAsStateWithLifecycle()
    val phoneSensors by PhoneStateMirror.phoneSensorsActive.collectAsStateWithLifecycle()
    val deviceCount by PhoneStateMirror.deviceCount.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.background(Background),
        timeText = { TimeText() },
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Background),
            state = rememberScalingLazyListState(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { HeroHeader(streaming, sampleRate, battery, heartRate, sps, deviceCount) }

            item {
                BigChip(
                    label = if (streaming) "Parar IMU" else "Iniciar IMU",
                    color = if (streaming) Error else Accent,
                    contentColor = if (streaming) Color.White else Color.Black,
                    onClick = if (streaming) onStopImu else onStartImu,
                )
            }

            item {
                BigChip(
                    label = if (phoneSensors) "Parar trajeto telem." else "Iniciar trajeto telem.",
                    color = CardElevated,
                    contentColor = TextPrimary,
                    onClick = {
                        onSendAction(
                            if (phoneSensors) WatchProtocol.WatchAction.STOP_TRAJECTORY
                            else WatchProtocol.WatchAction.START_TRAJECTORY
                        )
                    },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SmallChip("Beep", CardElevated, TextPrimary, Modifier.weight(1f), onTestBeep)
                    SmallChip("Vibrar", CardElevated, TextPrimary, Modifier.weight(1f), onTestVibrate)
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SmallChip("Aviso", Warning, Color.Black, Modifier.weight(1f), onTestWarning)
                    SmallChip("Perigo", Error, Color.White, Modifier.weight(1f), onTestDanger)
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SmallChip("50 Hz", CardElevated, TextPrimary, Modifier.weight(1f)) { onSetRate(50) }
                    SmallChip("100 Hz", CardElevated, TextPrimary, Modifier.weight(1f)) { onSetRate(100) }
                    SmallChip("200 Hz", CardElevated, TextPrimary, Modifier.weight(1f)) { onSetRate(200) }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SmallChip("Beep telem.", CardElevated, TextPrimary, Modifier.weight(1f)) {
                        onSendAction(WatchProtocol.WatchAction.AUDIO_BEEP)
                    }
                    SmallChip("Ligar BLE", AccentMuted, Color.White, Modifier.weight(1f)) {
                        onSendAction(WatchProtocol.WatchAction.CONNECT_ALL)
                    }
                }
            }

            item { SectionTitle("Wearables") }
            item { DeviceCardLine("Omi", glassesState.short(), glassesState.color()) }
            item { DeviceCardLine("Sole", wristbandState.short(), wristbandState.color()) }
            item {
                DeviceCardLine(
                    "Sensores telem.",
                    if (phoneSensors) "Activos" else "Off",
                    if (phoneSensors) Success else TextMuted,
                )
            }

            if (lastError != null) {
                item {
                    Text(
                        text = "⚠ $lastError",
                        color = Error,
                        textAlign = TextAlign.Center,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroHeader(streaming: Boolean, sampleRate: Int, battery: Int, heartRate: Int, sps: Long, deviceCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (streaming) Success else Accent),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = if (streaming) "A transmitir" else "PECI",
            color = if (streaming) Success else Accent,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (streaming) "$sps amostras · ${sampleRate} Hz" else "$deviceCount disp. ligados",
            color = TextSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "bateria ${battery}% · bpm ${if (heartRate > 0) heartRate.toString() else "—"}",
            color = TextMuted,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BigChip(label: String, color: Color, contentColor: Color, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp) },
        colors = ChipDefaults.chipColors(
            backgroundColor = color,
            contentColor = contentColor,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SmallChip(
    label: String,
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        colors = ChipDefaults.chipColors(
            backgroundColor = color,
            contentColor = contentColor,
        ),
        modifier = modifier,
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        textAlign = TextAlign.Center,
    )
}

/** Card pequeno para um dispositivo do telemóvel — coerente com cards do app. */
@Composable
private fun DeviceCardLine(name: String, status: String, dotColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.size(8.dp))
        Text(name, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(status, color = TextSecondary, fontSize = 11.sp)
    }
}

private fun PhoneStateMirror.BleState.short(): String = when (this) {
    PhoneStateMirror.BleState.CONNECTED, PhoneStateMirror.BleState.READY -> "Ligado"
    PhoneStateMirror.BleState.CONNECTING, PhoneStateMirror.BleState.DISCOVERING,
    PhoneStateMirror.BleState.CONFIGURING -> "A ligar"
    PhoneStateMirror.BleState.ERROR -> "Erro"
    PhoneStateMirror.BleState.DISCONNECTED -> "Off"
}

private fun PhoneStateMirror.BleState.color(): Color = when (this) {
    PhoneStateMirror.BleState.CONNECTED, PhoneStateMirror.BleState.READY -> Success
    PhoneStateMirror.BleState.CONNECTING, PhoneStateMirror.BleState.DISCOVERING,
    PhoneStateMirror.BleState.CONFIGURING -> Warning
    PhoneStateMirror.BleState.ERROR -> Error
    PhoneStateMirror.BleState.DISCONNECTED -> TextMuted
}
