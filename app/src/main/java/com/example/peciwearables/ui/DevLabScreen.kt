package com.example.peciwearables.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.peciwearables.AppViewModel
import com.example.peciwearables.CameraScreen
import com.example.peciwearables.Constants
import com.example.peciwearables.integration.image.camera.CameraStreamStatus

/**
 * Aba "Dev/Laboratório": ferramentas técnicas de teste e monitorização em
 * tempo real, só visível quando o Modo desenvolvimento está ativo. Organiza
 * o conteúdo técnico já existente (câmara/YOLO/Depth, áudio, IMU) em
 * sub-tabs, sem duplicar lógica de negócio — cada sub-tab reaproveita o
 * código já escrito em CameraScreen (e, historicamente, em FotosScreen —
 * já removida; o seu conteúdo foi transcrito para AudioTab/ImuTab).
 */
@Composable
fun DevLabScreen(viewModel: AppViewModel) {
    val tabs = listOf("Visão geral", "Câmara", "Áudio", "IMU", "Logs")
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Dev / Laboratório",
            color = Constants.primaryTextColor,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Constants.cardBackgroundElevated,
            contentColor = Constants.accentColor,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            tabs.forEachIndexed { i, name ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { selectedTab = i },
                    text = {
                        Text(
                            name,
                            fontSize = 12.sp,
                            color = if (selectedTab == i) Constants.accentColor else Constants.secondaryTextColor,
                        )
                    },
                )
            }
        }

        when (tabs[selectedTab]) {
            "Visão geral" -> OverviewTab(viewModel)
            "Câmara" -> CameraScreen(viewModel)
            "Áudio" -> AudioTab(viewModel)
            "IMU" -> ImuTab(viewModel)
            "Logs" -> LogsTab(viewModel)
        }
    }
}

@Composable
private fun OverviewTab(viewModel: AppViewModel) {
    val cameraStreamHealth by viewModel.cameraStreamHealth.collectAsStateWithLifecycle()
    val glassesMicStreaming by viewModel.glassesMicStreaming.collectAsStateWithLifecycle()
    val imuStreamingEnabled by viewModel.imuStreamingEnabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Ferramentas de teste e monitorização em tempo real.",
            color = Constants.secondaryTextColor,
            fontSize = 12.sp,
        )

        OverviewRow("Câmara (vídeo)", cameraStreamStatusLabel(cameraStreamHealth.status), cameraStreamStatusColor(cameraStreamHealth.status))
        OverviewRow("Microfone", if (glassesMicStreaming) "Ativo" else "Inativo", if (glassesMicStreaming) Constants.successColor else Constants.secondaryTextColor)
        OverviewRow("IMU (movimento)", if (imuStreamingEnabled) "Ativo" else "Inativo", if (imuStreamingEnabled) Constants.successColor else Constants.secondaryTextColor)
    }
}

@Composable
private fun OverviewRow(label: String, statusLabel: String, statusColor: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Constants.cardBackground)
            .padding(14.dp),
    ) {
        Column {
            Text(label, color = Constants.primaryTextColor, fontSize = 14.sp)
            Text(statusLabel, color = statusColor, fontSize = 12.sp)
        }
    }
}

private fun cameraStreamStatusLabel(status: CameraStreamStatus): String = when (status) {
    CameraStreamStatus.GOOD -> "Boa ligação"
    CameraStreamStatus.UNSTABLE -> "Instável"
    CameraStreamStatus.LOST -> "Sem frames"
    CameraStreamStatus.IDLE -> "Stream inativo"
    CameraStreamStatus.DISCONNECTED -> "Óculos desligados"
}

private fun cameraStreamStatusColor(status: CameraStreamStatus): androidx.compose.ui.graphics.Color = when (status) {
    CameraStreamStatus.GOOD -> Constants.successColor
    CameraStreamStatus.UNSTABLE -> Constants.warningColor
    CameraStreamStatus.LOST -> Constants.errorColor
    CameraStreamStatus.IDLE -> Constants.secondaryTextColor
    CameraStreamStatus.DISCONNECTED -> Constants.secondaryTextColor
}

