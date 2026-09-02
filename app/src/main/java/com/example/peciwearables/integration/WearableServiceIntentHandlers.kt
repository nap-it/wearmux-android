package com.example.peciwearables.integration

import android.content.Context
import android.content.Intent
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_ATCLL_ENDPOINT
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_CAMERA_QUALITY_FACTOR
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_CAMERA_RATE_MS
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_CAMERA_RESOLUTION
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_CANDIDATE_ADDRESS
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_DEPTH_URL
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_GLASSES_CONNECTION_MODE
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_GLASSES_INFERENCE_MODE
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_GLASSES_INFERENCE_URL
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_IMU_STREAMING_ENABLED
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_MICROPHONE_BIT_DEPTH
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_MICROPHONE_SAMPLE_RATE
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_ML_PROCESSING_LOCATION
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_ML_SERVER_URL
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_NAVISENS_IMU_SOURCE
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_NOTIFY_BODY
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_NOTIFY_TITLE
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_NOTIFY_TYPE
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_PASSWORD
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_SAFETY_ENABLED
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_SAFETY_ZONE_ID
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_SAFETY_ZONE_NAME
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_SAFETY_ZONE_RADIUS
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_SSID
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_TONE_DURATION_MS
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_TONE_FREQ_HZ
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_TONE_PRESET
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_UNIFIED_SERVER_URL
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_WATCH_NOTIFY_BODY
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_WATCH_NOTIFY_TITLE
import com.example.peciwearables.integration.audio.AudioTestEngine
import com.example.peciwearables.integration.audio.NotificationSounder
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.ble.GlassesMicrophoneProfile
import com.example.peciwearables.integration.ble.WearableKind
import com.example.peciwearables.integration.inference.InferenceMode
import com.example.peciwearables.integration.ml.PeciServerClassifier
import com.example.peciwearables.integration.safety.CrossingZone
import com.example.peciwearables.integration.wearable.WearableCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

private fun BleDeviceState.isOnlineBle() = this == BleDeviceState.READY || this == BleDeviceState.CONNECTED

internal fun WearableService.handleStartMicrophone() {
    if (!WearableService._glassesState.value.isOnlineBle()) { WearableService.appendLog("⚠  Microfone: Omi não está conectado (${WearableService._glassesState.value.name})"); return }
    val ageMs = if (glassesMicTransitionAtMs > 0L) System.currentTimeMillis() - glassesMicTransitionAtMs else Long.MAX_VALUE
    val stuck = WearableService._glassesMicState.value in setOf(WearableService.Companion.GlassesMicStreamState.STARTING, WearableService.Companion.GlassesMicStreamState.STOPPING) &&
        ageMs >= WearableService.Companion.GLASSES_MIC_TRANSITION_TIMEOUT_MS
    if (stuck) {
        WearableService.appendLog("🎤 Mic state preso em ${WearableService._glassesMicState.value} há ${ageMs}ms; a destrancar")
        WearableService._glassesMicState.value = WearableService.Companion.GlassesMicStreamState.IDLE; WearableService._glassesMicStreaming.value = false
    }
    if (WearableService._glassesMicState.value != WearableService.Companion.GlassesMicStreamState.IDLE) return
    WearableService._glassesMicState.value = WearableService.Companion.GlassesMicStreamState.STARTING
    glassesMicTransitionAtMs = System.currentTimeMillis()
    glassesMicFrameCount = 0L; glassesMicLastUiUpdateMs = 0L
    WearableService._glassesMicDataText.value = "A iniciar stream..."; audioPipeline.clearQueue()
    startMicrophoneLatencyMeasurement()
    sendGlassesCommand(WearableCommand.StartAudioStream, "iniciar microfone")
    WearableService.appendLog("🎤 Microfone: ativado"); updateWristbandTrafficProfile("pedido start mic")
    adjustStreamFps(audioActive = true, suffix = "mic activo")
}

