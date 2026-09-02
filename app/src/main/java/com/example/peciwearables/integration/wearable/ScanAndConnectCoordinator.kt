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
        if (_scanRunning.value) { appendLog("Descoberta de candidatos ja em curso"); return }
        cancelAutoConnect()
        byAddress.clear(); _candidates.value = emptyList()
        _scanRunning.value = true
        appendLog("A procurar candidatos BLE para escolha manual...")
        scanner.startScan(
            onCandidate = { c ->
                byAddress[c.address] = c
                _candidates.value = byAddress.values.sortedByDescending { it.rssi }.map { asUi(it) }
                false
            },
            onFinished = { foundAny ->
                _scanRunning.value = false
                appendLog(if (foundAny) "Descoberta concluida: ${_candidates.value.size} candidato(s)" else "Nenhum candidato BLE encontrado")
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
        if (candidate == null) { appendLog("Candidato nao encontrado ($address)."); return }
        stopDiscovery("Scan de candidatos parado para conectar")
        connectCandidate(candidate)
    }

    fun startFilteredConnect(kind: WearableKind, label: String) {
        cancelAutoConnect(); stopDiscovery()
        appendLog("A procurar $label via WearableHub...")
        scanner.startScan(
            onCandidate = { c ->
                if (c.kind == kind) connectCandidate(c)
                else if (c.kind == WearableKind.UNKNOWN) connectUnknown(c, preferredKind = kind)
                else false
            },
            onFinished = { matched -> if (!matched) appendLog("Nenhum $label encontrado") },
        )
    }

    fun startAutoConnect() {
        if (autoConnectInProgress) { appendLog("Ligacao auto ja em curso"); return }
        stopDiscovery()
        val wantGlasses = !isGlassesBusy(); val wantSole = !isWristbandBusy()
        if (!wantGlasses && !wantSole) { appendLog("Ligacao auto ignorada: tudo ocupado"); return }
        autoConnectInProgress = true
        appendLog("Ligacao auto: a procurar wearable compativel...")
        scanner.startScan(
            onCandidate = { c -> handleAuto(c, wantGlasses, wantSole) },
            onFinished = { matched ->
                autoConnectInProgress = false
                if (!matched) appendLog("Ligacao auto: nenhum wearable encontrado")
            },
        )
    }

    fun cancelAutoConnect() {
        if (!autoConnectInProgress) return
        scanner.stopScan(); autoConnectInProgress = false
        appendLog("Ligacao auto cancelada (acao manual)")
    }

    private fun connectCandidate(c: CapabilityScanCandidate): Boolean = when (c.kind) {
        WearableKind.GLASSES -> {
            if (isGlassesBusy()) { appendLog("Slot Omi ocupado"); false }
            else { appendLog("A ligar ${c.name} como GLASSES"); via(c); true }
        }
        WearableKind.WRIST_OR_SOLE -> {
            if (isWristbandBusy()) { appendLog("Slot pulseira ocupado"); false }
            else { appendLog("A ligar ${c.name} como WRIST/SOLE"); via(c); true }
        }
        WearableKind.UNKNOWN -> connectUnknown(c, preferredKind = null)
    }

    private fun connectUnknown(c: CapabilityScanCandidate, preferredKind: WearableKind?): Boolean {
        val label = c.name.ifBlank { c.address }
        return when (preferredKind) {
            WearableKind.GLASSES -> if (isGlassesBusy()) { appendLog("$label: slot Omi ocupado"); false } else { via(c); true }
            WearableKind.WRIST_OR_SOLE -> if (isWristbandBusy()) { appendLog("$label: slot pulseira ocupado"); false } else { via(c); true }
            null, WearableKind.UNKNOWN -> when {
                !isGlassesBusy() -> { appendLog("$label: fallback Omi"); via(c); true }
                !isWristbandBusy() -> { appendLog("$label: fallback pulseira"); via(c); true }
                else -> { appendLog("$label: ambos slots ocupados"); false }
            }
        }
    }

    private fun handleAuto(c: CapabilityScanCandidate, wantGlasses: Boolean, wantSole: Boolean): Boolean = when (c.kind) {
        WearableKind.GLASSES -> if (!wantGlasses || isGlassesBusy()) false else connectCandidate(c)
        WearableKind.WRIST_OR_SOLE -> if (!wantSole || isWristbandBusy()) false else connectCandidate(c)
        WearableKind.UNKNOWN -> when {
            wantGlasses && !isGlassesBusy() -> connectUnknown(c, WearableKind.GLASSES)
            wantSole && !isWristbandBusy() -> connectUnknown(c, WearableKind.WRIST_OR_SOLE)
            else -> { appendLog("Auto-connect: tipo desconhecido ${c.name}, a continuar"); false }
        }
    }

    private fun via(c: CapabilityScanCandidate) {
        scope.launch {
            val session = hub.connectByCandidate(c.toWearableScanCandidate())
            if (session == null) appendLog("Falha ao confirmar ${c.name} via WearableHub")
            else appendLog("WearableHub: sessao ativa ${session.adapterId} (${session.id.raw})")
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
            appendLog("A ligar a ESP32-CAM (192.168.4.1)...")
            val session = hub.connectByCandidate(candidate)
            if (session == null) {
                appendLog("Falha ao ligar a ESP32-CAM. Verifica Wi-Fi 'ESP32-CAM'.")
                onStateChange(BleDeviceState.DISCONNECTED)
            } else {
                appendLog("ESP32-CAM ligada com sucesso!")
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
