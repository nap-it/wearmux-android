package com.wearmux.android.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
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

    // This Compose activity handles permissions directly through ComponentActivity.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* utilizador volta a tocar se negar */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissions()
        setContent {
            MaterialTheme {
                WatchHomeScreen(
                    watchName = watchName(),
                    onStartImu = { startImuService() },
                    onStopImu = { stopImuService() },
                    onSetRate = { rate -> startImuService(rate) },
                    onTestBeep = { BeepPlayer.playLocal(this, 880, 250) },
                    onTestVibrate = { Vibrations.play(this, WatchProtocol.VibratePattern.DOUBLE) },
                    onTestWarning = { WatchNotifier.showWarning(this, "WearMux", "Test warning") },
                    onTestDanger = { WatchNotifier.showDanger(this, "WearMux", "Test critical alert") },
                    onSendAction = { actionId -> sendActionToPhone(actionId) },
                )
            }
        }
    }

    private fun watchName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: "Galaxy Watch"

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

private val Background = Color(0xFF0A0C10)
private val CardBg = Color(0xFF12151D)
private val CardElevated = Color(0xFF1A2030)
private val CardBorder = Color(0xFF1D2230)
private val Accent = Color(0xFF4C6FFF)
private val AccentMuted = Color(0xFF3B5BE0)
private val TextPrimary = Color(0xFFF2F4F8)
private val TextSecondary = Color(0xFF8992A6)
private val Success = Color(0xFF3DDC97)
private val Warning = Color(0xFFF5A623)
private val Error = Color(0xFFEF4444)
private val Idle = Color(0xFF5B667A)

@Composable
private fun WatchHomeScreen(
    watchName: String,
    onStartImu: () -> Unit,
    onStopImu: () -> Unit,
    onSetRate: (Int) -> Unit,
    onTestBeep: () -> Unit,
    onTestVibrate: () -> Unit,
    onTestWarning: () -> Unit,
    onTestDanger: () -> Unit,
    onSendAction: (Int) -> Unit,
) {
    val listState = rememberScalingLazyListState()

    Scaffold(
        modifier = Modifier.background(Background),
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Background),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { HomeHeader() }
            phoneItems(watchName, onStartImu, onStopImu, onSetRate, onSendAction)
            testItems(onTestBeep, onTestVibrate, onTestWarning, onTestDanger)
        }
    }
}

private fun ScalingLazyListScope.phoneItems(
    watchName: String,
    onStartImu: () -> Unit,
    onStopImu: () -> Unit,
    onSetRate: (Int) -> Unit,
    onSendAction: (Int) -> Unit,
) {
    item {
        val glassesState by PhoneStateMirror.glassesState.collectAsStateWithLifecycle()
        val wristbandState by PhoneStateMirror.wristbandState.collectAsStateWithLifecycle()
        val onSearch = { onSendAction(WatchProtocol.WatchAction.CONNECT_ALL) }
        if (glassesState.isVisible() || wristbandState.isVisible()) ConnectAnotherDeviceStrip(onSearch)
        else GiantConnectButton(onSearch)
    }

    item { SectionTitle("CONNECTED DEVICES") }

    item {
        val streaming by ImuStreamingService.isStreaming.collectAsStateWithLifecycle()
        val sampleRate by ImuStreamingService.sampleRateHz.collectAsStateWithLifecycle()
        val battery by ImuStreamingService.batteryPct.collectAsStateWithLifecycle()
        val heartRate by ImuStreamingService.heartRateBpm.collectAsStateWithLifecycle()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DeviceCard(
                icon = R.drawable.ic_watch,
                title = watchName,
                subtitle = describeWatch(streaming, sampleRate, battery, heartRate),
                statusColor = if (streaming) Success else Accent,
                onClick = if (streaming) onStopImu else onStartImu,
            )
            ActionChip(
                label = if (streaming) "Stop IMU" else "Start IMU",
                background = if (streaming) Error else Accent,
                onClick = if (streaming) onStopImu else onStartImu,
            )
        }
    }

    item {
        val phoneSensors by PhoneStateMirror.phoneSensorsActive.collectAsStateWithLifecycle()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DeviceCard(
                icon = R.drawable.ic_phone,
                title = "Phone",
                subtitle = if (phoneSensors) "Sensors active" else "Sensors idle",
                statusColor = if (phoneSensors) Success else Idle,
                onClick = { onSendAction(phoneSensorsAction(phoneSensors)) },
            )
            ActionChip(
                label = if (phoneSensors) "Stop sensors" else "Start sensors",
                background = CardElevated,
                onClick = { onSendAction(phoneSensorsAction(phoneSensors)) },
            )
        }
    }

    item {
        val glassesState by PhoneStateMirror.glassesState.collectAsStateWithLifecycle()
        if (glassesState.isVisible()) {
            DeviceCard(
                icon = R.drawable.ic_glasses,
                title = "Glasses",
                subtitle = glassesState.statusText(),
                statusColor = glassesState.statusColor(),
            )
        }
    }
    item {
        val wristbandState by PhoneStateMirror.wristbandState.collectAsStateWithLifecycle()
        if (wristbandState.isVisible()) {
            DeviceCard(
                icon = R.drawable.ic_walk,
                title = "Wristband",
                subtitle = wristbandState.statusText(),
                statusColor = wristbandState.statusColor(),
            )
        }
    }

    item { SectionTitle("SAMPLE RATE") }
    item {
        val streaming by ImuStreamingService.isStreaming.collectAsStateWithLifecycle()
        val sampleRate by ImuStreamingService.sampleRateHz.collectAsStateWithLifecycle()
        ChipRow {
            listOf(50, 100, 200).forEach { hz ->
                SmallChip(
                    label = "$hz Hz",
                    background = if (streaming && sampleRate == hz) Accent else CardElevated,
                    modifier = Modifier.weight(1f),
                ) { onSetRate(hz) }
            }
        }
    }
    item {
        ActionChip(
            label = "Beep phone",
            background = CardElevated,
            onClick = { onSendAction(WatchProtocol.WatchAction.AUDIO_BEEP) },
        )
    }
    item {
        val lastError by ImuStreamingService.lastError.collectAsStateWithLifecycle()
        lastError?.let {
            Text(
                text = "⚠ $it",
                color = Error,
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
            )
        }
    }
}