internal fun WearableService.handleStopMicrophone() {
    if (!WearableService._glassesState.value.isOnlineBle()) { WearableService.appendLog("⚠  Microfone: Omi não está conectado (${WearableService._glassesState.value.name})"); return }
    if (WearableService._glassesMicState.value == WearableService.Companion.GlassesMicStreamState.IDLE && !WearableService._glassesMicStreaming.value) return
    WearableService._glassesMicState.value = WearableService.Companion.GlassesMicStreamState.STOPPING
    glassesMicTransitionAtMs = System.currentTimeMillis()
    sendGlassesCommand(WearableCommand.StopAudioStream, "parar microfone")
    audioPipeline.clearQueue()
    WearableService._glassesMicStreaming.value = false; WearableService._glassesMicStatusText.value = "IDLE"
    WearableService.appendLog("🎤 Microfone: parar stream"); updateWristbandTrafficProfile("pedido stop mic")
    adjustStreamFps(audioActive = false, suffix = "mic parado")
}

internal fun WearableService.adjustStreamFps(audioActive: Boolean, suffix: String) {
    if (!WearableService._isStreaming.value) return
    val fps = streamTargetFps(audioActive)
    glassesBleClient.updateStreamTargetFps(fps)
    WearableService.appendLog("🎬 Stream ${if (audioActive) "ajustado" else "restaurado"} para ${fps}fps ($suffix)")
}

internal fun WearableService.handleStartAudioRecording() {
    if (!WearableService._glassesState.value.isOnlineBle()) { WearableService.appendLog("⚠  Gravação áudio: Omi não está conectado (${WearableService._glassesState.value.name})"); return }
    if (WearableService._audioRecordingActive.value) { WearableService.appendLog("ℹ Gravação áudio já em curso"); return }
    audioRecordPcm = ByteArrayOutputStream(); audioRecordSamples = 0L; audioRecordSampleRate = 16_000
    WearableService._audioRecordingActive.value = true
    WearableService.appendLog("🎙️ Gravação áudio iniciada"); updateWristbandTrafficProfile("gravacao audio iniciada")
    if (WearableService._isStreaming.value) {
        val fps = streamTargetFps(audioActive = true); glassesBleClient.updateStreamTargetFps(fps)
        WearableService.appendLog("🎬 Stream ajustado para ${fps}fps durante gravação áudio")
    }
    if (!WearableService._glassesMicStreaming.value) {
        startMicrophoneLatencyMeasurement()
        sendGlassesCommand(WearableCommand.StartAudioStream, "iniciar microfone para gravacao")
        WearableService.appendLog("🎤 Microfone: ativado para gravação")
    }
}

internal fun WearableService.handleStopAudioRecording() {
    if (!WearableService._audioRecordingActive.value) { WearableService.appendLog("ℹ Não há gravação áudio em curso"); return }
    WearableService._audioRecordingActive.value = false
    persistAudioRecording()?.let { rec ->
        WearableService._recordedAudios.value = WearableService._recordedAudios.value + rec
        WearableService.appendLog("✅ Gravação guardada (#${rec.index}) ${"%.1f".format(rec.durationSec)}s @${rec.sampleRateHz}Hz")
    } ?: WearableService.appendLog("⚠  Gravação descartada (sem dados)")
    updateWristbandTrafficProfile("gravacao audio parada")
    if (WearableService._isStreaming.value) {
        val fps = streamTargetFps(); glassesBleClient.updateStreamTargetFps(fps)
        WearableService.appendLog("🎬 Stream restaurado para ${fps}fps")
    }
}

internal fun WearableService.handleSetMlProcessingLocation(intent: Intent) {
    val name = intent.getStringExtra(EXTRA_ML_PROCESSING_LOCATION)
    WearableService.appendLog("🧠  Pedido de modo ML recebido: ${name ?: "(null)"}")
    val mode = name?.let { runCatching { MlProcessingLocation.valueOf(it) }.getOrNull() } ?: MlProcessingLocation.APP
    setMlProcessingLocation(mode)
}

