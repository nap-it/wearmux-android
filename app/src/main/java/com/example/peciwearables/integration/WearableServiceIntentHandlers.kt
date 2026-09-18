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
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_MQTT_BROKER_URL
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_MQTT_ENABLED
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_MQTT_TOPIC_PREFIX
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_UNIFIED_SERVER_URL
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_WATCH_NOTIFY_BODY
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_WATCH_NOTIFY_TITLE
import com.example.peciwearables.integration.adapters.BleDeviceState
import com.example.peciwearables.integration.adapters.WearableCommand
import com.example.peciwearables.integration.adapters.WearableSession
import com.example.peciwearables.integration.api.PeciServerClassifier
import com.example.peciwearables.integration.hub.WearableKind
import com.example.peciwearables.integration.inference.InferenceMode
import com.example.peciwearables.integration.modules.android.AudioTestEngine
import com.example.peciwearables.integration.modules.android.NotificationSounder
import com.example.peciwearables.integration.modules.microphone.GlassesMicrophoneProfile
import com.example.peciwearables.integration.safety.CrossingZone
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private fun BleDeviceState.isOnlineBle() = this == BleDeviceState.READY || this == BleDeviceState.CONNECTED

internal fun WearableService.handleStartMicrophone() {
    if (!WearableService._glassesState.value.isOnlineBle()) { WearableService.appendLog("⚠  Microphone: Omi is not connected (${WearableService._glassesState.value.name})"); return }
    val ageMs = if (glassesMicTransitionAtMs > 0L) System.currentTimeMillis() - glassesMicTransitionAtMs else Long.MAX_VALUE
    val stuck = WearableService._glassesMicState.value in setOf(WearableService.Companion.GlassesMicStreamState.STARTING, WearableService.Companion.GlassesMicStreamState.STOPPING) &&
        ageMs >= WearableService.Companion.GLASSES_MIC_TRANSITION_TIMEOUT_MS
    if (stuck) {
        WearableService.appendLog("🎤 Mic state stuck at ${WearableService._glassesMicState.value} for ${ageMs}ms; unsticking")
        WearableService._glassesMicState.value = WearableService.Companion.GlassesMicStreamState.IDLE; WearableService._glassesMicStreaming.value = false
    }
    if (WearableService._glassesMicState.value != WearableService.Companion.GlassesMicStreamState.IDLE) return
    WearableService._glassesMicState.value = WearableService.Companion.GlassesMicStreamState.STARTING
    glassesMicTransitionAtMs = System.currentTimeMillis()
    glassesMicFrameCount = 0L; glassesMicLastUiUpdateMs = 0L
    WearableService._glassesMicDataText.value = "Starting stream..."; audioPipeline.clearQueue()
    startMicrophoneLatencyMeasurement()
    sendGlassesCommand(WearableCommand.StartAudioStream, "start microphone")
    WearableService.appendLog("🎤 Microphone: enabled"); updateWristbandTrafficProfile("mic start requested")
    adjustStreamFps(audioActive = true, suffix = "mic active")
}

internal fun WearableService.handleStopMicrophone() {
    if (!WearableService._glassesState.value.isOnlineBle()) { WearableService.appendLog("⚠  Microphone: Omi is not connected (${WearableService._glassesState.value.name})"); return }
    if (WearableService._glassesMicState.value == WearableService.Companion.GlassesMicStreamState.IDLE && !WearableService._glassesMicStreaming.value) return
    WearableService._glassesMicState.value = WearableService.Companion.GlassesMicStreamState.STOPPING
    glassesMicTransitionAtMs = System.currentTimeMillis()
    sendGlassesCommand(WearableCommand.StopAudioStream, "stop microphone")
    audioPipeline.clearQueue()
    WearableService._glassesMicStreaming.value = false; WearableService._glassesMicStatusText.value = "IDLE"
    WearableService.appendLog("🎤 Microphone: stop stream"); updateWristbandTrafficProfile("mic stop requested")
    adjustStreamFps(audioActive = false, suffix = "mic stopped")
}

