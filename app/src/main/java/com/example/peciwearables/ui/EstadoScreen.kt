package com.example.peciwearables.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.peciwearables.AppViewModel
import com.example.peciwearables.Constants
import com.example.peciwearables.integration.GlassesConnectionMode
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.image.camera.CameraStreamStatus
import com.example.peciwearables.integration.watch.WatchClient

/**
 * Aba "Estado": resumo de saúde do sistema — stream de câmara, microfone, IMU,
 * ligação e último alerta de segurança. Igual em modo Normal e Dev; não é uma
 * ferramenta técnica (isso fica em Dev/Laboratório), é um dashboard de leitura.
 */
@Composable
fun EstadoScreen(viewModel: AppViewModel) {
    val cameraStreamHealth by viewModel.cameraStreamHealth.collectAsStateWithLifecycle()
    val glassesMicStreaming by viewModel.glassesMicStreaming.collectAsStateWithLifecycle()
    val glassesMicStatusText by viewModel.glassesMicStatusText.collectAsStateWithLifecycle()
    val imuStreamingEnabled by viewModel.imuStreamingEnabled.collectAsStateWithLifecycle()
    val glassesConnectionMode by viewModel.glassesConnectionMode.collectAsStateWithLifecycle()
    val udpActive by viewModel.udpActive.collectAsStateWithLifecycle()
    val glassesState by viewModel.glassesState.collectAsStateWithLifecycle()
    val wristbandState by viewModel.wristbandState.collectAsStateWithLifecycle()
    val watchState by viewModel.watchState.collectAsStateWithLifecycle()
    val glassesBattery by viewModel.glassesBattery.collectAsStateWithLifecycle()
    val wristbandBattery by viewModel.wristbandBattery.collectAsStateWithLifecycle()
    val watchBattery by viewModel.watchBattery.collectAsStateWithLifecycle()
    val safetyLog by viewModel.safetyLog.collectAsStateWithLifecycle()

    val systemReady = cameraStreamHealth.status != CameraStreamStatus.LOST &&
        glassesState != BleDeviceState.ERROR &&
        wristbandState != BleDeviceState.ERROR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Estado do sistema",
            color = Constants.primaryTextColor,
            style = MaterialTheme.typography.titleLarge,
        )

        SystemReadyCard(systemReady, glassesState)

        GlassesStatusCard(
            glassesState = glassesState,
            cameraStreamStatus = cameraStreamHealth.status,
            framesReceived = cameraStreamHealth.framesReceived,
            fps = cameraStreamHealth.fps,
            lastFrameAgeMs = cameraStreamHealth.lastFrameAgeMs,
            micStreaming = glassesMicStreaming,
            micStatusText = glassesMicStatusText,
            imuStreamingEnabled = imuStreamingEnabled,
            connectionMode = glassesConnectionMode,
            udpActive = udpActive,
        )

        SimpleDeviceStatusCard(
            title = "Relógio – Galaxy Watch",
            statusLabel = watchStatusLabel(watchState),
            statusColor = watchStatusColor(watchState),
            battery = watchBattery,
        )

        SimpleDeviceStatusCard(
            title = "Pulseira",
            statusLabel = bleStatusLabel(wristbandState),
            statusColor = bleStatusColor(wristbandState),
            battery = wristbandBattery,
        )

        LastAlertCard(safetyLog)
    }
}

