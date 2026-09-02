package com.example.peciwearables.integration

import com.example.peciwearables.integration.atcll.AtcllClient
import com.example.peciwearables.integration.image.BleCameraPipeline
import com.example.peciwearables.integration.managers.GlassesCameraManager
import com.example.peciwearables.integration.managers.GlassesMicrophoneManager
import com.example.peciwearables.integration.ml.PeciOnDeviceClassifier
import com.example.peciwearables.integration.ml.PeciServerClassifier
import com.example.peciwearables.integration.safety.CrossingZoneManager
import com.example.peciwearables.integration.safety.CrossingZoneStore
import com.example.peciwearables.integration.safety.SafetyDiagnostics
import com.example.peciwearables.integration.safety.SafetyGates
import com.example.peciwearables.integration.safety.SafetyOrchestrator
import com.example.peciwearables.integration.safety.SafetyOutputs
import com.example.peciwearables.integration.watch.WatchClient
import com.example.peciwearables.integration.wearable.DefaultWearableConnectionCoordinator
import com.example.peciwearables.integration.wearable.DefaultWearableHub
import com.example.peciwearables.integration.ble.devices.brilliantsole.defaultBrilliantSoleClientFactory
import com.example.peciwearables.integration.ble.devices.omi.defaultOmiGlassesClientFactory
import com.example.peciwearables.integration.wearable.devices.brilliantsole.BrilliantSoleWristbandWearableAdapter
import com.example.peciwearables.integration.wearable.devices.omi.OmiGlassesWearableAdapter
import com.example.peciwearables.integration.wearable.standardWearableAdapterRegistry
import com.example.peciwearables.integration.image.camera.CameraStreamHealth
import com.example.peciwearables.integration.image.camera.computeCameraStreamStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun WearableService.bootstrapAdaptersAndHub() {
    glassesWearableAdapter = OmiGlassesWearableAdapter(defaultOmiGlassesClientFactory(this), wearableRealtimeSink)
    wristbandWearableAdapter = BrilliantSoleWristbandWearableAdapter(defaultBrilliantSoleClientFactory(this), wearableRealtimeSink)
    val esp32 = com.example.peciwearables.integration.wearable.devices.esp32.Esp32WearableAdapter(realtimeSink = wearableRealtimeSink)
    watchClient = com.example.peciwearables.integration.watch.WatchClient(this).also {
        it.csvWriter = latencyCsvWriter
    }
    val galaxyWatch = com.example.peciwearables.integration.wearable.devices.galaxywatch.GalaxyWatchWearableAdapter(watchClient)
    wearableHub = DefaultWearableHub(
        DefaultWearableConnectionCoordinator(standardWearableAdapterRegistry(glassesWearableAdapter, wristbandWearableAdapter, esp32, galaxyWatch)),
        serviceScope,
    )
    WearableService._glassesWifiSupported.value = false
    WearableService._glassesWifiConnectionEnabled.value = null
    WearableService._glassesWifiConnected.value = null
    WearableService._glassesWifiSecure.value = null
}

