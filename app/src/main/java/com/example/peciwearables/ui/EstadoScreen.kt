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
import com.example.peciwearables.integration.adapters.BleDeviceState
import com.example.peciwearables.integration.modules.camera.CameraStreamStatus
import com.example.peciwearables.integration.modules.wearos.WatchClient

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
            text = "System status",
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
            title = "Watch – Galaxy Watch",
            statusLabel = watchStatusLabel(watchState),
            statusColor = watchStatusColor(watchState),
            battery = watchBattery,
        )

        SimpleDeviceStatusCard(
            title = "Wristband",
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
            "System ready",
            "All main sensors are active and streaming data.",
            Constants.successColor,
        )
    } else {
        Triple(
            "Checking sensors",
            "Some sensors are not active or the stream is unstable.",
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
        if (age < 1000) "${age} ms ago" else "${"%.1f".format(age / 1000f)} s ago"
    } ?: "no frames yet"
    val (cameraLabel, cameraColor) = when (cameraStreamStatus) {
        CameraStreamStatus.GOOD -> "Good connection" to Constants.successColor
        CameraStreamStatus.UNSTABLE -> "Unstable" to Constants.warningColor
        CameraStreamStatus.LOST -> "No frames" to Constants.errorColor
        CameraStreamStatus.IDLE -> "Stream inactive" to Constants.secondaryTextColor
        CameraStreamStatus.DISCONNECTED -> "Glasses disconnected" to Constants.secondaryTextColor
    }
    val connectionLabel = if (connectionMode == GlassesConnectionMode.WIFI) {
        if (udpActive) "Wi-Fi · Stable" else "Wi-Fi · UDP inactive"
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
        Text("Glasses", color = Constants.primaryTextColor, style = MaterialTheme.typography.titleMedium)

        StatusRow(
            label = "Camera (video)",
            value = "${"%.1f".format(fps)} FPS · $framesReceived frames · $ageLabel",
            statusLabel = cameraLabel,
            statusColor = cameraColor,
        )
        StatusRow(
            label = "Microphone",
            value = micStatusText.ifBlank { "No data" },
            statusLabel = if (micStreaming) "Active" else "Inactive",
            statusColor = if (micStreaming) Constants.successColor else Constants.secondaryTextColor,
        )
        StatusRow(
            label = "IMU (motion)",
            value = "Accelerometer + Gyroscope",
            statusLabel = if (imuStreamingEnabled) "Active" else "Inactive",
            statusColor = if (imuStreamingEnabled) Constants.successColor else Constants.secondaryTextColor,
        )
        StatusRow(
            label = "Connection",
            value = connectionLabel,
            statusLabel = if (connectionColor == Constants.successColor) "Stable" else "Checking",
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
            Text("Battery: $batteryStr", color = Constants.secondaryTextColor, fontSize = 12.sp)
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
        Text("Last alert", color = Constants.primaryTextColor, style = MaterialTheme.typography.titleMedium)
        Text(
            text = lastAlert ?: "No danger detected. Everything is safe so far.",
            color = if (lastAlert != null) Constants.warningColor else Constants.secondaryTextColor,
            fontSize = 12.sp,
        )
    }
}

// -------------------- helpers de status --------------------

private fun bleStatusLabel(state: BleDeviceState): String = when (state) {
    BleDeviceState.CONNECTED, BleDeviceState.READY -> "Stable"
    BleDeviceState.CONNECTING, BleDeviceState.DISCOVERING, BleDeviceState.CONFIGURING -> "Connecting"
    BleDeviceState.ERROR -> "Error"
    BleDeviceState.DISCONNECTED -> "Disconnected"
}

private fun bleStatusColor(state: BleDeviceState): Color = when (state) {
    BleDeviceState.CONNECTED, BleDeviceState.READY -> Constants.successColor
    BleDeviceState.CONNECTING, BleDeviceState.DISCOVERING, BleDeviceState.CONFIGURING -> Constants.warningColor
    BleDeviceState.ERROR -> Constants.errorColor
    BleDeviceState.DISCONNECTED -> Constants.idleColor
}

private fun watchStatusLabel(state: WatchClient.State): String = when (state) {
    WatchClient.State.STREAMING -> "Stable"
    WatchClient.State.AVAILABLE -> "Ready"
    WatchClient.State.REMOTE -> "Out of range (cloud)"
    WatchClient.State.ERROR -> "Error"
    WatchClient.State.DISCONNECTED -> "Disconnected"
}

private fun watchStatusColor(state: WatchClient.State): Color = when (state) {
    WatchClient.State.STREAMING -> Constants.successColor
    WatchClient.State.AVAILABLE -> Constants.accentColor
    WatchClient.State.REMOTE -> Constants.warningColor
    WatchClient.State.ERROR -> Constants.errorColor
    WatchClient.State.DISCONNECTED -> Constants.idleColor
}