@Composable
private fun AudioTab(viewModel: AppViewModel) {
    val glassesState by viewModel.glassesState.collectAsStateWithLifecycle()
    val glassesMicStreaming by viewModel.glassesMicStreaming.collectAsStateWithLifecycle()
    val glassesMicStatusText by viewModel.glassesMicStatusText.collectAsStateWithLifecycle()
    val glassesMicDataText by viewModel.glassesMicDataText.collectAsStateWithLifecycle()
    val recordedAudios by viewModel.recordedAudios.collectAsStateWithLifecycle()
    val audioRecordingActive by viewModel.audioRecordingActive.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    val glassesActive = glassesState == com.example.peciwearables.integration.ble.BleDeviceState.READY ||
        glassesState == com.example.peciwearables.integration.ble.BleDeviceState.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Button(
                onClick = { viewModel.startMicrophone() },
                enabled = glassesActive && !glassesMicStreaming,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF26A69A)),
                modifier = Modifier.weight(1f),
            ) { Text("Iniciar mic", color = androidx.compose.ui.graphics.Color.Black, fontSize = 12.sp) }
            androidx.compose.material3.Button(
                onClick = { viewModel.stopMicrophone() },
                enabled = glassesActive && glassesMicStreaming,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFEF5350)),
                modifier = Modifier.weight(1f),
            ) { Text("Parar mic", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp) }
        }

        Text(
            text = "Mic: $glassesMicStatusText · $glassesMicDataText",
            color = if (glassesMicStreaming || audioRecordingActive) Constants.successColor else Constants.secondaryTextColor,
            fontSize = 11.sp,
        )

        if (!glassesActive) {
            Text(
                text = "Liga os óculos na aba Dispositivos para ativar o microfone.",
                color = Constants.secondaryTextColor.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }

        Text("Áudios gravados", color = Constants.primaryTextColor, style = MaterialTheme.typography.titleSmall)

        if (recordedAudios.isEmpty()) {
            Text("Sem gravações ainda.", color = Constants.secondaryTextColor, fontSize = 12.sp)
        } else {
            recordedAudios.reversed().forEach { audio ->
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Constants.cardBackground)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Audio #${audio.index} | ${audio.timestamp}", color = Constants.primaryTextColor, fontSize = 12.sp)
                        Text(
                            "${"%.1f".format(audio.durationSec)}s @ ${audio.sampleRateHz}Hz",
                            color = Constants.secondaryTextColor,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                    androidx.compose.material3.Button(
                        onClick = {
                            try {
                                player?.release()
                                player = android.media.MediaPlayer().apply {
                                    setDataSource(audio.filePath)
                                    prepare()
                                    setOnCompletionListener { it.release(); player = null }
                                    start()
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Erro a reproduzir audio", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF3F51B5)),
                    ) { Text("Play", color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable
private fun ImuTab(viewModel: AppViewModel) {
    val latestImuSamples by viewModel.latestImuSamples.collectAsStateWithLifecycle()
    val latestGlassesImu by viewModel.latestGlassesImu.collectAsStateWithLifecycle()
    val latestGlassesQuaternion by viewModel.latestGlassesQuaternion.collectAsStateWithLifecycle()
    val imuStreamingEnabled by viewModel.imuStreamingEnabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("IMU Wristband", color = Constants.primaryTextColor, style = MaterialTheme.typography.titleSmall)
        if (latestImuSamples.isEmpty()) {
            Text("Sem dados IMU recentes.", color = Constants.secondaryTextColor, fontSize = 12.sp)
        } else {
            val s = latestImuSamples.last()
            val pAvg = if (s.pressure.isNotEmpty()) s.pressure.map { it.toInt() }.average() else 0.0
            Text(
                text = "amostras=${latestImuSamples.size}  ax=${s.ax} ay=${s.ay} az=${s.az}  gx=${s.gx} gy=${s.gy} gz=${s.gz}  pAvg=${"%.1f".format(pAvg)}",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Constants.cardBackground)
                    .padding(10.dp),
            )
        }

        HeadTrackingCard(
            latestGlassesImu = latestGlassesImu,
            latestGlassesQuaternion = latestGlassesQuaternion,
            imuStreamingEnabled = imuStreamingEnabled,
            onToggleImu = { viewModel.setImuStreaming(it) },
            gzIntegralDeg = viewModel.latestGyIntegralDeg.collectAsState().value,
            gzSpreadDeg = viewModel.latestGySpreadDeg.collectAsState().value,
        )
    }
}

@Composable
private fun LogsTab(viewModel: AppViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UseCasesSection(viewModel)
        SafetyLogSection(viewModel)
    }
}

@Composable
private fun SafetyLogSection(viewModel: AppViewModel) {
    val safetyLog by viewModel.safetyLog.collectAsStateWithLifecycle()

    Text("Registo de segurança", color = Constants.primaryTextColor, style = MaterialTheme.typography.titleMedium)
    Text(
        "Últimas ${safetyLog.size} entradas (máximo 50).",
        color = Constants.secondaryTextColor,
        fontSize = 11.sp,
    )

    if (safetyLog.isEmpty()) {
        Text(
            "Sem entradas no registo de segurança.",
            color = Constants.secondaryTextColor,
            fontSize = 11.sp,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF0F1117))
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = safetyLog.joinToString("\n"),
                color = androidx.compose.ui.graphics.Color(0xFF00E676),
                fontSize = 10.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun UseCasesSection(viewModel: AppViewModel) {
    val uc1 by viewModel.safetyUc1Enabled.collectAsStateWithLifecycle()
    val uc1_2 by viewModel.safetyUc1_2Enabled.collectAsStateWithLifecycle()
    val uc1_3 by viewModel.safetyUc3Enabled.collectAsStateWithLifecycle()
    val uc1_4 by viewModel.safetyUc1_4Enabled.collectAsStateWithLifecycle()
    val uc1_4Strict by viewModel.safetyUc1_4StrictEnabled.collectAsStateWithLifecycle()
    val uc2 by viewModel.safetyUc2Enabled.collectAsStateWithLifecycle()
    val uc3Incoming by viewModel.safetyUc3IncomingEnabled.collectAsStateWithLifecycle()
    val uc4_3 by viewModel.safetyUc4_3Enabled.collectAsStateWithLifecycle()
    val uc4_5 by viewModel.safetyUc4_5Enabled.collectAsStateWithLifecycle()

    Text("Use Cases (Teste)", color = Constants.primaryTextColor, style = MaterialTheme.typography.titleMedium)
    Text(
        "Ativa/desativa cada caso de uso de segurança para testes. Os efeitos correm em background (WearableService).",
        color = Constants.secondaryTextColor,
        fontSize = 11.sp,
    )

    UseCaseToggleRow("UC1.1 – Pedestre distraído", uc1) { viewModel.safetySetUc1(it) }
    UseCaseToggleRow("UC1.2 – Veículo a aproximar (YOLO+Depth)", uc1_2) { viewModel.safetySetUc1_2(it) }
    UseCaseToggleRow("UC1.3 – Crossing vocal", uc1_3) { viewModel.safetySetUc3(it) }
    UseCaseToggleRow("UC1.4 – Alerta ciclista", uc1_4) { viewModel.safetySetUc1_4(it) }
    UseCaseToggleRow("UC1.4 estrito – Alerta ciclista proporcional", uc1_4Strict) { viewModel.safetySetUc1_4Strict(it) }
    UseCaseToggleRow("UC2 – Envio ATCLL (VAM/DENM/stream)", uc2) { viewModel.safetySetUc2(it) }
    UseCaseToggleRow("UC3 – Alertas recebidos (ATCLL)", uc3Incoming) { viewModel.safetySetUc3Incoming(it) }
    UseCaseToggleRow("UC4.3 – Sons ambientes → vibração", uc4_3) { viewModel.safetySetUc4_3(it) }
    UseCaseToggleRow("UC4.5 – Transcrição em tempo real", uc4_5) { viewModel.safetySetUc4_5(it) }
}

@Composable
private fun UseCaseToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Constants.cardBackground)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = Constants.primaryTextColor,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Constants.accentColor),
        )
    }
}