internal fun WearableService.handleSetMlServerUrl(intent: Intent) {
    val url = intent.getStringExtra(EXTRA_ML_SERVER_URL)?.takeIf { it.isNotBlank() } ?: return
    WearableService._mlServerUrl.value = url
    getSharedPreferences(WearableService.Companion.ATCLL_PREFS, Context.MODE_PRIVATE).edit().putString(WearableService.Companion.IMU_PREF_URL, url).apply()
    serverClassifier = runCatching { PeciServerClassifier(url) }
        .onSuccess { WearableService.appendLog("☁️ ML server atualizado: $url") }
        .onFailure { WearableService.appendLog("⚠️ Falha ao atualizar cliente ML server: ${it.message}") }
        .getOrNull()
}

internal fun WearableService.handleApplyGlassesCameraProfile(intent: Intent) {
    val resolution = intent.getIntExtra(EXTRA_CAMERA_RESOLUTION, -1).takeIf { it > 0 }
    val quality = intent.getIntExtra(EXTRA_CAMERA_QUALITY_FACTOR, -1).takeIf { it >= 0 }
    val rateMs = intent.getIntExtra(EXTRA_CAMERA_RATE_MS, -1).takeIf { it > 0 }
    if (resolution == null && quality == null && rateMs == null) { WearableService.appendLog("⚠  Perfil de câmara inválido"); return }
    val wasStreaming = WearableService._isStreaming.value
    if (wasStreaming) glassesBleClient.setStreamMode(false)
    glassesBleClient.applyCameraSettings(resolution, quality, rateMs)
    glassesCameraManager.resetState("camera profile change")
    if (wasStreaming) serviceScope.launch {
        delay(800); glassesBleClient.setStreamMode(true, streamTargetFps())
        WearableService.appendLog("🎬 Stream re-iniciado com novo perfil")
    }
}

internal fun WearableService.handleApplyGlassesMicrophoneProfile(intent: Intent) {
    val sr = intent.getIntExtra(EXTRA_MICROPHONE_SAMPLE_RATE, -1)
    val bd = intent.getIntExtra(EXTRA_MICROPHONE_BIT_DEPTH, -1)
    val profile = GlassesMicrophoneProfile.fromAppValues(sr, bd)
    if (profile == null) WearableService.appendLog("Perfil de microfone invalido: ${sr}Hz/${bd}-bit")
    else applyGlassesMicrophoneProfileInternal(profile)
}

internal fun WearableService.handleConnectUdp() {
    val ip = WearableService._glassesIp.value
    if (ip.isNullOrBlank()) {
        WearableService.appendLog("⚠  Sem IP Wi-Fi conhecido dos Omi; a forçar enable + poll")
        glassesBleClient.sendWifiEnabled(true); glassesBleClient.requestWifiInfo()
        startWifiTransitionWatchdog()
    } else connectUdp(ip)
}

internal fun WearableService.handleSetGlassesConnectionMode(intent: Intent) {
    val mode = runCatching { GlassesConnectionMode.valueOf(intent.getStringExtra(EXTRA_GLASSES_CONNECTION_MODE) ?: "BLE") }
        .getOrDefault(GlassesConnectionMode.BLE)
    WearableService._glassesConnectionMode.value = mode
    WearableService.appendLog("Omi: modo preferido = ${if (mode == GlassesConnectionMode.WIFI) "Wi-Fi" else "BLE"}")
    if (mode == GlassesConnectionMode.BLE) {
        if (glassesConnectedViaUdp || WearableService._udpActive.value || wifiUdpRecovery.activeUdpManager != null) {
            WearableService.appendLog("🔁 A mudar para BLE: a terminar sessão Wi-Fi/UDP")
            wifiUdpRecovery.disconnectUdpSession(); wifiUdpRecovery.clearRouting()
            WearableService._glassesState.value = BleDeviceState.DISCONNECTED
        }
        if (WearableService._glassesState.value == BleDeviceState.DISCONNECTED || WearableService._glassesState.value == BleDeviceState.ERROR) {
            WearableService.appendLog("🔁 A iniciar reconexão BLE para modo BLE")
            startFilteredConnect(WearableKind.GLASSES, "Omi")
        }
    }
    if (WearableService._isStreaming.value) glassesBleClient.updateStreamTargetFps(streamTargetFps())
}

