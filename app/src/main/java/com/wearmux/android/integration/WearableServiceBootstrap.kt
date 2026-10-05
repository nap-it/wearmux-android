package com.wearmux.android.integration

import com.wearmux.android.integration.adapters.devices.brilliantsole.BrilliantSoleWristbandWearableAdapter
import com.wearmux.android.integration.adapters.devices.brilliantsole.defaultBrilliantSoleClientFactory
import com.wearmux.android.integration.adapters.devices.omi.OmiGlassesWearableAdapter
import com.wearmux.android.integration.adapters.devices.omi.defaultOmiGlassesClientFactory
import com.wearmux.android.integration.api.AtcllClient
import com.wearmux.android.integration.api.MqttConfig
import com.wearmux.android.integration.api.WearMuxServerClassifier
import com.wearmux.android.integration.hub.DefaultDeviceConnectionCoordinator
import com.wearmux.android.integration.hub.DefaultDeviceHub
import com.wearmux.android.integration.hub.standardWearableAdapterRegistry
import com.wearmux.android.integration.ml.WearMuxOnDeviceClassifier
import com.wearmux.android.integration.modules.camera.BleCameraPipeline
import com.wearmux.android.integration.modules.camera.CameraStreamHealth
import com.wearmux.android.integration.modules.camera.GlassesCameraManager
import com.wearmux.android.integration.modules.camera.computeCameraStreamStatus
import com.wearmux.android.integration.modules.microphone.GlassesMicrophoneManager
import com.wearmux.android.integration.modules.wearos.WatchClient
import com.wearmux.android.integration.output.OutputDispatcher
import com.wearmux.android.integration.safety.CrossingZoneManager
import com.wearmux.android.integration.safety.CrossingZoneStore
import com.wearmux.android.integration.safety.SafetyDiagnostics
import com.wearmux.android.integration.safety.SafetyGates
import com.wearmux.android.integration.safety.SafetyOrchestrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun WearableService.bootstrapAdaptersAndHub() {
    glassesWearableAdapter = OmiGlassesWearableAdapter(defaultOmiGlassesClientFactory(this), wearableRealtimeSink)
    wristbandWearableAdapter = BrilliantSoleWristbandWearableAdapter(defaultBrilliantSoleClientFactory(this), wearableRealtimeSink)
    val esp32 = com.wearmux.android.integration.adapters.devices.esp32.Esp32WearableAdapter(realtimeSink = wearableRealtimeSink)
    watchClient = com.wearmux.android.integration.modules.wearos.WatchClient(this).also {
        it.csvWriter = latencyCsvWriter
    }
    val galaxyWatch = com.wearmux.android.integration.adapters.devices.galaxywatch.GalaxyWatchWearableAdapter(watchClient)
    deviceHub = DefaultDeviceHub(
        DefaultDeviceConnectionCoordinator(standardWearableAdapterRegistry(glassesWearableAdapter, wristbandWearableAdapter, esp32, galaxyWatch)),
        serviceScope,
    )
    WearableService._glassesWifiSupported.value = false
    WearableService._glassesWifiConnectionEnabled.value = null
    WearableService._glassesWifiConnected.value = null
    WearableService._glassesWifiSecure.value = null
}

internal fun WearableService.bootstrapCameraAndMicManagers() {
    val onJpeg: (ByteArray) -> Unit = { jpeg ->
        latestCameraJpeg = jpeg; latestCameraJpegAtMs = timestamper.now()
        glassesBleClient.onSdkStreamImageAssembled()
    }
    bleCameraPipeline = BleCameraPipeline(metrics = cameraMetrics).apply {
        onLog = WearableService::appendLog; onJpegReady = onJpeg
    }
    glassesCameraManager = GlassesCameraManager(
        cameraPipeline = bleCameraPipeline,
        onBitmapReady = { bitmap ->
            photoCaptureInFlight = false; photoCaptureTimeoutJob?.cancel()
            publishCapturedPhoto(bitmap); updateWristbandTrafficProfile("photo received")
        },
        onJpegReady = onJpeg, onLog = WearableService::appendLog, metrics = cameraMetrics,
    )
    runCatching { glassesBleClient.attachCameraMetrics(cameraMetrics) }
    cameraMetrics.start(serviceScope)
    glassesMicrophoneManager = GlassesMicrophoneManager(audioPipeline, WearableService::appendLog)
}

internal fun WearableService.wireCoAxialFusion() {
    coAxialImuFusion.start()
    serviceScope.launch {
        coAxialImuFusion.stream.collect { fused ->
            WearableService._fusedImuStream.tryEmit(fused)
            WearableService._fusedImuSourceCount.value = coAxialImuFusion.lastFusedSourceCount
            if (WearableService._navisensImuSource.value == NavisensImuSource.FUSED)
                WearableService._latestImuSamples.update { (it + fusedToImuSample(fused)).takeLast(40) }
        }
    }
}