internal fun WearableService.adjustStreamFps(audioActive: Boolean, suffix: String) {
    if (!WearableService._isStreaming.value) return
    val fps = streamTargetFps(audioActive)
    glassesBleClient.updateStreamTargetFps(fps)
    WearableService.appendLog("🎬 Stream ${if (audioActive) "adjusted" else "restored"} to ${fps}fps ($suffix)")
}

internal fun WearableService.handleStartAudioRecording() {
    if (!WearableService._glassesState.value.isOnlineBle()) { WearableService.appendLog("⚠  Audio recording: Omi is not connected (${WearableService._glassesState.value.name})"); return }
    if (WearableService._audioRecordingActive.value) { WearableService.appendLog("ℹ Audio recording already in progress"); return }
    audioRecordPcm = ByteArrayOutputStream(); audioRecordSamples = 0L; audioRecordSampleRate = 16_000
    WearableService._audioRecordingActive.value = true
    WearableService.appendLog("🎙️ Audio recording started"); updateWristbandTrafficProfile("audio recording started")
    if (WearableService._isStreaming.value) {
        val fps = streamTargetFps(audioActive = true); glassesBleClient.updateStreamTargetFps(fps)
        WearableService.appendLog("🎬 Stream adjusted to ${fps}fps during audio recording")
    }
    if (!WearableService._glassesMicStreaming.value) {
        startMicrophoneLatencyMeasurement()
        sendGlassesCommand(WearableCommand.StartAudioStream, "start microphone for recording")
        WearableService.appendLog("🎤 Microphone: enabled for recording")
    }
}

internal fun WearableService.handleStopAudioRecording() {
    if (!WearableService._audioRecordingActive.value) { WearableService.appendLog("ℹ No audio recording in progress"); return }
    WearableService._audioRecordingActive.value = false
    persistAudioRecording()?.let { rec ->
        WearableService._recordedAudios.value = WearableService._recordedAudios.value + rec
        WearableService.appendLog("✅ Recording saved (#${rec.index}) ${"%.1f".format(rec.durationSec)}s @${rec.sampleRateHz}Hz")
    } ?: WearableService.appendLog("⚠  Recording discarded (no data)")
    updateWristbandTrafficProfile("audio recording stopped")
    if (WearableService._isStreaming.value) {
        val fps = streamTargetFps(); glassesBleClient.updateStreamTargetFps(fps)
        WearableService.appendLog("🎬 Stream restored to ${fps}fps")
    }
}

internal fun WearableService.handleSetMlProcessingLocation(intent: Intent) {
    val name = intent.getStringExtra(EXTRA_ML_PROCESSING_LOCATION)
    WearableService.appendLog("🧠  ML mode request received: ${name ?: "(null)"}")
    val mode = name?.let { runCatching { MlProcessingLocation.valueOf(it) }.getOrNull() } ?: MlProcessingLocation.APP
    setMlProcessingLocation(mode)
}

internal fun WearableService.handleSetMlServerUrl(intent: Intent) {
    val url = intent.getStringExtra(EXTRA_ML_SERVER_URL)?.takeIf { it.isNotBlank() } ?: return
    WearableService._mlServerUrl.value = url
    getSharedPreferences(WearableService.Companion.ATCLL_PREFS, Context.MODE_PRIVATE).edit().putString(WearableService.Companion.IMU_PREF_URL, url).apply()
    serverClassifier = runCatching { PeciServerClassifier(url) }
        .onSuccess { WearableService.appendLog("☁️ ML server updated: $url") }
        .onFailure { WearableService.appendLog("⚠️ Failed to update ML server client: ${it.message}") }
        .getOrNull()
}