internal fun WearableService.handleStartStream() {
    if (WearableService._glassesConnectionMode.value == GlassesConnectionMode.WIFI && !WearableService._udpActive.value) {
        val ip = WearableService._glassesIp.value
        if (ip.isNullOrBlank()) WearableService.appendLog("⚠  Stream Wi-Fi: sem IP conhecido dos Omi")
        else { WearableService.appendLog("ℹ Stream Wi-Fi: sessão UDP não ativa, a tentar ligar..."); connectUdp(ip) }
        return
    }
    WearableService._glassesCameraState.value = WearableService.Companion.GlassesCameraStreamState.STARTING
    val fps = streamTargetFps()
    sendGlassesCommand(WearableCommand.StartVideoStream(fps), "iniciar stream")
    WearableService._isStreaming.value = true; WearableService._streamFrameCount.value = 0; streamFpsTimestamps.clear()
    WearableService.appendLog("🎥 Streaming de vídeo iniciado (alvo=${fps}fps)")
}

internal fun WearableService.handleStopStream() {
    WearableService._glassesCameraState.value = WearableService.Companion.GlassesCameraStreamState.STOPPING
    serviceScope.launch {
        wearableHub.sessions.value.values.forEach { session ->
            if (session.capabilities.contains(com.example.peciwearables.integration.wearable.WearableCapability.VIDEO_STREAM))
                session.send(WearableCommand.StopVideoStream)
        }
    }
    WearableService._isStreaming.value = false; WearableService._streamFps.value = 0f; streamFpsTimestamps.clear()
    WearableService._lastFrameTimestampMs.value = null
    WearableService.appendLog("⏹ Streaming de vídeo parado")
}

internal fun WearableService.handleSetGlassesInferenceMode(intent: Intent) {
    val mode = runCatching { InferenceMode.valueOf(intent.getStringExtra(EXTRA_GLASSES_INFERENCE_MODE) ?: "LOCAL") }
        .getOrDefault(InferenceMode.LOCAL)
    glassesInferenceManager.setMode(mode); WearableService._glassesInferenceMode.value = mode
    WearableService.appendLog("👁 Glasses inference → $mode")
}

internal fun WearableService.handleSetGlassesInferenceUrl(intent: Intent) {
    val url = intent.getStringExtra(EXTRA_GLASSES_INFERENCE_URL).orEmpty()
    if (url.isBlank()) return
    glassesInferenceManager.setCloudUrl(url); WearableService._glassesInferenceCloudUrl.value = url
    WearableService.appendLog("👁 Glasses inference URL → $url")
}

internal fun WearableService.handleSetImuStreaming(intent: Intent) {
    val enabled = intent.getBooleanExtra(EXTRA_IMU_STREAMING_ENABLED, false)
    sendGlassesCommand(WearableCommand.SetImuStreaming(enabled), "alterar IMU")
    WearableService._imuStreamingEnabled.value = enabled
    if (!enabled) glassesQuaternionTracker.reset()
}

internal fun WearableService.handleWatchNotify(intent: Intent) {
    val type = intent.getIntExtra(EXTRA_NOTIFY_TYPE, 0)
    val title = intent.getStringExtra(EXTRA_WATCH_NOTIFY_TITLE) ?: "PECI"
    val body = intent.getStringExtra(EXTRA_WATCH_NOTIFY_BODY) ?: ""
    watchClient.requestNotify(type, title, body)
    WearableService.appendLog("⌚ Notificação watch: ${if (type == 1) "PERIGO" else "AVISO"} — $title")
}