internal fun WearableService.bootstrapWatchClient() {
    watchClient.onSampleTimestampUs = { tsUs -> timeSyncManager.addSample("galaxy_watch", tsUs) }
    watchClient.start()
    // Liga o watch ao hub genérico assim que o nome do nó fica disponível.
    serviceScope.launch {
        watchClient.watchNodeName.collect { name ->
            if (name.isNullOrBlank()) return@collect
            val candidate = com.wearmux.android.integration.adapters.devices.galaxywatch
                .GalaxyWatchWearableAdapter.syntheticCandidate(name)
            runCatching { deviceHub.connectByCandidate(candidate) }
        }
    }
    com.wearmux.android.integration.modules.wearos.WatchClientObservers(
        scope = serviceScope, watch = watchClient, imuStream = WearableService._watchImuStream,
        onState = { WearableService._watchState.value = it }, onName = { WearableService._watchName.value = it },
        onBattery = { WearableService._watchBattery.value = it }, onSampleRate = { WearableService._watchSampleRateHz.value = it },
        onSamplesPerSec = { WearableService._watchSamplesPerSec.value = it }, onHeartRate = { WearableService._watchHeartRateBpm.value = it },
        onLastError = { WearableService._watchLastError.value = it },
        onImuSample = { sample ->
            coAxialImuFusion.feedWatch(sample)
            if (WearableService._navisensImuSource.value == NavisensImuSource.WATCH) WearableService._latestImuSamples.update { (it + sample).takeLast(40) }
        },
        onLog = WearableService::appendLog,
        heartbeat = object : com.wearmux.android.integration.modules.wearos.WatchClientObservers.HeartbeatProvider {
            override val glassesState get() = WearableService._glassesState.value
            override val wristbandState get() = WearableService._wristbandState.value
            override val phoneSensorsActive get() = WearableService._phoneSensorsActive.value
        },
    ).attach()
}

internal fun WearableService.bootstrapCrossingZones() {
    crossingZoneStore = CrossingZoneStore(this)
    crossingZoneManager = CrossingZoneManager(scope = serviceScope, store = crossingZoneStore)
        .also { it.attachToGps(WearableService._phoneGps) }
}

internal fun WearableService.bootstrapSafety() {
    val outputDispatcher = OutputDispatcher(
        watchClient,
        vibrateWristband = { runCatching { wristbandBleClient.sendStopAlertVibration() } },
        vibrateWristbandIntensity = { pct ->
            val a = (pct.coerceIn(0, 100) / 100f).coerceAtLeast(0.2f)
            runCatching { wristbandBleClient.sendWaveformVibration(a, durationMs = 250, locationBitmask = 0x03) }
        },
        eventBus = safetyEventBus,
    )
    serviceScope.launch { safetyEventBus.events.collect { ev -> WearableService.appendSafetyLog("⚡ ${ev.kind} $ev") } }
    safetyDiagnostics = SafetyDiagnostics().also {
        it.start(serviceScope, WearableService._glassesImuStream, WearableService._latestImuSamples, WearableService._phoneAccel)
    }
    safetyOrchestrator = SafetyOrchestrator(serviceScope, safetyDecisionFeed, outputDispatcher, SafetyGates(safetyToggles), ::mirrorDecisionForUi)
    safetyOrchestrator.start()
}

internal fun WearableService.bootstrapAtcllAndTelemetry(cloudPrefs: android.content.SharedPreferences) {
    atcllClient = AtcllClient(serviceScope)
    cloudPrefs.getString(WearableService.ATCLL_PREF_URL, null)?.takeIf { it.isNotBlank() }?.let {
        atcllClient.setEndpoint(it); WearableService.appendLog("🌐 ATCLL endpoint restored: $it")
    }
    val unifiedUrl = cloudPrefs.getString(WearableService.UNIFIED_SERVER_PREF_URL, WearableService.UNIFIED_SERVER_DEFAULT_URL)
        ?.takeIf { it.isNotBlank() } ?: WearableService.UNIFIED_SERVER_DEFAULT_URL
    WearableService._unifiedServerUrl.value = unifiedUrl
    depthManager.setCloudUrl("$unifiedUrl/depth")
    WearableService.appendLog("🌐 Unified server: $unifiedUrl")
    restoreMqttConfig(cloudPrefs)
    telemetryReporter = com.wearmux.android.integration.consumer.TelemetryReporter(
        serviceScope, unifiedUrl, csvWriter = latencyCsvWriter, mqttPublish = mqttPublisher::publish,
    )
    telemetryReporter.start(telemetryPayloadBuilder::build)
    telemetryReporter.onDecisions = { decisions ->
        @Suppress("UNCHECKED_CAST")
        safetyDecisionFeed.onCloudDecisions(decisions as List<Map<String, Any?>>)
    }
    startGlassesPoseReporter(unifiedUrl); cloudZoneSync.startPeriodicResync()
    serviceScope.launch { scanAndConnect.candidates.collect { WearableService._bleCandidates.value = it } }
    serviceScope.launch { scanAndConnect.scanRunning.collect { WearableService._candidateScanRunning.value = it } }
    WearableService.appendLog("📡 Publishing telemetry to $unifiedUrl/telemetry")
    serviceScope.launch { atcllClient.status.collect { WearableService._atcllStatus.value = it } }
    serviceScope.launch { atcllClient.endpoint.collect { WearableService._atcllEndpoint.value = it } }
}