internal fun WearableService.handleApplyGlassesCameraProfile(intent: Intent) {
    val resolution = intent.getIntExtra(EXTRA_CAMERA_RESOLUTION, -1).takeIf { it > 0 }
    val quality = intent.getIntExtra(EXTRA_CAMERA_QUALITY_FACTOR, -1).takeIf { it >= 0 }
    val rateMs = intent.getIntExtra(EXTRA_CAMERA_RATE_MS, -1).takeIf { it > 0 }
    if (resolution == null && quality == null && rateMs == null) { WearableService.appendLog("⚠  Invalid camera profile"); return }
    val wasStreaming = WearableService._isStreaming.value
    if (wasStreaming) glassesBleClient.setStreamMode(false)
    glassesBleClient.applyCameraSettings(resolution, quality, rateMs)
    glassesCameraManager.resetState("camera profile change")
    if (wasStreaming) serviceScope.launch {
        delay(800); glassesBleClient.setStreamMode(true, streamTargetFps())
        WearableService.appendLog("🎬 Stream restarted with new profile")
    }
}

internal fun WearableService.handleApplyGlassesMicrophoneProfile(intent: Intent) {
    val sr = intent.getIntExtra(EXTRA_MICROPHONE_SAMPLE_RATE, -1)
    val bd = intent.getIntExtra(EXTRA_MICROPHONE_BIT_DEPTH, -1)
    val profile = GlassesMicrophoneProfile.fromAppValues(sr, bd)
    if (profile == null) WearableService.appendLog("Invalid microphone profile: ${sr}Hz/${bd}-bit")
    else applyGlassesMicrophoneProfileInternal(profile)
}

internal fun WearableService.handleConnectUdp() {
    val ip = WearableService._glassesIp.value
    if (ip.isNullOrBlank()) {
        WearableService.appendLog("⚠  No known Wi-Fi IP for the Omi; forcing enable + poll")
        glassesBleClient.sendWifiEnabled(true); glassesBleClient.requestWifiInfo()
        startWifiTransitionWatchdog()
    } else connectUdp(ip)
}

internal fun WearableService.handleSetGlassesConnectionMode(intent: Intent) {
    val mode = runCatching { GlassesConnectionMode.valueOf(intent.getStringExtra(EXTRA_GLASSES_CONNECTION_MODE) ?: "BLE") }
        .getOrDefault(GlassesConnectionMode.BLE)
    WearableService._glassesConnectionMode.value = mode
    WearableService.appendLog("Omi: preferred mode = ${if (mode == GlassesConnectionMode.WIFI) "Wi-Fi" else "BLE"}")
    if (mode == GlassesConnectionMode.BLE) {
        if (glassesConnectedViaUdp || WearableService._udpActive.value || wifiUdpRecovery.activeUdpManager != null) {
            WearableService.appendLog("🔁 Switching to BLE: ending Wi-Fi/UDP session")
            wifiUdpRecovery.disconnectUdpSession(); wifiUdpRecovery.clearRouting()
            WearableService._glassesState.value = BleDeviceState.DISCONNECTED
        }
        if (WearableService._glassesState.value == BleDeviceState.DISCONNECTED || WearableService._glassesState.value == BleDeviceState.ERROR) {
            WearableService.appendLog("🔁 Starting BLE reconnection for BLE mode")
            startFilteredConnect(WearableKind.GLASSES, "Omi")
        }
    }
    if (WearableService._isStreaming.value) glassesBleClient.updateStreamTargetFps(streamTargetFps())
}

internal fun WearableService.handleStartStream() {
    if (WearableService._glassesConnectionMode.value == GlassesConnectionMode.WIFI && !WearableService._udpActive.value) {
        val ip = WearableService._glassesIp.value
        if (ip.isNullOrBlank()) WearableService.appendLog("⚠  Wi-Fi stream: no known Omi IP")
        else { WearableService.appendLog("ℹ Wi-Fi stream: UDP session not active, trying to connect..."); connectUdp(ip) }
        return
    }
    WearableService._glassesCameraState.value = WearableService.Companion.GlassesCameraStreamState.STARTING
    val fps = streamTargetFps()
    sendGlassesCommand(WearableCommand.StartVideoStream(fps), "start stream")
    WearableService._isStreaming.value = true; WearableService._streamFrameCount.value = 0; streamFpsTimestamps.clear()
    WearableService.appendLog("🎥 Video streaming started (target=${fps}fps)")
}