internal fun WearableService.handleSetNavisensSource(intent: Intent) {
    val raw = intent.getStringExtra(EXTRA_NAVISENS_IMU_SOURCE)
    runCatching { NavisensImuSource.valueOf(raw ?: "") }
        .onSuccess { WearableService._navisensImuSource.value = it }
        .onFailure { WearableService.appendLog("⚠ Fonte IMU inválida: $raw") }
}

internal fun WearableService.handleAudioTestTone(intent: Intent) {
    val preset = intent.getStringExtra(EXTRA_TONE_PRESET)?.let {
        runCatching { AudioTestEngine.TonePreset.valueOf(it) }.getOrNull()
    }
    if (preset != null) { AudioTestEngine.playPreset(preset); WearableService.appendLog("🔊 Tom de teste: ${preset.label}"); return }
    val f = intent.getIntExtra(EXTRA_TONE_FREQ_HZ, 880); val d = intent.getIntExtra(EXTRA_TONE_DURATION_MS, 250)
    AudioTestEngine.playTone(f, d); WearableService.appendLog("🔊 Tom de teste: ${f}Hz / ${d}ms")
}

internal fun WearableService.handleConnectCandidate(intent: Intent) {
    val address = intent.getStringExtra(EXTRA_CANDIDATE_ADDRESS)
    if (address.isNullOrBlank()) WearableService.appendLog("Falta EXTRA_CANDIDATE_ADDRESS para ligar candidato")
    else connectSelectedCandidate(address)
}

internal fun WearableService.handleSendWifi(intent: Intent) {
    val ssid = intent.getStringExtra(EXTRA_SSID) ?: return
    val pass = intent.getStringExtra(EXTRA_PASSWORD) ?: return
    if (ssid.isBlank()) { WearableService.appendLog("⚠  Wi-Fi: SSID vazio, nada enviado"); return }
    wifiCredentialsSent = true
    WearableService.appendLog("📶 A enviar configuração Wi-Fi para Omi (SSID=$ssid, ${pass.length} chars)")
    sendGlassesCommand(WearableCommand.ConfigureWifi(ssid, pass), "configurar Wi-Fi")
    if (WearableService._glassesConnectionMode.value == GlassesConnectionMode.WIFI) startWifiTransitionWatchdog()
    else WearableService.appendLog("ℹ Wi-Fi: credenciais enviadas mas modo é BLE — muda para Wi-Fi para usar UDP")
}

internal fun WearableService.handleWakeCamera() {
    if (WearableService._glassesState.value.isOnlineBle()) { glassesBleClient.wakeCamera(); WearableService.appendLog("📷 Câmera sempre ativa neste firmware") }
    else WearableService.appendLog("⚠  Câmera: Omi não está conectado (${WearableService._glassesState.value.name})")
}

internal fun WearableService.handleUcToggle(intent: Intent, label: String, setter: (Boolean) -> Unit) {
    val on = intent.getBooleanExtra(EXTRA_SAFETY_ENABLED, false)
    setter(on); WearableService.appendLog("$label ${if (on) "ON" else "OFF"}")
}

internal fun WearableService.handleUcToggleFlow(intent: Intent, flow: MutableStateFlow<Boolean>, label: String) {
    flow.value = intent.getBooleanExtra(EXTRA_SAFETY_ENABLED, false)
    WearableService.appendLog("$label ${if (flow.value) "ON" else "OFF"}")
}

internal fun WearableService.handleUc45Toggle(intent: Intent) {
    val on = intent.getBooleanExtra(EXTRA_SAFETY_ENABLED, false)
    WearableService._safetyUc4_5Enabled.value = on
    if (on) ttsEngine.speak("Transcrição activa", flush = true)
    else { ttsEngine.stop(); conversationTranscriber.reset() }
    WearableService.appendLog("🗣 UC4.5 (transcrição realtime) ${if (on) "ON" else "OFF"}")
}

