package com.example.peciwearables.integration.sensors

import com.example.peciwearables.integration.pdr.PdrPosition
import com.example.peciwearables.integration.pdr.PedestrianDeadReckoning
import com.example.peciwearables.integration.pdr.RouteManager
import com.example.peciwearables.integration.pdr.SavedRoute
import com.example.peciwearables.integration.safety.CyclistModeDetector
import com.example.peciwearables.integration.safety.haversineMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Liga `PhoneSensorManager`, `PedestrianDeadReckoning`, `RouteManager` e
 * `CyclistModeDetector` aos mirrors UI no companion do `WearableService`.
 * Encapsula ~85 linhas de wiring que viviam em 4 funções inline no service.
 */
class PhoneSensorsAndPdrWiring(
    private val scope: CoroutineScope,
    private val phoneSensors: PhoneSensorManager,
    private val pdr: PedestrianDeadReckoning,
    private val routeManager: RouteManager,
    private val cyclistDetector: CyclistModeDetector,
    private val coAxialImuFusion: CoAxialImuFusion,
    private val phoneAccel: MutableStateFlow<PhoneAccelSample?>,
    private val phoneGps: MutableStateFlow<PhoneGpsLocation?>,
    private val pdrPosition: MutableStateFlow<PdrPosition?>,
    private val pdrStepCount: MutableStateFlow<Int>,
    private val savedRoutes: MutableStateFlow<List<SavedRoute>>,
    private val activeRoute: MutableStateFlow<SavedRoute?>,
    private val routeRecording: MutableStateFlow<Boolean>,
    private val routeRecordingWaypointCount: MutableStateFlow<Int>,
    private val routeRecordingWaypoints: MutableStateFlow<List<Pair<Double, Double>>>,
    private val latestImuSamples: StateFlow<List<com.example.peciwearables.integration.protocol.ImuSample>>,
    private val cyclistMode: MutableStateFlow<CyclistModeDetector.Mode>,
    private val imuSampleTotalCounter: AtomicLong,
    private val routeImuOnly: () -> Boolean,
    private val waitingForAnchorFix: () -> Boolean,
    private val onAnchor: (Double, Double) -> Unit,
    private val uc1_4Enabled: () -> Boolean,
) {
    fun start() {
        phoneSensors.onAccelSample = { sample ->
            phoneAccel.value = sample
            pdr.onAccelerometer(sample)
            coAxialImuFusion.feedPhoneAccel(sample.x, sample.y, sample.z)
        }
        phoneSensors.onGyroSample = { values, ts -> pdr.onGyroscope(values[2], ts) }
        phoneSensors.onGpsUpdate = { gps ->
            phoneGps.value = gps
            if (!routeImuOnly()) {
                pdr.onGpsUpdate(gps)
                if (routeManager.isRecording.value && waitingForAnchorFix()) onAnchor(gps.latitude, gps.longitude)
            }
        }
        scope.launch {
            pdr.position.collect { pos ->
                pdrPosition.value = pos
                if (!routeImuOnly() || !routeManager.isRecording.value || pos == null) return@collect
                val last = routeManager.recordingWaypoints.value.lastOrNull()
                val dist = if (last != null) haversineMeters(last.latitude, last.longitude, pos.latitude, pos.longitude) else Double.MAX_VALUE
                if (dist >= 0.5) {
                    routeManager.addWaypoint(pos.latitude, pos.longitude); routeRecordingWaypointCount.value++
                }
            }
        }
        scope.launch { pdr.stepCount.collect { pdrStepCount.value = it } }
        scope.launch { routeManager.routes.collect { savedRoutes.value = it } }
        scope.launch {
            routeManager.activeRoute.collect { route ->
                activeRoute.value = route
                pdr.setRoute(route?.waypoints?.map { it.latitude to it.longitude })
            }
        }
        scope.launch { routeManager.isRecording.collect { routeRecording.value = it } }
        scope.launch {
            routeManager.recordingWaypoints.collect { wps -> routeRecordingWaypoints.value = wps.map { it.latitude to it.longitude } }
        }
        scope.launch { phoneGps.collect { gps -> if (gps != null) cyclistDetector.onGps(gps) } }
        scope.launch {
            latestImuSamples.collect { samples ->
                samples.forEach { cyclistDetector.onImu(it) }
                imuSampleTotalCounter.addAndGet(samples.size.toLong())
            }
        }
        scope.launch {
            while (true) {
                if (uc1_4Enabled()) cyclistMode.value = cyclistDetector.currentMode()
                delay(1_000)
            }
        }
    }
}
