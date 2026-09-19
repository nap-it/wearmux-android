package com.example.peciwearables.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.peciwearables.integration.adapters.BleDeviceState
import com.example.peciwearables.integration.modules.wearos.WatchClient

/** Identifies a device the home screen and detail screen both refer to. */
enum class DeviceId {
    PHONE, GLASSES, WRISTBAND, GALAXY_WATCH, ESP32_CAM
}

/**
 * The app's single entry point screen. Always the same route ("home"):
 * a giant "Connect Device" call to action when no external wearable is
 * connected, shrinking to a compact "+ Connect another device" strip with
 * the connected-devices list taking over below it once at least one is
 * connected. The phone is always the first item in that list.
 */
@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onOpenDevice: (DeviceId) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val hasConnectedWearable by viewModel.hasConnectedWearable.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val candidateScanRunning by viewModel.candidateScanRunning.collectAsStateWithLifecycle()

        HomeHeader(onOpenSettings = onOpenSettings)

        if (hasConnectedWearable) {
            ConnectAnotherDeviceStrip(
                scanning = candidateScanRunning,
                onClick = {
                    if (candidateScanRunning) viewModel.stopDiscoverBleCandidates()
                    else viewModel.discoverBleCandidates()
                },
            )
        } else {
            GiantConnectButton(
                scanning = candidateScanRunning,
                onClick = {
                    if (candidateScanRunning) viewModel.stopDiscoverBleCandidates()
                    else viewModel.discoverBleCandidates()
                },
            )
        }

        CandidateScanSection(viewModel)

        Text(
            text = "Connected devices",
            color = Constants.primaryTextColor,
            fontSize = 15.sp,
        )

        PhoneDeviceCard(viewModel, onOpenDevice)
        WatchDeviceCard(viewModel, onOpenDevice)
        GlassesDeviceCard(viewModel, onOpenDevice)
        WristbandDeviceCard(viewModel, onOpenDevice)
        Esp32DeviceCard(viewModel, onOpenDevice)
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "WearMux",
            color = Constants.primaryTextColor,
            fontSize = 22.sp,
            style = MaterialTheme.typography.titleLarge,
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = Constants.secondaryTextColor,
            )
        }
    }
}

@Composable
private fun GiantConnectButton(scanning: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.1f)
            .clip(RoundedCornerShape(28.dp))
            .background(Constants.accentColor)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Link,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = if (scanning) "Searching…" else "Search Wearables",
            color = Color.White,
            fontSize = 26.sp,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun ConnectAnotherDeviceStrip(scanning: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Constants.cardBackgroundElevated)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Link,
            contentDescription = null,
            tint = Constants.accentColor,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = if (scanning) "Searching…" else "Search another device",
            color = Constants.accentColor,
            fontSize = 16.sp,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun CandidateScanSection(viewModel: AppViewModel) {
    val bleCandidates by viewModel.bleCandidates.collectAsStateWithLifecycle()
    val candidateScanRunning by viewModel.candidateScanRunning.collectAsStateWithLifecycle()

    // The giant Connect Device button/strip is the only way to start or
    // stop a scan — this section only ever shows the results, so it stays
    // hidden until there's something to show.
    if (!candidateScanRunning && bleCandidates.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (candidateScanRunning && bleCandidates.isEmpty()) {
            Text(
                text = "Searching for nearby devices…",
                color = Constants.secondaryTextColor,
                fontSize = 13.sp,
            )
        }

        bleCandidates.forEach { candidate ->
            BleCandidateRow(
                candidate = candidate,
                onConnect = { viewModel.connectCandidate(candidate.address) },
            )
        }
    }
}

@Composable
private fun PhoneDeviceCard(viewModel: AppViewModel, onOpenDevice: (DeviceId) -> Unit) {
    val phoneSensorsActive by viewModel.phoneSensorsActive.collectAsStateWithLifecycle()
    val pdrStepCount by viewModel.pdrStepCount.collectAsStateWithLifecycle()
    val batteryPercent by viewModel.phoneBatteryPercent.collectAsStateWithLifecycle()

    val batterySuffix = if (batteryPercent >= 0) " · battery $batteryPercent%" else ""
    DeviceCard(
        icon = Icons.Filled.PhoneAndroid,
        title = "Phone",
        subtitle = (if (phoneSensorsActive) "Sensors active · $pdrStepCount steps" else "Sensors idle") + batterySuffix,
        statusColor = if (phoneSensorsActive) Constants.successColor else Constants.idleColor,
        onClick = { onOpenDevice(DeviceId.PHONE) },
    )
}

@Composable
private fun WatchDeviceCard(viewModel: AppViewModel, onOpenDevice: (DeviceId) -> Unit) {
    val watchState by viewModel.watchState.collectAsStateWithLifecycle()
    val watchName by viewModel.watchName.collectAsStateWithLifecycle()
    val watchBattery by viewModel.watchBattery.collectAsStateWithLifecycle()
    val watchSps by viewModel.watchSamplesPerSec.collectAsStateWithLifecycle()
    val watchRate by viewModel.watchSampleRateHz.collectAsStateWithLifecycle()
    val watchBpm by viewModel.watchHeartRateBpm.collectAsStateWithLifecycle()

    if (!WearableVisibilityHelpers.isVisible(watchState)) return

    DeviceCard(
        icon = Icons.Filled.Watch,
        title = watchName ?: "Galaxy Watch",
        subtitle = describeWatch(watchState, watchBattery, watchRate, watchSps, watchBpm),
        statusColor = colorFor(watchState),
        onClick = { onOpenDevice(DeviceId.GALAXY_WATCH) },
    )
}

