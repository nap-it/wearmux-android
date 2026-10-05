package com.wearmux.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wearmux.android.AppViewModel
import com.wearmux.android.Constants
import com.wearmux.android.integration.adapters.BleDeviceState
import com.wearmux.android.integration.modules.wearos.WatchClient

/**
 * Full-screen detail view for a single device. Normal mode shows only
 * battery, a plain-language status, and Disconnect/Forget actions.
 * Developer Mode additionally reveals a "Technical details" block (signal,
 * MAC address, firmware, frame counts, transport) between the status cards
 * and the action buttons — it never replaces the simple view, only adds to it.
 */
@Composable
fun DeviceDetailScreen(
    viewModel: AppViewModel,
    deviceId: DeviceId,
    onBack: () -> Unit,
    onOpenStatus: (DeviceId) -> Unit,
) {
    val isDeveloperMode by viewModel.isDeveloperMode.collectAsStateWithLifecycle()
    var showConnectionDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Constants.secondaryTextColor,
                )
            }
            Text("Back", color = Constants.secondaryTextColor, fontSize = 14.sp)
        }

        DeviceHeader(deviceId)

        val (batteryText, statusText, statusColor) = deviceSummary(viewModel, deviceId)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryCard(label = "Battery", value = batteryText, modifier = Modifier.weight(1f))
            SummaryCard(
                label = "Status",
                value = statusText,
                valueColor = statusColor,
                modifier = Modifier.weight(1f),
            )
        }

        if (isDeveloperMode) {
            TechnicalDetailsBlock(
                viewModel,
                deviceId,
                onOpenConnectionDialog = { showConnectionDialog = true },
                onOpenStatus = { onOpenStatus(deviceId) },
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        DeviceActions(viewModel, deviceId)
    }

    if (showConnectionDialog) {
        deviceId.toConnectableDevice()?.let { connectable ->
            DeviceConnectionDialog(
                viewModel = viewModel,
                device = connectable,
                onDismiss = { showConnectionDialog = false },
            )
        }
    }
}

@Composable
private fun DeviceHeader(deviceId: DeviceId) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = deviceId.icon(),
            contentDescription = null,
            tint = Constants.primaryTextColor,
            modifier = Modifier.height(56.dp),
        )
        Text(
            text = deviceId.displayName(),
            color = Constants.primaryTextColor,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = Constants.primaryTextColor,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Constants.cardBackground)
            .border(BorderStroke(1.dp, Constants.cardBorderColor), RoundedCornerShape(16.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Constants.secondaryTextColor, fontSize = 12.sp)
    }
}

@Composable
private fun TechnicalDetailsBlock(
    viewModel: AppViewModel,
    deviceId: DeviceId,
    onOpenConnectionDialog: () -> Unit,
    onOpenStatus: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("technical_details_block")
            .clip(RoundedCornerShape(14.dp))
            .background(Constants.cardBackground)
            .border(BorderStroke(1.dp, Constants.cardBorderColor), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "TECHNICAL DETAILS",
            color = Constants.accentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        )
        technicalDetailRows(viewModel, deviceId).forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, color = Constants.secondaryTextColor, fontSize = 12.sp)
                Text(value, color = Constants.primaryTextColor, fontSize = 12.sp)
            }
        }
        if (deviceId.toConnectableDevice() != null) {
            OutlinedButton(onClick = onOpenConnectionDialog, modifier = Modifier.fillMaxWidth()) {
                Text("Connection settings…", fontSize = 12.sp)
            }
        }
        OutlinedButton(onClick = onOpenStatus, modifier = Modifier.fillMaxWidth()) {
            Text("Live status (camera, IMU, GPS, log)", fontSize = 12.sp)
        }
    }
}