internal fun WearableService.bootstrapMlClassifiers(cloudPrefs: android.content.SharedPreferences) {
    onDeviceClassifier = runCatching { WearMuxOnDeviceClassifier(this) }
        .onSuccess { WearableService.appendLog("🧠  Local in-app ML active (model wearmux_model.tflite)") }
        .onFailure { WearableService.appendLog("⚠  Failed to start local ML: ${it.message}") }
        .getOrNull()
    val ep = cloudPrefs.getString(WearableService.IMU_PREF_URL, null)?.takeIf { it.isNotBlank() }
    if (ep == null) { serverClassifier = null; WearableService.appendLog("☁ ML server disabled (no endpoint configured)"); return }
    WearableService._mlServerUrl.value = ep
    serverClassifier = runCatching { WearMuxServerClassifier(ep) }
        .onSuccess { WearableService.appendLog("☁ ML server client initialized (endpoint $ep)") }
        .onFailure { WearableService.appendLog("⚠  Failed to start ML server client: ${it.message}") }
        .getOrNull()
}

/** Recalcula [WearableService.cameraStreamHealth] a cada segundo a partir dos sinais já publicados. */
internal fun WearableService.startCameraStreamHealthLoop() {
    serviceScope.launch {
        while (true) {
            val lastTs = WearableService._lastFrameTimestampMs.value
            val nowMs = System.currentTimeMillis()
            val ageMs = lastTs?.let { nowMs - it }
            val status = computeCameraStreamStatus(
                glassesState = WearableService._glassesState.value,
                frameCount = WearableService._streamFrameCount.value.toLong(),
                fps = WearableService._streamFps.value,
                lastFrameAgeMs = ageMs,
            )
            WearableService._cameraStreamHealth.value = CameraStreamHealth(
                isStreaming = WearableService._isStreaming.value,
                framesReceived = WearableService._streamFrameCount.value.toLong(),
                fps = WearableService._streamFps.value,
                lastFrameTimestampMs = lastTs,
                lastFrameAgeMs = ageMs,
                status = status,
            )
            delay(1000L)
        }
    }
}

/** Observa a sessão ativa dos óculos e expõe o endereço/identificador estável para a UI. */
internal fun WearableService.startGlassesConnectedAddressObserver() {
    serviceScope.launch {
        deviceHub.sessions.collect { sessions ->
            val session = sessions.values.firstOrNull { it.adapterId == OmiGlassesWearableAdapter.ADAPTER_ID }
            WearableService._glassesConnectedAddress.value = session?.id?.raw
        }
    }
}

internal fun WearableService.resetGlassesMicState() {
    WearableService._glassesMicStreaming.value = false
    WearableService._glassesMicStatusText.value = "IDLE"
    WearableService._glassesMicDataText.value = "No data"
    WearableService._glassesMicSampleRateHz.value = 16_000
    WearableService._glassesMicBitDepth.value = 8
    WearableService._glassesMicPlaybackSupported.value = true
    glassesMicFrameCount = 0L; glassesMicLastUiUpdateMs = 0L
    WearableService._audioRecordingActive.value = false
    audioRecordPcm.reset(); audioRecordSamples = 0L; audioRecordSampleRate = 16_000
}

/** Repõe as definições do broker MQTT guardadas na sessão anterior. */
internal fun WearableService.restoreMqttConfig(cloudPrefs: android.content.SharedPreferences) {
    val broker = cloudPrefs.getString(WearableService.MQTT_PREF_BROKER, null).orEmpty()
    val enabled = cloudPrefs.getBoolean(WearableService.MQTT_PREF_ENABLED, false)
    val prefix = cloudPrefs.getString(WearableService.MQTT_PREF_TOPIC_PREFIX, null)
        ?.takeIf { it.isNotBlank() } ?: MqttConfig.DEFAULT_TOPIC_PREFIX
    WearableService._mqttConfig.value = MqttConfig(enabled = enabled, brokerUrl = broker, topicPrefix = prefix)
    if (enabled && broker.isNotBlank()) {
        WearableService.appendLog("MQTT enabled: $broker (prefix $prefix)")
    }
}