internal fun WearableService.handleStopStream() {
    WearableService._glassesCameraState.value = WearableService.Companion.GlassesCameraStreamState.STOPPING
    serviceScope.launch {
        deviceHub.sessions.value.values.forEach { session ->
            if (session.capabilities.contains(com.example.peciwearables.integration.adapters.WearableCapability.VIDEO_STREAM))
                session.send(WearableCommand.StopVideoStream)
        }
    }
    WearableService._isStreaming.value = false; WearableService._streamFps.value = 0f; streamFpsTimestamps.clear()
    WearableService._lastFrameTimestampMs.value = null
    WearableService.appendLog("⏹ Video streaming stopped")
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
    sendGlassesCommand(WearableCommand.SetImuStreaming(enabled), "change IMU")
    WearableService._imuStreamingEnabled.value = enabled
    if (!enabled) glassesQuaternionTracker.reset()
}

internal fun WearableService.handleWatchNotify(intent: Intent) {
    val type = intent.getIntExtra(EXTRA_NOTIFY_TYPE, 0)
    val title = intent.getStringExtra(EXTRA_WATCH_NOTIFY_TITLE) ?: "PECI"
    val body = intent.getStringExtra(EXTRA_WATCH_NOTIFY_BODY) ?: ""
    watchClient.requestNotify(type, title, body)
    WearableService.appendLog("⌚ Watch notification: ${if (type == 1) "DANGER" else "WARNING"} — $title")
}

internal fun WearableService.handleSetNavisensSource(intent: Intent) {
    val raw = intent.getStringExtra(EXTRA_NAVISENS_IMU_SOURCE)
    runCatching { NavisensImuSource.valueOf(raw ?: "") }
        .onSuccess { WearableService._navisensImuSource.value = it }
        .onFailure { WearableService.appendLog("⚠ Invalid IMU source: $raw") }
}

internal fun WearableService.handleAudioTestTone(intent: Intent) {
    val preset = intent.getStringExtra(EXTRA_TONE_PRESET)?.let {
        runCatching { AudioTestEngine.TonePreset.valueOf(it) }.getOrNull()
    }
    if (preset != null) { AudioTestEngine.playPreset(preset); WearableService.appendLog("🔊 Test tone: ${preset.label}"); return }
    val f = intent.getIntExtra(EXTRA_TONE_FREQ_HZ, 880); val d = intent.getIntExtra(EXTRA_TONE_DURATION_MS, 250)
    AudioTestEngine.playTone(f, d); WearableService.appendLog("🔊 Test tone: ${f}Hz / ${d}ms")
}

internal fun WearableService.handleConnectCandidate(intent: Intent) {
    val address = intent.getStringExtra(EXTRA_CANDIDATE_ADDRESS)
    if (address.isNullOrBlank()) WearableService.appendLog("Missing EXTRA_CANDIDATE_ADDRESS to connect candidate")
    else connectSelectedCandidate(address)
}

internal fun WearableService.handleSendWifi(intent: Intent) {
    val ssid = intent.getStringExtra(EXTRA_SSID) ?: return
    val pass = intent.getStringExtra(EXTRA_PASSWORD) ?: return
    if (ssid.isBlank()) { WearableService.appendLog("⚠  Wi-Fi: empty SSID, nothing sent"); return }
    wifiCredentialsSent = true
    WearableService.appendLog("📶 Sending Wi-Fi configuration to Omi (SSID=$ssid, ${pass.length} chars)")
    sendGlassesCommand(WearableCommand.ConfigureWifi(ssid, pass), "configure Wi-Fi")
    if (WearableService._glassesConnectionMode.value == GlassesConnectionMode.WIFI) startWifiTransitionWatchdog()
    else WearableService.appendLog("ℹ Wi-Fi: credentials sent but mode is BLE — switch to Wi-Fi to use UDP")
}