private fun ScalingLazyListScope.testItems(
    onTestBeep: () -> Unit,
    onTestVibrate: () -> Unit,
    onTestWarning: () -> Unit,
    onTestDanger: () -> Unit,
) {
    item { SectionTitle("TESTS") }
    item {
        ChipRow {
            SmallChip("Beep", CardElevated, Modifier.weight(1f), onClick = onTestBeep)
            SmallChip("Vibrate", CardElevated, Modifier.weight(1f), onClick = onTestVibrate)
        }
    }
    item {
        ChipRow {
            SmallChip("Warning", Warning, Modifier.weight(1f), Color.Black, onTestWarning)
            SmallChip("Danger", Error, Modifier.weight(1f), onClick = onTestDanger)
        }
    }
}

private fun phoneSensorsAction(active: Boolean): Int =
    if (active) WatchProtocol.WatchAction.STOP_TRAJECTORY else WatchProtocol.WatchAction.START_TRAJECTORY

@Composable
private fun HomeHeader() {
    Text(
        text = "WearMux",
        color = TextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GiantConnectButton(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Accent, AccentMuted)))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_link),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = "Search Wearables",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ConnectAnotherDeviceStrip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardElevated)
            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_link),
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "Search another device",
            color = Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = TextSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun DeviceCard(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    statusColor: Color,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(CardElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(6.dp))
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(statusColor),
        )
    }
}

@Composable
private fun ActionChip(label: String, background: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun SmallChip(
    label: String,
    background: Color,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = contentColor, fontSize = 11.sp, maxLines = 1)
    }
}

private fun describeWatch(streaming: Boolean, rate: Int, battery: Int, bpm: Int): String {
    val batteryStr = if (battery >= 0) "$battery%" else "—"
    val bpmStr = if (bpm > 0) " · ❤ $bpm bpm" else ""
    return if (streaming) "Streaming @ ${rate}Hz · $batteryStr$bpmStr"
    else "Ready · battery $batteryStr$bpmStr"
}

private fun PhoneStateMirror.BleState.isVisible(): Boolean =
    this != PhoneStateMirror.BleState.DISCONNECTED

private fun PhoneStateMirror.BleState.statusText(): String = when (this) {
    PhoneStateMirror.BleState.CONNECTED, PhoneStateMirror.BleState.READY -> "Connected"
    PhoneStateMirror.BleState.CONNECTING -> "Connecting…"
    PhoneStateMirror.BleState.DISCOVERING -> "Discovering…"
    PhoneStateMirror.BleState.CONFIGURING -> "Configuring…"
    PhoneStateMirror.BleState.ERROR -> "Error"
    PhoneStateMirror.BleState.DISCONNECTED -> "Disconnected"
}

private fun PhoneStateMirror.BleState.statusColor(): Color = when (this) {
    PhoneStateMirror.BleState.CONNECTED, PhoneStateMirror.BleState.READY -> Success
    PhoneStateMirror.BleState.CONNECTING, PhoneStateMirror.BleState.DISCOVERING,
    PhoneStateMirror.BleState.CONFIGURING -> Warning
    PhoneStateMirror.BleState.ERROR -> Error
    PhoneStateMirror.BleState.DISCONNECTED -> Idle
}
