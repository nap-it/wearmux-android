package com.example.peciwearables.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.peciwearables.AppViewModel
import com.example.peciwearables.Constants
import com.example.peciwearables.integration.NavisensImuSource
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.watch.WatchClient
import com.example.peciwearables.ui.BleCandidateRow

/**
 * Substituição limpa para a tab Info.
 *
 * Estrutura: header curto + 4 device cards uniformes + 1 card de atalho para
 * a tab Mapa. Cada card é compacto (~88 dp) com botão de expandir que revela
 * detalhes pouco usados (logs, IP, sample rate). Sem `SensorsPdrSttSection`
 * colado por baixo — o STT/PDR fica na Settings.
 */
@Composable
fun CleanInfoScreen(viewModel: AppViewModel) {
    val phoneSensorsActive by viewModel.phoneSensorsActive.collectAsStateWithLifecycle()
    val pdrStepCount by viewModel.pdrStepCount.collectAsStateWithLifecycle()

    val watchState by viewModel.watchState.collectAsStateWithLifecycle()
    val watchName by viewModel.watchName.collectAsStateWithLifecycle()
    val watchBattery by viewModel.watchBattery.collectAsStateWithLifecycle()
    val watchSps by viewModel.watchSamplesPerSec.collectAsStateWithLifecycle()
    val watchRate by viewModel.watchSampleRateHz.collectAsStateWithLifecycle()
    val watchBpm by viewModel.watchHeartRateBpm.collectAsStateWithLifecycle()

    val glassesState by viewModel.glassesState.collectAsStateWithLifecycle()
    val glassesBattery by viewModel.glassesBattery.collectAsStateWithLifecycle()
    val glassesFw by viewModel.glassesFirmware.collectAsStateWithLifecycle()
    val glassesIp by viewModel.glassesIp.collectAsStateWithLifecycle()
    val glassesConnectedAddress by viewModel.glassesConnectedAddress.collectAsStateWithLifecycle()

    val wristbandState by viewModel.wristbandState.collectAsStateWithLifecycle()
    val wristbandBattery by viewModel.wristbandBattery.collectAsStateWithLifecycle()
    val wristbandActivity by viewModel.wristbandActivity.collectAsStateWithLifecycle()

    val esp32State by viewModel.esp32State.collectAsStateWithLifecycle()
    val isDeveloperMode by viewModel.isDeveloperMode.collectAsStateWithLifecycle()

    val connectedCount = listOf(
        phoneSensorsActive,
        watchState == WatchClient.State.STREAMING || watchState == WatchClient.State.AVAILABLE,
        glassesState == BleDeviceState.READY || glassesState == BleDeviceState.CONNECTED,
        wristbandState == BleDeviceState.READY || wristbandState == BleDeviceState.CONNECTED,
        esp32State == BleDeviceState.READY || esp32State == BleDeviceState.CONNECTED,
    ).count { it }

    val anyWearableVisible = isVisible(watchState) || isVisible(glassesState) ||
        isVisible(wristbandState) || isVisible(esp32State)

    var trajectoryFor by remember { mutableStateOf<NavisensImuSource?>(null) }
    var configureDevice by remember { mutableStateOf<ConnectableDevice?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Header(connectedCount)

        CandidateScanSection(viewModel)

        Text(
            text = "Dispositivos conectados",
            color = Constants.primaryTextColor,
            fontSize = 15.sp,
        )

        DeviceCard(
            icon = Icons.Filled.PhoneAndroid,
            title = "Telemóvel",
            subtitle = if (phoneSensorsActive) "Sensores activos · $pdrStepCount passos" else "Sensores em repouso",
            statusColor = if (phoneSensorsActive) ColorOk else ColorIdle,
            primaryAction = if (phoneSensorsActive) "Parar sensores" else "Iniciar sensores",
            onPrimary = {
                if (phoneSensorsActive) viewModel.stopPhoneSensors() else viewModel.startPhoneSensors()
            },
            secondaryAction = if (isDeveloperMode) "Trajeto" else null,
            onSecondary = {
                if (!phoneSensorsActive) viewModel.startPhoneSensors()
                trajectoryFor = NavisensImuSource.PHONE
            },
        )

        if (!anyWearableVisible) {
            NoWearablesConnectedHint()
        }

        if (isVisible(watchState)) {
            DeviceCard(
                icon = Icons.Filled.Watch,
                title = watchName ?: "Galaxy Watch",
                subtitle = describeWatch(watchState, watchBattery, watchRate, watchSps, watchBpm),
                statusColor = colorFor(watchState),
                primaryAction = when (watchState) {
                    WatchClient.State.STREAMING -> "Parar IMU"
                    WatchClient.State.AVAILABLE -> "Iniciar IMU"
                    else -> null
                },
                secondaryAction = if (isDeveloperMode && watchState == WatchClient.State.STREAMING) "Trajeto"
                    else if (watchState == WatchClient.State.AVAILABLE) "Beep" else null,
                onPrimary = {
                    if (watchState == WatchClient.State.STREAMING) viewModel.watchStopImu()
                    else if (watchState == WatchClient.State.AVAILABLE) viewModel.watchStartImu()
                },
                onSecondary = {
                    if (watchState == WatchClient.State.STREAMING) trajectoryFor = NavisensImuSource.WATCH
                    else viewModel.watchBeep()
                },
                onConfigure = { configureDevice = ConnectableDevice.GALAXY_WATCH },
                hint = when (watchState) {
                    WatchClient.State.DISCONNECTED ->
                        "Empareelhe o relógio via Galaxy Wearable no telemóvel."
                    WatchClient.State.REMOTE ->
                        "Watch está fora de alcance Bluetooth. Aproxima-te (~10 m) para o usar."
                    else -> null
                },
            )
        }

        val glassesReady = glassesState == BleDeviceState.READY || glassesState == BleDeviceState.CONNECTED
        if (isVisible(glassesState)) {
            DeviceCard(
                icon = Icons.Outlined.Visibility,
                title = "Óculos",
                subtitle = describeGlasses(glassesState, glassesBattery, glassesIp, glassesFw),
                statusColor = colorForBle(glassesState),
                primaryAction = if (glassesState == BleDeviceState.DISCONNECTED || glassesState == BleDeviceState.ERROR)
                    "Ligar" else "Reconectar",
                secondaryAction = if (isDeveloperMode && glassesReady) "Trajeto" else null,
                onPrimary = { viewModel.connectGlasses() },
                onSecondary = { trajectoryFor = NavisensImuSource.GLASSES },
                onConfigure = { configureDevice = ConnectableDevice.GLASSES },
                address = glassesConnectedAddress,
            )
        }

        if (isVisible(wristbandState)) {
            DeviceCard(
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                title = "Pulseira",
                subtitle = describeSole(wristbandState, wristbandBattery, wristbandActivity),
                statusColor = colorForBle(wristbandState),
                primaryAction = if (wristbandState == BleDeviceState.DISCONNECTED || wristbandState == BleDeviceState.ERROR)
                    "Ligar" else "Reconectar",
                onPrimary = { viewModel.connectWristband() },
                onConfigure = { configureDevice = ConnectableDevice.WRISTBAND },
            )
        }

        if (isVisible(esp32State)) {
            DeviceCard(
                icon = Icons.Outlined.Visibility,
                title = "ESP32 Camera",
                subtitle = if (esp32State == BleDeviceState.CONNECTED) "Conectado via Wi-Fi" else "Manual",
                statusColor = colorForBle(esp32State),
                primaryAction = if (esp32State == BleDeviceState.DISCONNECTED || esp32State == BleDeviceState.ERROR)
                    "Ligar" else "Reconectar",
                onPrimary = { viewModel.connectEsp32() },
                onConfigure = { configureDevice = ConnectableDevice.ESP32_CAM },
            )
        }
    }

    trajectoryFor?.let { src ->
        MiniTrajectoryDialog(
            viewModel = viewModel,
            forSource = src,
            title = when (src) {
                NavisensImuSource.PHONE -> "Telemóvel"
                NavisensImuSource.GLASSES -> "Óculos"
                NavisensImuSource.WATCH -> "Galaxy Watch"
                NavisensImuSource.FUSED -> "Fusão IMU (N fontes)"
            },
            onDismiss = { trajectoryFor = null },
        )
    }

    configureDevice?.let { dev ->
        DeviceConnectionDialog(
            viewModel = viewModel,
            device = dev,
            onDismiss = { configureDevice = null },
        )
    }
}