@Composable
private fun DeviceActions(viewModel: AppViewModel, deviceId: DeviceId) {
    var showForgetConfirm by remember { mutableStateOf(false) }
    val isDeveloperMode by viewModel.isDeveloperMode.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (deviceId == DeviceId.PHONE) {
            PhoneSensorsAction(viewModel, isDeveloperMode)
        } else {
            Button(
                onClick = { disconnect(viewModel, deviceId) },
                colors = ButtonDefaults.buttonColors(containerColor = Constants.neutralButton),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disconnect", color = Constants.primaryTextColor, fontSize = 16.sp) }

            OutlinedButton(
                onClick = { showForgetConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Constants.errorColor),
            ) { Text("Forget device", fontSize = 16.sp) }
        }
    }

    if (showForgetConfirm) {
        AlertDialog(
            onDismissRequest = { showForgetConfirm = false },
            title = { Text("Forget device") },
            text = { Text("This disconnects the device. To reconnect, use the Connect Device screen again.") },
            confirmButton = {
                TextButton(onClick = {
                    disconnect(viewModel, deviceId)
                    showForgetConfirm = false
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showForgetConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The phone has no BLE connection to manage, so its only device-detail
 * action is starting/stopping its own sensors — and that's a developer
 * tool (it feeds the phone's local PDR/trajectory pipeline), so it only
 * appears with Developer Mode on.
 */
@Composable
private fun PhoneSensorsAction(viewModel: AppViewModel, isDeveloperMode: Boolean) {
    if (!isDeveloperMode) {
        Text(
            text = "Turn on Developer mode in Settings to test the phone's own sensors.",
            color = Constants.secondaryTextColor,
            fontSize = 12.sp,
        )
        return
    }

    val phoneSensorsActive by viewModel.phoneSensorsActive.collectAsStateWithLifecycle()
    Button(
        onClick = {
            if (phoneSensorsActive) viewModel.stopPhoneSensors() else viewModel.startPhoneSensors()
        },
        colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (phoneSensorsActive) "Stop sensors" else "Start sensors",
            color = androidx.compose.ui.graphics.Color.Black,
            fontSize = 16.sp,
        )
    }
}

private fun disconnect(viewModel: AppViewModel, deviceId: DeviceId) {
    when (deviceId) {
        DeviceId.GLASSES -> viewModel.disconnectGlasses()
        DeviceId.WRISTBAND -> viewModel.disconnectWristband()
        DeviceId.GALAXY_WATCH -> viewModel.watchStopImu()
        DeviceId.ESP32_CAM -> {} // No explicit disconnect action exists yet for ESP32.
        DeviceId.PHONE -> {}
    }
}

private fun DeviceId.icon() = when (this) {
    DeviceId.PHONE -> Icons.Filled.PhoneAndroid
    DeviceId.GLASSES -> Icons.Outlined.Visibility
    DeviceId.WRISTBAND -> Icons.AutoMirrored.Filled.DirectionsWalk
    DeviceId.GALAXY_WATCH -> Icons.Filled.Watch
    DeviceId.ESP32_CAM -> Icons.Outlined.Visibility
}

private fun DeviceId.displayName() = when (this) {
    DeviceId.PHONE -> "Phone"
    DeviceId.GLASSES -> "Glasses"
    DeviceId.WRISTBAND -> "Wristband"
    DeviceId.GALAXY_WATCH -> "Galaxy Watch"
    DeviceId.ESP32_CAM -> "ESP32 Camera"
}

private fun DeviceId.toConnectableDevice(): ConnectableDevice? = when (this) {
    DeviceId.GLASSES -> ConnectableDevice.GLASSES
    DeviceId.WRISTBAND -> ConnectableDevice.WRISTBAND
    DeviceId.GALAXY_WATCH -> ConnectableDevice.GALAXY_WATCH
    DeviceId.ESP32_CAM -> ConnectableDevice.ESP32_CAM
    DeviceId.PHONE -> null
}

private data class DeviceSummary(
    val batteryText: String,
    val statusText: String,
    val statusColor: androidx.compose.ui.graphics.Color,
)

@Composable
private fun deviceSummary(viewModel: AppViewModel, deviceId: DeviceId): DeviceSummary {
    return when (deviceId) {
        DeviceId.PHONE -> {
            val battery by viewModel.phoneBatteryPercent.collectAsStateWithLifecycle()
            val active by viewModel.phoneSensorsActive.collectAsStateWithLifecycle()
            DeviceSummary(
                batteryText = if (battery >= 0) "$battery%" else "—",
                statusText = if (active) "Connected" else "Idle",
                statusColor = if (active) Constants.successColor else Constants.idleColor,
            )
        }
        DeviceId.GLASSES -> {
            val state by viewModel.glassesState.collectAsStateWithLifecycle()
            val battery by viewModel.glassesBattery.collectAsStateWithLifecycle()
            DeviceSummary(
                batteryText = if (battery >= 0) "$battery%" else "—",
                statusText = bleStatusText(state),
                statusColor = bleStatusColor(state),
            )
        }
        DeviceId.WRISTBAND -> {
            val state by viewModel.wristbandState.collectAsStateWithLifecycle()
            val battery by viewModel.wristbandBattery.collectAsStateWithLifecycle()
            DeviceSummary(
                batteryText = if (battery >= 0) "$battery%" else "—",
                statusText = bleStatusText(state),
                statusColor = bleStatusColor(state),
            )
        }
        DeviceId.GALAXY_WATCH -> {
            val state by viewModel.watchState.collectAsStateWithLifecycle()
            val battery by viewModel.watchBattery.collectAsStateWithLifecycle()
            DeviceSummary(
                batteryText = if (battery >= 0) "$battery%" else "—",
                statusText = watchStatusText(state),
                statusColor = watchStatusColor(state),
            )
        }
        DeviceId.ESP32_CAM -> {
            val state by viewModel.esp32State.collectAsStateWithLifecycle()
            DeviceSummary(
                batteryText = "—",
                statusText = bleStatusText(state),
                statusColor = bleStatusColor(state),
            )
        }
    }
}

@Composable
private fun technicalDetailRows(viewModel: AppViewModel, deviceId: DeviceId): List<Pair<String, String>> {
    return when (deviceId) {
        DeviceId.GLASSES -> {
            val fw by viewModel.glassesFirmware.collectAsStateWithLifecycle()
            val ip by viewModel.glassesIp.collectAsStateWithLifecycle()
            val mode by viewModel.glassesConnectionMode.collectAsStateWithLifecycle()
            listOfNotNull(
                "Firmware" to fw.ifBlank { "—" },
                ip?.takeIf { it.isNotBlank() }?.let { "IP address" to it },
                "Transport" to mode.name,
            )
        }
        DeviceId.WRISTBAND -> {
            val activity by viewModel.wristbandActivity.collectAsStateWithLifecycle()
            listOf("Activity" to activity)
        }
        DeviceId.GALAXY_WATCH -> {
            val rate by viewModel.watchSampleRateHz.collectAsStateWithLifecycle()
            val sps by viewModel.watchSamplesPerSec.collectAsStateWithLifecycle()
            listOf("Sample rate" to "$rate Hz", "Samples/s" to "$sps")
        }
        DeviceId.ESP32_CAM, DeviceId.PHONE -> emptyList()
    }
}

private fun bleStatusText(state: BleDeviceState): String = when (state) {
    BleDeviceState.CONNECTED, BleDeviceState.READY -> "Connected"
    BleDeviceState.CONNECTING -> "Connecting…"
    BleDeviceState.DISCOVERING -> "Discovering…"
    BleDeviceState.CONFIGURING -> "Configuring…"
    BleDeviceState.ERROR -> "Error"
    BleDeviceState.DISCONNECTED -> "Disconnected"
}

private fun bleStatusColor(state: BleDeviceState): androidx.compose.ui.graphics.Color = when (state) {
    BleDeviceState.CONNECTED, BleDeviceState.READY -> Constants.successColor
    BleDeviceState.CONNECTING, BleDeviceState.DISCOVERING, BleDeviceState.CONFIGURING -> Constants.warningColor
    BleDeviceState.ERROR -> Constants.errorColor
    BleDeviceState.DISCONNECTED -> Constants.idleColor
}

private fun watchStatusText(state: WatchClient.State): String = when (state) {
    WatchClient.State.STREAMING -> "Connected"
    WatchClient.State.AVAILABLE -> "Ready"
    WatchClient.State.REMOTE -> "Out of range"
    WatchClient.State.ERROR -> "Error"
    WatchClient.State.DISCONNECTED -> "Disconnected"
}

private fun watchStatusColor(state: WatchClient.State): androidx.compose.ui.graphics.Color = when (state) {
    WatchClient.State.STREAMING -> Constants.successColor
    WatchClient.State.AVAILABLE -> Constants.accentColor
    WatchClient.State.REMOTE -> Constants.warningColor
    WatchClient.State.ERROR -> Constants.errorColor
    WatchClient.State.DISCONNECTED -> Constants.idleColor
}