internal fun WearableService.handleAddZoneHere(intent: Intent) {
    val gps = WearableService._phoneGps.value
    if (gps == null) { WearableService.appendLog("⚠ Adicionar zona: sem GPS — activa primeiro os sensores."); return }
    val name = intent.getStringExtra(EXTRA_SAFETY_ZONE_NAME)?.takeIf { it.isNotBlank() }
        ?: "Passadeira ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
    val radius = intent.getFloatExtra(EXTRA_SAFETY_ZONE_RADIUS, 15f)
    val zone = CrossingZone(java.util.UUID.randomUUID().toString(), name, gps.latitude, gps.longitude, radius)
    crossingZoneStore.add(zone); cloudZoneSync.add(zone)
    WearableService.appendLog("📍 Zona adicionada: $name (raio ${radius.toInt()}m)")
}

internal fun WearableService.handleRemoveZone(intent: Intent) {
    intent.getStringExtra(EXTRA_SAFETY_ZONE_ID)?.let { id ->
        crossingZoneStore.remove(id); cloudZoneSync.remove(id); WearableService.appendLog("🗑 Zona removida")
    }
}

internal fun WearableService.handleRefreshOsm() {
    val gps = WearableService._phoneGps.value
    if (gps == null) { WearableService.appendLog("⚠ OSM refresh: sem GPS — activa primeiro os sensores."); return }
    serviceScope.launch { crossingZoneManager.refresh(gps); WearableService.appendLog("🌐 OSM refresh pedido (raio 500m)") }
}

internal fun WearableService.handleAtcllEndpoint(intent: Intent) {
    val url = intent.getStringExtra(EXTRA_ATCLL_ENDPOINT)
    atcllClient.setEndpoint(url)
    getSharedPreferences(WearableService.Companion.ATCLL_PREFS, Context.MODE_PRIVATE).edit().apply {
        if (url.isNullOrBlank()) remove(WearableService.Companion.ATCLL_PREF_URL) else putString(WearableService.Companion.ATCLL_PREF_URL, url); apply()
    }
    WearableService.appendLog("🌐 ATCLL endpoint: ${url ?: "OFFLINE"}")
}

internal fun WearableService.handleDepthUrl(intent: Intent) {
    val url = intent.getStringExtra(EXTRA_DEPTH_URL) ?: return
    if (url.isBlank()) return
    depthManager.setCloudUrl(url)
    getSharedPreferences(WearableService.Companion.ATCLL_PREFS, Context.MODE_PRIVATE).edit().putString(WearableService.Companion.DEPTH_PREF_URL, url).apply()
    WearableService.appendLog("📡 Depth endpoint guardado: $url")
}

internal fun WearableService.handleUnifiedServerUrl(intent: Intent) {
    val url = intent.getStringExtra(EXTRA_UNIFIED_SERVER_URL)?.takeIf { it.isNotBlank() } ?: return
    getSharedPreferences(WearableService.Companion.ATCLL_PREFS, Context.MODE_PRIVATE).edit().putString(WearableService.Companion.UNIFIED_SERVER_PREF_URL, url).apply()
    WearableService._unifiedServerUrl.value = url
    telemetryReporter.baseUrl = url
    depthManager.setCloudUrl("$url/depth")
    glassesInferenceManager.setCloudUrl("$url/detect")
    connectKwsInternal(hostFromUrl(url)); startGlassesPoseReporter(url)
    WearableService.appendLog("🌐 Unified server: $url")
}

internal fun WearableService.handleAudioNotify(intent: Intent) {
    val freq = intent.getIntExtra(EXTRA_TONE_FREQ_HZ, 880)
    val dur = intent.getIntExtra(EXTRA_TONE_DURATION_MS, 350)
    val title = intent.getStringExtra(EXTRA_NOTIFY_TITLE) ?: "Sinal PECI"
    val body = intent.getStringExtra(EXTRA_NOTIFY_BODY) ?: "Tom ${freq}Hz"
    NotificationSounder.play(this, title, body, freq, dur)
    WearableService.appendLog("🔔 Notificação áudio: $title — $body")
}