@Composable
private fun GlassesDeviceCard(viewModel: AppViewModel, onOpenDevice: (DeviceId) -> Unit) {
    val glassesState by viewModel.glassesState.collectAsStateWithLifecycle()
    val glassesBattery by viewModel.glassesBattery.collectAsStateWithLifecycle()
    val glassesFw by viewModel.glassesFirmware.collectAsStateWithLifecycle()
    val glassesIp by viewModel.glassesIp.collectAsStateWithLifecycle()

    if (!WearableVisibilityHelpers.isVisible(glassesState)) return

    DeviceCard(
        icon = Icons.Outlined.Visibility,
        title = "Glasses",
        subtitle = describeGlasses(glassesState, glassesBattery, glassesIp, glassesFw),
        statusColor = colorForBle(glassesState),
        onClick = { onOpenDevice(DeviceId.GLASSES) },
    )
}

@Composable
private fun WristbandDeviceCard(viewModel: AppViewModel, onOpenDevice: (DeviceId) -> Unit) {
    val wristbandState by viewModel.wristbandState.collectAsStateWithLifecycle()
    val wristbandBattery by viewModel.wristbandBattery.collectAsStateWithLifecycle()
    val wristbandActivity by viewModel.wristbandActivity.collectAsStateWithLifecycle()

    if (!WearableVisibilityHelpers.isVisible(wristbandState)) return

    DeviceCard(
        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
        title = "Wristband",
        subtitle = describeSole(wristbandState, wristbandBattery, wristbandActivity),
        statusColor = colorForBle(wristbandState),
        onClick = { onOpenDevice(DeviceId.WRISTBAND) },
    )
}

@Composable
private fun Esp32DeviceCard(viewModel: AppViewModel, onOpenDevice: (DeviceId) -> Unit) {
    val esp32State by viewModel.esp32State.collectAsStateWithLifecycle()
    if (!WearableVisibilityHelpers.isVisible(esp32State)) return

    DeviceCard(
        icon = Icons.Outlined.Visibility,
        title = "ESP32 Camera",
        subtitle = if (esp32State == BleDeviceState.CONNECTED) "Connected via Wi-Fi" else "Manual",
        statusColor = colorForBle(esp32State),
        onClick = { onOpenDevice(DeviceId.ESP32_CAM) },
    )
}

/**
 * Big, simple row for a single connected device on the home screen. Tapping
 * anywhere on the card opens that device's full detail screen — no inline
 * actions or gear icon here, unlike the old CleanInfoScreen cards.
 */
@Composable
private fun DeviceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    statusColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Constants.cardBackground)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            Text(title, color = Constants.primaryTextColor, fontSize = 16.sp)
            Text(subtitle, color = Constants.secondaryTextColor, fontSize = 12.sp)
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor),
        )
    }
}

// -------------------- status helpers (moved from CleanInfoScreen.kt) --------------------

private object WearableVisibilityHelpers {
    fun isVisible(state: WatchClient.State) = com.example.peciwearables.WearableVisibility.isVisible(state)
    fun isVisible(state: BleDeviceState) = com.example.peciwearables.WearableVisibility.isVisible(state)
}

private fun colorFor(state: WatchClient.State): Color = when (state) {
    WatchClient.State.STREAMING -> Constants.successColor
    WatchClient.State.AVAILABLE -> Constants.accentColor
    WatchClient.State.REMOTE -> Constants.warningColor
    WatchClient.State.ERROR -> Constants.errorColor
    WatchClient.State.DISCONNECTED -> Constants.idleColor
}

private fun colorForBle(state: BleDeviceState): Color = when (state) {
    BleDeviceState.CONNECTED, BleDeviceState.READY -> Constants.successColor
    BleDeviceState.CONNECTING, BleDeviceState.DISCOVERING, BleDeviceState.CONFIGURING -> Constants.warningColor
    BleDeviceState.ERROR -> Constants.errorColor
    BleDeviceState.DISCONNECTED -> Constants.idleColor
}

private fun describeWatch(state: WatchClient.State, battery: Int, rate: Int, sps: Int, bpm: Int): String {
    val batteryStr = if (battery >= 0) "$battery%" else "—"
    val bpmStr = if (bpm > 0) {
        val tag = if (bpm >= 95) "🚶" else "❤"
        " · $tag ${bpm} bpm"
    } else ""
    return when (state) {
        WatchClient.State.STREAMING -> "Streaming · $sps/s @ ${rate}Hz · ${batteryStr}$bpmStr"
        WatchClient.State.AVAILABLE -> "Ready · battery ${batteryStr}$bpmStr"
        WatchClient.State.REMOTE -> "Out of range (cloud) · battery ${batteryStr}$bpmStr"
        WatchClient.State.ERROR -> "Communication error"
        WatchClient.State.DISCONNECTED -> "No connection"
    }
}

private fun describeGlasses(state: BleDeviceState, battery: Int, ip: String?, fw: String): String {
    val batteryStr = if (battery >= 0) "$battery%" else "—"
    val core = when (state) {
        BleDeviceState.CONNECTED, BleDeviceState.READY -> "Connected · battery $batteryStr"
        BleDeviceState.CONNECTING -> "Connecting..."
        BleDeviceState.DISCOVERING -> "Discovering services..."
        BleDeviceState.CONFIGURING -> "Configuring..."
        BleDeviceState.ERROR -> "BLE error"
        BleDeviceState.DISCONNECTED -> "Disconnected"
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
        BleDeviceState.CONNECTED, BleDeviceState.READY -> "Connected · battery $batteryStr · $activity"
        BleDeviceState.CONNECTING -> "Connecting..."
        BleDeviceState.DISCOVERING -> "Discovering services..."
        BleDeviceState.CONFIGURING -> "Configuring..."
        BleDeviceState.ERROR -> "BLE error"
        BleDeviceState.DISCONNECTED -> "Disconnected"
    }
}
