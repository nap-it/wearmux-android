package com.example.peciwearables.integration.wearable

import com.example.peciwearables.integration.BleConnectionCandidate
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.ble.CapabilityDiscoveryScanner
import com.example.peciwearables.integration.ble.CapabilityScanCandidate
import com.example.peciwearables.integration.ble.WearableKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordena descoberta + ligação BLE de wearables (Omi/Sole/ESP32). Substitui o
 * spaghetti de `startCandidateDiscovery` / `connectCandidate` /
 * `startAutoConnectFlow` / `startFilteredConnect` que estava no WearableService.
 */
class ScanAndConnectCoordinator(
    private val scope: CoroutineScope,
    private val hub: DefaultWearableHub,
    private val scanner: CapabilityDiscoveryScanner,
    private val glassesState: () -> BleDeviceState,
    private val wristbandState: () -> BleDeviceState,
    private val appendLog: (String) -> Unit,
) {
    private val _candidates = MutableStateFlow<List<BleConnectionCandidate>>(emptyList())
    val candidates: StateFlow<List<BleConnectionCandidate>> = _candidates.asStateFlow()
    private val _scanRunning = MutableStateFlow(false)
    val scanRunning: StateFlow<Boolean> = _scanRunning.asStateFlow()

    private val byAddress = LinkedHashMap<String, CapabilityScanCandidate>()
    @Volatile private var autoConnectInProgress = false

    fun startDiscovery() {
        if (_scanRunning.value) { appendLog("Candidate discovery already in progress"); return }
        cancelAutoConnect()
        byAddress.clear(); _candidates.value = emptyList()
        _scanRunning.value = true
        appendLog("Searching for BLE candidates for manual selection...")
        scanner.startScan(
            onCandidate = { c ->
                byAddress[c.address] = c
                _candidates.value = byAddress.values.sortedByDescending { it.rssi }.map { asUi(it) }
                false
            },
            onFinished = { foundAny ->
                _scanRunning.value = false
                appendLog(if (foundAny) "Discovery complete: ${_candidates.value.size} candidate(s)" else "No BLE candidate found")
            },
        )
    }

    fun stopDiscovery(reason: String? = null) {
        if (!_scanRunning.value) return
        scanner.stopScan(); _scanRunning.value = false
        if (!reason.isNullOrBlank()) appendLog(reason)
    }

    fun connectByAddress(address: String) {
        val candidate = byAddress[address]
        if (candidate == null) { appendLog("Candidate not found ($address)."); return }
        stopDiscovery("Candidate scan stopped to connect")
        connectCandidate(candidate)
    }

    fun startFilteredConnect(kind: WearableKind, label: String) {
        cancelAutoConnect(); stopDiscovery()
        appendLog("Searching for $label via WearableHub...")
        scanner.startScan(
            onCandidate = { c ->
                if (c.kind == kind) connectCandidate(c)
                else if (c.kind == WearableKind.UNKNOWN) connectUnknown(c, preferredKind = kind)
                else false
            },
            onFinished = { matched -> if (!matched) appendLog("No $label found") },
        )
    }

    fun startAutoConnect() {
        if (autoConnectInProgress) { appendLog("Auto connect already in progress"); return }
        stopDiscovery()
        val wantGlasses = !isGlassesBusy(); val wantSole = !isWristbandBusy()
        if (!wantGlasses && !wantSole) { appendLog("Auto connect skipped: all slots busy"); return }
        autoConnectInProgress = true
        appendLog("Auto connect: searching for a compatible wearable...")
        scanner.startScan(
            onCandidate = { c -> handleAuto(c, wantGlasses, wantSole) },
            onFinished = { matched ->
                autoConnectInProgress = false
                if (!matched) appendLog("Auto connect: no wearable found")
            },
        )
    }

    fun cancelAutoConnect() {
        if (!autoConnectInProgress) return
        scanner.stopScan(); autoConnectInProgress = false
        appendLog("Auto connect cancelled (manual action)")
    }

    private fun connectCandidate(c: CapabilityScanCandidate): Boolean = when (c.kind) {
        WearableKind.GLASSES -> {
            if (isGlassesBusy()) { appendLog("Omi slot busy"); false }
            else { appendLog("Connecting ${c.name} as GLASSES"); via(c); true }
        }
        WearableKind.WRIST_OR_SOLE -> {
            if (isWristbandBusy()) { appendLog("Wristband slot busy"); false }
            else { appendLog("Connecting ${c.name} as WRIST/SOLE"); via(c); true }
        }
        WearableKind.UNKNOWN -> connectUnknown(c, preferredKind = null)
    }

    private fun connectUnknown(c: CapabilityScanCandidate, preferredKind: WearableKind?): Boolean {
        val label = c.name.ifBlank { c.address }
        return when (preferredKind) {
            WearableKind.GLASSES -> if (isGlassesBusy()) { appendLog("$label: Omi slot busy"); false } else { via(c); true }
            WearableKind.WRIST_OR_SOLE -> if (isWristbandBusy()) { appendLog("$label: wristband slot busy"); false } else { via(c); true }
            null, WearableKind.UNKNOWN -> when {
                !isGlassesBusy() -> { appendLog("$label: fallback Omi"); via(c); true }
                !isWristbandBusy() -> { appendLog("$label: wristband fallback"); via(c); true }
                else -> { appendLog("$label: both slots busy"); false }
            }
        }
    }

    private fun handleAuto(c: CapabilityScanCandidate, wantGlasses: Boolean, wantSole: Boolean): Boolean = when (c.kind) {
        WearableKind.GLASSES -> if (!wantGlasses || isGlassesBusy()) false else connectCandidate(c)
        WearableKind.WRIST_OR_SOLE -> if (!wantSole || isWristbandBusy()) false else connectCandidate(c)
        WearableKind.UNKNOWN -> when {
            wantGlasses && !isGlassesBusy() -> connectUnknown(c, WearableKind.GLASSES)
            wantSole && !isWristbandBusy() -> connectUnknown(c, WearableKind.WRIST_OR_SOLE)
            else -> { appendLog("Auto-connect: unknown type ${c.name}, continuing"); false }
        }
    }

    private fun via(c: CapabilityScanCandidate) {
        scope.launch {
            val session = hub.connectByCandidate(c.toWearableScanCandidate())
            if (session == null) appendLog("Failed to confirm ${c.name} via WearableHub")
            else appendLog("WearableHub: active session ${session.adapterId} (${session.id.raw})")
        }
    }

    fun connectEsp32Cam(onStateChange: (BleDeviceState) -> Unit) {
        scope.launch {
            onStateChange(BleDeviceState.CONNECTING)
            val candidate = WearableScanCandidate(
                id = WearableId("esp32-cam-192.168.4.1"),
                displayName = "ESP32-CAM", rssi = 0,
                advertisedServiceUuids = emptySet(), manufacturerData = emptyMap(),
                serviceData = emptyMap(), raw = null,
            )
            appendLog("Connecting to ESP32-CAM (192.168.4.1)...")
            val session = hub.connectByCandidate(candidate)
            if (session == null) {
                appendLog("Failed to connect to ESP32-CAM. Check the 'ESP32-CAM' Wi-Fi.")
                onStateChange(BleDeviceState.DISCONNECTED)
            } else {
                appendLog("ESP32-CAM connected successfully!")
                onStateChange(BleDeviceState.READY)
            }
        }
    }

    private fun isGlassesBusy() = glassesState() != BleDeviceState.DISCONNECTED && glassesState() != BleDeviceState.ERROR
    private fun isWristbandBusy() = wristbandState() != BleDeviceState.DISCONNECTED && wristbandState() != BleDeviceState.ERROR

    private fun asUi(c: CapabilityScanCandidate) = BleConnectionCandidate(
        address = c.address, name = c.name, rssi = c.rssi, kind = c.kind,
        sdkDeviceType = c.sdkDeviceType?.name, ipAddress = c.ipAddress, isWifiSecure = c.isWifiSecure,
    )
}