internal fun WearableService.bootstrapCameraAndMicManagers() {
    val onJpeg: (ByteArray) -> Unit = { jpeg -> latestCameraJpeg = jpeg; glassesBleClient.onSdkStreamImageAssembled() }
    bleCameraPipeline = BleCameraPipeline(metrics = cameraMetrics).apply {
        onLog = WearableService::appendLog; onJpegReady = onJpeg
    }
    glassesCameraManager = GlassesCameraManager(
        cameraPipeline = bleCameraPipeline,
        onBitmapReady = { bitmap ->
            photoCaptureInFlight = false; photoCaptureTimeoutJob?.cancel()
            publishCapturedPhoto(bitmap); updateWristbandTrafficProfile("foto recebida")
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
            val candidate = com.example.peciwearables.integration.wearable.devices.galaxywatch
                .GalaxyWatchWearableAdapter.syntheticCandidate(name)
            runCatching { wearableHub.connectByCandidate(candidate) }
        }
    }
    com.example.peciwearables.integration.watch.WatchClientObservers(
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
        heartbeat = object : com.example.peciwearables.integration.watch.WatchClientObservers.HeartbeatProvider {
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
    val safetyOutputs = SafetyOutputs(
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
    safetyOrchestrator = SafetyOrchestrator(serviceScope, safetyDecisionFeed, safetyOutputs, SafetyGates(safetyToggles), ::mirrorDecisionForUi)
    safetyOrchestrator.start()
}

internal fun WearableService.bootstrapAtcllAndTelemetry(cloudPrefs: android.content.SharedPreferences) {
    atcllClient = AtcllClient(serviceScope)
    cloudPrefs.getString(WearableService.ATCLL_PREF_URL, null)?.takeIf { it.isNotBlank() }?.let {
        atcllClient.setEndpoint(it); WearableService.appendLog("🌐 ATCLL endpoint restaurado: $it")
    }
    val unifiedUrl = cloudPrefs.getString(WearableService.UNIFIED_SERVER_PREF_URL, WearableService.UNIFIED_SERVER_DEFAULT_URL)
        ?.takeIf { it.isNotBlank() } ?: WearableService.UNIFIED_SERVER_DEFAULT_URL
    WearableService._unifiedServerUrl.value = unifiedUrl
    depthManager.setCloudUrl("$unifiedUrl/depth")
    WearableService.appendLog("🌐 Unified server: $unifiedUrl")
    telemetryReporter = com.example.peciwearables.integration.telemetry.TelemetryReporter(serviceScope, unifiedUrl, csvWriter = latencyCsvWriter)
    telemetryReporter.start(telemetryPayloadBuilder::build)
    telemetryReporter.onDecisions = { decisions ->
        @Suppress("UNCHECKED_CAST")
        safetyDecisionFeed.onCloudDecisions(decisions as List<Map<String, Any?>>)
    }
    startGlassesPoseReporter(unifiedUrl); cloudZoneSync.startPeriodicResync()
    serviceScope.launch { scanAndConnect.candidates.collect { WearableService._bleCandidates.value = it } }
    serviceScope.launch { scanAndConnect.scanRunning.collect { WearableService._candidateScanRunning.value = it } }
    WearableService.appendLog("📡 Telemetry a publicar em $unifiedUrl/telemetry")
    serviceScope.launch { atcllClient.status.collect { WearableService._atcllStatus.value = it } }
    serviceScope.launch { atcllClient.endpoint.collect { WearableService._atcllEndpoint.value = it } }
}

internal fun WearableService.bootstrapMlClassifiers(cloudPrefs: android.content.SharedPreferences) {
    onDeviceClassifier = runCatching { PeciOnDeviceClassifier(this) }
        .onSuccess { WearableService.appendLog("🧠  ML local na app ativo (modelo peci_model.tflite)") }
        .onFailure { WearableService.appendLog("⚠  Falha ao iniciar ML local: ${it.message}") }
        .getOrNull()
    val ep = cloudPrefs.getString(WearableService.IMU_PREF_URL, null)?.takeIf { it.isNotBlank() }
    if (ep == null) { serverClassifier = null; WearableService.appendLog("☁ ML server desativado (sem endpoint configurado)"); return }
    WearableService._mlServerUrl.value = ep
    serverClassifier = runCatching { PeciServerClassifier(ep) }
        .onSuccess { WearableService.appendLog("☁ ML server client inicializado (endpoint $ep)") }
        .onFailure { WearableService.appendLog("⚠  Falha ao iniciar cliente ML server: ${it.message}") }
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
        wearableHub.sessions.collect { sessions ->
            val session = sessions.values.firstOrNull { it.adapterId == OmiGlassesWearableAdapter.ADAPTER_ID }
            WearableService._glassesConnectedAddress.value = session?.id?.raw
        }
    }
}

internal fun WearableService.resetGlassesMicState() {
    WearableService._glassesMicStreaming.value = false
    WearableService._glassesMicStatusText.value = "IDLE"
    WearableService._glassesMicDataText.value = "Sem dados"
    WearableService._glassesMicSampleRateHz.value = 16_000
    WearableService._glassesMicBitDepth.value = 8
    WearableService._glassesMicPlaybackSupported.value = true
    glassesMicFrameCount = 0L; glassesMicLastUiUpdateMs = 0L
    WearableService._audioRecordingActive.value = false
    audioRecordPcm.reset(); audioRecordSamples = 0L; audioRecordSampleRate = 16_000
}
