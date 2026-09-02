package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


class CrossingZoneManager(
    private val scope: CoroutineScope,
    private val store: CrossingZoneStore,
    private val fetcher: OsmCrossingFetcher = OsmCrossingFetcher(),
    private val refreshThresholdMeters: Double = 200.0,
    private val osmRadiusMeters: Int = 500,
) {

    private val _osmZones = MutableStateFlow<List<CrossingZone>>(emptyList())
    val osmZones: StateFlow<List<CrossingZone>> = _osmZones

    private val _osmStatus = MutableStateFlow(OsmStatus.IDLE)
    val osmStatus: StateFlow<OsmStatus> = _osmStatus

    /** União das duas fontes — é o que o orchestrator deve consumir. */
    private val _zones = MutableStateFlow<List<CrossingZone>>(emptyList())
    val zones: StateFlow<List<CrossingZone>> = _zones

    private val refreshMutex = Mutex()
    private var lastFetchLat: Double? = null
    private var lastFetchLon: Double? = null
    private var autoFetchJob: Job? = null

    enum class OsmStatus { IDLE, FETCHING, OK, ERROR, OFFLINE }

    init {
        // Sempre que manuais ou OSM mudam, recombina.
        scope.launch {
            store.zones.combine(_osmZones) { manual, osm -> manual + osm }
                .onEach { _zones.value = it }
                .launchIn(scope)
        }
    }

    /**
     * Liga o auto-refresh ao GPS — quando o utilizador se afasta do último
     * fetch, busca passadeiras novas. O caller passa o GPS flow.
     */
    fun attachToGps(gpsFlow: StateFlow<PhoneGpsLocation?>) {
        autoFetchJob?.cancel()
        autoFetchJob = scope.launch {
            gpsFlow.collect { gps ->
                if (gps == null) return@collect
                val needsRefresh = lastFetchLat == null ||
                    haversineMeters(lastFetchLat!!, lastFetchLon!!, gps.latitude, gps.longitude) >
                    refreshThresholdMeters
                if (needsRefresh) refresh(gps)
            }
        }
    }

    /** Refresh forçado a partir do GPS actual. */
    suspend fun refresh(gps: PhoneGpsLocation) {
        if (refreshMutex.isLocked) return
        refreshMutex.withLock {
            _osmStatus.value = OsmStatus.FETCHING
            val zones = try {
                fetcher.fetchCrossings(gps, radiusMeters = osmRadiusMeters)
            } catch (e: Exception) {
                _osmStatus.value = OsmStatus.ERROR
                return@withLock
            }
            if (zones.isEmpty()) {
                _osmStatus.value = OsmStatus.OFFLINE
                // Mantém zonas anteriores se servidor falhou — não estoura UX.
            } else {
                _osmZones.value = zones
                _osmStatus.value = OsmStatus.OK
                lastFetchLat = gps.latitude
                lastFetchLon = gps.longitude
            }
        }
    }

    fun cancel() {
        autoFetchJob?.cancel()
        autoFetchJob = null
    }
}