@Composable
private fun SystemReadyCard(ready: Boolean, glassesState: BleDeviceState) {
    val (label, subtitle, color) = if (ready) {
        Triple(
            "Sistema pronto",
            "Todos os sensores principais estão ativos e a transmitir dados.",
            Constants.successColor,
        )
    } else {
        Triple(
            "A verificar sensores",
            "Alguns sensores não estão ativos ou a stream está instável.",
            Constants.warningColor,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Constants.cardBackground)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = color, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Constants.secondaryTextColor, fontSize = 12.sp)
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun GlassesStatusCard(
    glassesState: BleDeviceState,
    cameraStreamStatus: CameraStreamStatus,
    framesReceived: Long,
    fps: Float,
    lastFrameAgeMs: Long?,
    micStreaming: Boolean,
    micStatusText: String,
    imuStreamingEnabled: Boolean,
    connectionMode: GlassesConnectionMode,
    udpActive: Boolean,
) {
    val ageLabel = lastFrameAgeMs?.let { age ->
        if (age < 1000) "há ${age} ms" else "há ${"%.1f".format(age / 1000f)} s"
    } ?: "sem frames ainda"
    val (cameraLabel, cameraColor) = when (cameraStreamStatus) {
        CameraStreamStatus.GOOD -> "Boa ligação" to Constants.successColor
        CameraStreamStatus.UNSTABLE -> "Instável" to Constants.warningColor
        CameraStreamStatus.LOST -> "Sem frames" to Constants.errorColor
        CameraStreamStatus.IDLE -> "Stream inativo" to Constants.secondaryTextColor
        CameraStreamStatus.DISCONNECTED -> "Óculos desligados" to Constants.secondaryTextColor
    }
    val connectionLabel = if (connectionMode == GlassesConnectionMode.WIFI) {
        if (udpActive) "Wi-Fi · Estável" else "Wi-Fi · UDP inativo"
    } else {
        "BLE · " + bleStatusLabel(glassesState)
    }
    val connectionColor = if (connectionMode == GlassesConnectionMode.WIFI && !udpActive) {
        Constants.warningColor
    } else {
        bleStatusColor(glassesState)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Constants.cardBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Óculos", color = Constants.primaryTextColor, style = MaterialTheme.typography.titleMedium)

        StatusRow(
            label = "Câmara (vídeo)",
            value = "${"%.1f".format(fps)} FPS · $framesReceived frames · $ageLabel",
            statusLabel = cameraLabel,
            statusColor = cameraColor,
        )
        StatusRow(
            label = "Microfone",
            value = micStatusText.ifBlank { "Sem dados" },
            statusLabel = if (micStreaming) "Ativo" else "Inativo",
            statusColor = if (micStreaming) Constants.successColor else Constants.secondaryTextColor,
        )
        StatusRow(
            label = "IMU (movimento)",
            value = "Acelerómetro + Giroscópio",
            statusLabel = if (imuStreamingEnabled) "Ativo" else "Inativo",
            statusColor = if (imuStreamingEnabled) Constants.successColor else Constants.secondaryTextColor,
        )
        StatusRow(
            label = "Ligação",
            value = connectionLabel,
            statusLabel = if (connectionColor == Constants.successColor) "Estável" else "A verificar",
            statusColor = connectionColor,
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String, statusLabel: String, statusColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Constants.primaryTextColor, fontSize = 13.sp)
            Text(value, color = Constants.secondaryTextColor, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(Modifier.size(6.dp))
            Text(statusLabel, color = statusColor, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SimpleDeviceStatusCard(title: String, statusLabel: String, statusColor: Color, battery: Int) {
    val batteryStr = if (battery >= 0) "$battery%" else "—"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Constants.cardBackground)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Constants.primaryTextColor, fontSize = 15.sp)
            Text("Bateria: $batteryStr", color = Constants.secondaryTextColor, fontSize = 12.sp)
        }
        Text(statusLabel, color = statusColor, fontSize = 12.sp)
    }
}

@Composable
private fun LastAlertCard(safetyLog: List<String>) {
    val lastAlert = safetyLog.lastOrNull()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Constants.cardBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Último alerta", color = Constants.primaryTextColor, style = MaterialTheme.typography.titleMedium)
        Text(
            text = lastAlert ?: "Nenhum perigo detetado. Tudo seguro até ao momento.",
            color = if (lastAlert != null) Constants.warningColor else Constants.secondaryTextColor,
            fontSize = 12.sp,
        )
    }
}

// -------------------- helpers de status --------------------

private fun bleStatusLabel(state: BleDeviceState): String = when (state) {
    BleDeviceState.CONNECTED, BleDeviceState.READY -> "Estável"
    BleDeviceState.CONNECTING, BleDeviceState.DISCOVERING, BleDeviceState.CONFIGURING -> "A ligar"
    BleDeviceState.ERROR -> "Erro"
    BleDeviceState.DISCONNECTED -> "Desligado"
}

private fun bleStatusColor(state: BleDeviceState): Color = when (state) {
    BleDeviceState.CONNECTED, BleDeviceState.READY -> Constants.successColor
    BleDeviceState.CONNECTING, BleDeviceState.DISCOVERING, BleDeviceState.CONFIGURING -> Constants.warningColor
    BleDeviceState.ERROR -> Constants.errorColor
    BleDeviceState.DISCONNECTED -> Constants.idleColor
}

private fun watchStatusLabel(state: WatchClient.State): String = when (state) {
    WatchClient.State.STREAMING -> "Estável"
    WatchClient.State.AVAILABLE -> "Pronto"
    WatchClient.State.REMOTE -> "Longe (cloud)"
    WatchClient.State.ERROR -> "Erro"
    WatchClient.State.DISCONNECTED -> "Desligado"
}

private fun watchStatusColor(state: WatchClient.State): Color = when (state) {
    WatchClient.State.STREAMING -> Constants.successColor
    WatchClient.State.AVAILABLE -> Constants.accentColor
    WatchClient.State.REMOTE -> Constants.warningColor
    WatchClient.State.ERROR -> Constants.errorColor
    WatchClient.State.DISCONNECTED -> Constants.idleColor
}