@Composable
private fun Header(connectedCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "PECI Wearables",
            color = Constants.primaryTextColor,
            fontSize = 22.sp,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = if (connectedCount == 1) "1 dispositivo conectado" else "$connectedCount dispositivos conectados",
            color = Constants.secondaryTextColor,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun CandidateScanSection(viewModel: AppViewModel) {
    val bleCandidates by viewModel.bleCandidates.collectAsStateWithLifecycle()
    val candidateScanRunning by viewModel.candidateScanRunning.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Constants.cardBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Procurar candidatos", color = Constants.primaryTextColor, fontSize = 15.sp)
        Text(
            "Procure dispositivos próximos via Bluetooth.",
            color = Constants.secondaryTextColor,
            fontSize = 12.sp,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.discoverBleCandidates() },
                enabled = !candidateScanRunning,
                colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
                modifier = Modifier.weight(1f),
            ) {
                Text("Procurar", color = Color.Black, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { viewModel.stopDiscoverBleCandidates() },
                enabled = candidateScanRunning,
                modifier = Modifier.weight(1f),
            ) {
                Text("Parar procura", fontSize = 12.sp)
            }
        }

        if (candidateScanRunning) {
            Text(
                text = "A procurar candidatos BLE...",
                color = Constants.accentColor,
                fontSize = 12.sp,
            )
        }

        if (bleCandidates.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bleCandidates.forEach { candidate ->
                    BleCandidateRow(
                        candidate = candidate,
                        onConnect = { viewModel.connectCandidate(candidate.address) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    statusColor: Color,
    primaryAction: String?,
    onPrimary: () -> Unit,
    secondaryAction: String? = null,
    onSecondary: () -> Unit = {},
    onConfigure: (() -> Unit)? = null,
    hint: String? = null,
    address: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Constants.cardBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Constants.cardBackgroundElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = statusColor)
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Constants.primaryTextColor, fontSize = 15.sp)
                Text(subtitle, color = Constants.secondaryTextColor, fontSize = 12.sp)
                address?.let {
                    Text(
                        text = "Identificador: $it",
                        color = Constants.secondaryTextColor,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
            StatusDot(statusColor)
            Spacer(Modifier.size(6.dp))
            onConfigure?.let { cb ->
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Configurar",
                    tint = Constants.secondaryTextColor,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { cb() },
                )
                Spacer(Modifier.size(8.dp))
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = Constants.secondaryTextColor,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            primaryAction?.let { label ->
                Button(
                    onClick = onPrimary,
                    colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
                    modifier = Modifier.weight(1f),
                ) { Text(label, color = Color.Black, fontSize = 12.sp) }
            }
            secondaryAction?.let { label ->
                Button(
                    onClick = onSecondary,
                    colors = ButtonDefaults.buttonColors(containerColor = Constants.neutralButton),
                    modifier = Modifier.weight(1f),
                ) { Text(label, color = Color.White, fontSize = 12.sp) }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                hint?.let {
                    Text(it, color = Constants.secondaryTextColor, fontSize = 11.sp)
                }
                Text(
                    text = "Para mais opções avança para Settings.",
                    color = Constants.secondaryTextColor,
                    fontSize = 11.sp,
                )
            }
        }

        // Mostrar hint inline quando o card está colapsado mas há hint
        // crítica (ex: watch desligado a pedir Galaxy Wearable).
        if (!expanded && hint != null) {
            Text(
                text = hint,
                color = Constants.warningColor,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun NoWearablesConnectedHint() {
    Text(
        text = "Nenhum dispositivo ligado. Usa a procura abaixo para parear um.",
        color = Constants.secondaryTextColor,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

// -------------------- helpers de status --------------------

private val ColorOk get() = Constants.successColor
private val ColorIdle get() = Constants.idleColor
private val ColorWarn get() = Constants.warningColor
private val ColorErr get() = Constants.errorColor

private fun colorFor(state: WatchClient.State): Color = when (state) {
    WatchClient.State.STREAMING -> ColorOk
    WatchClient.State.AVAILABLE -> Constants.accentColor
    WatchClient.State.REMOTE -> ColorWarn   // amarelo — alcançável só via cloud
    WatchClient.State.ERROR -> ColorErr
    WatchClient.State.DISCONNECTED -> ColorIdle
}

private fun colorForBle(state: BleDeviceState): Color = when (state) {
    BleDeviceState.CONNECTED, BleDeviceState.READY -> ColorOk
    BleDeviceState.CONNECTING, BleDeviceState.DISCOVERING, BleDeviceState.CONFIGURING -> ColorWarn
    BleDeviceState.ERROR -> ColorErr
    BleDeviceState.DISCONNECTED -> ColorIdle
}

private fun isVisible(state: WatchClient.State): Boolean = when (state) {
    WatchClient.State.STREAMING, WatchClient.State.AVAILABLE, WatchClient.State.REMOTE -> true
    WatchClient.State.ERROR -> true
    WatchClient.State.DISCONNECTED -> false
}

private fun isVisible(state: BleDeviceState): Boolean = when (state) {
    BleDeviceState.CONNECTING, BleDeviceState.DISCOVERING, BleDeviceState.CONFIGURING,
    BleDeviceState.READY, BleDeviceState.CONNECTED, BleDeviceState.ERROR -> true
    BleDeviceState.DISCONNECTED -> false
}

private fun describeWatch(state: WatchClient.State, battery: Int, rate: Int, sps: Int, bpm: Int): String {
    val batteryStr = if (battery >= 0) "$battery%" else "—"
    val bpmStr = if (bpm > 0) {
        // Heurística simples: > 95 bpm em uso → caminhada activa.
        val tag = if (bpm >= 95) "🚶" else "❤"
        " · $tag ${bpm} bpm"
    } else ""
    return when (state) {
        WatchClient.State.STREAMING -> "A transmitir · $sps/s @ ${rate}Hz · ${batteryStr}$bpmStr"
        WatchClient.State.AVAILABLE -> "Pronto · bateria ${batteryStr}$bpmStr"
        WatchClient.State.REMOTE -> "Longe (cloud) · bateria ${batteryStr}$bpmStr"
        WatchClient.State.ERROR -> "Erro de comunicação"
        WatchClient.State.DISCONNECTED -> "Sem ligação"
    }
}

private fun describeGlasses(state: BleDeviceState, battery: Int, ip: String?, fw: String): String {
    val batteryStr = if (battery >= 0) "$battery%" else "—"
    val core = when (state) {
        BleDeviceState.CONNECTED, BleDeviceState.READY -> "Ligado · bateria $batteryStr"
        BleDeviceState.CONNECTING -> "A ligar..."
        BleDeviceState.DISCOVERING -> "A descobrir serviços..."
        BleDeviceState.CONFIGURING -> "A configurar..."
        BleDeviceState.ERROR -> "Erro BLE"
        BleDeviceState.DISCONNECTED -> "Desligado"
    }
    val extras = listOfNotNull(
        ip?.takeIf { it.isNotBlank() }?.let { "Wi-Fi $it" },
        fw.takeIf { it.isNotBlank() }?.let { "fw $it" },
    ).joinToString(" · ")
    return if (extras.isNotBlank()) "$core · $extras" else core
}

private fun describeSole(state: BleDeviceState, battery: Int, activity: String): String {
    val batteryStr = if (battery >= 0) "$battery%" else "—"
    return when (state) {
        BleDeviceState.CONNECTED, BleDeviceState.READY -> "Ligado · bateria $batteryStr · $activity"
        BleDeviceState.CONNECTING -> "A ligar..."
        BleDeviceState.DISCOVERING -> "A descobrir serviços..."
        BleDeviceState.CONFIGURING -> "A configurar..."
        BleDeviceState.ERROR -> "Erro BLE"
        BleDeviceState.DISCONNECTED -> "Desligado"
    }
}