internal fun WearableService.handleWakeCamera() {
    if (WearableService._glassesState.value.isOnlineBle()) { glassesBleClient.wakeCamera(); WearableService.appendLog("📷 Camera is always on with this firmware") }
    else WearableService.appendLog("⚠  Camera: Omi is not connected (${WearableService._glassesState.value.name})")
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
    if (on) ttsEngine.speak("Transcription active", flush = true)
    else { ttsEngine.stop(); conversationTranscriber.reset() }
    WearableService.appendLog("🗣 UC4.5 (real-time transcription) ${if (on) "ON" else "OFF"}")
}

internal fun WearableService.handleAddZoneHere(intent: Intent) {
    val gps = WearableService._phoneGps.value
    if (gps == null) { WearableService.appendLog("⚠ Add zone: no GPS — enable the sensors first."); return }
    val name = intent.getStringExtra(EXTRA_SAFETY_ZONE_NAME)?.takeIf { it.isNotBlank() }
        ?: "Crosswalk ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
    val radius = intent.getFloatExtra(EXTRA_SAFETY_ZONE_RADIUS, 15f)
    val zone = CrossingZone(java.util.UUID.randomUUID().toString(), name, gps.latitude, gps.longitude, radius)
    crossingZoneStore.add(zone); cloudZoneSync.add(zone)
    WearableService.appendLog("📍 Zone added: $name (radius ${radius.toInt()}m)")
}

internal fun WearableService.handleRemoveZone(intent: Intent) {
    intent.getStringExtra(EXTRA_SAFETY_ZONE_ID)?.let { id ->
        crossingZoneStore.remove(id); cloudZoneSync.remove(id); WearableService.appendLog("🗑 Zone removed")
    }
}

internal fun WearableService.handleRefreshOsm() {
    val gps = WearableService._phoneGps.value
    if (gps == null) { WearableService.appendLog("⚠ OSM refresh: no GPS — enable the sensors first."); return }
    serviceScope.launch { crossingZoneManager.refresh(gps); WearableService.appendLog("🌐 OSM refresh requested (radius 500m)") }
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
    WearableService.appendLog("📡 Depth endpoint saved: $url")
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
    val title = intent.getStringExtra(EXTRA_NOTIFY_TITLE) ?: "PECI signal"
    val body = intent.getStringExtra(EXTRA_NOTIFY_BODY) ?: "Tone ${freq}Hz"
    NotificationSounder.play(this, title, body, freq, dur)
    WearableService.appendLog("🔔 Audio notification: $title — $body")
}

internal fun WearableService.handleMqttSetConfig(intent: Intent) {
    val current = WearableService._mqttConfig.value
    val broker = intent.getStringExtra(EXTRA_MQTT_BROKER_URL)?.trim() ?: current.brokerUrl
    val prefix = intent.getStringExtra(EXTRA_MQTT_TOPIC_PREFIX)?.trim()?.takeIf { it.isNotBlank() }
        ?: current.topicPrefix
    val enabled = if (intent.hasExtra(EXTRA_MQTT_ENABLED)) {
        intent.getBooleanExtra(EXTRA_MQTT_ENABLED, current.enabled)
    } else {
        current.enabled
    }
    val next = current.copy(enabled = enabled, brokerUrl = broker, topicPrefix = prefix)
    WearableService._mqttConfig.value = next
    getSharedPreferences(WearableService.ATCLL_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(WearableService.MQTT_PREF_ENABLED, next.enabled)
        .putString(WearableService.MQTT_PREF_BROKER, next.brokerUrl)
        .putString(WearableService.MQTT_PREF_TOPIC_PREFIX, next.topicPrefix)
        .apply()
    // Fecha a ligação actual: a próxima publicação reabre já com o novo broker.
    mqttPublisher.shutdown()
    WearableService.appendLog(
        if (next.isUsable) "MQTT: ${next.normalizedBrokerUrl()} (prefix ${next.topicPrefix})"
        else "MQTT: disabled"
    )
}
