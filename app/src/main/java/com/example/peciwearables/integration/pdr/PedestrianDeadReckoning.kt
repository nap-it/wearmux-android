package com.example.peciwearables.integration.pdr

import android.util.Log
import com.example.peciwearables.integration.sensors.PhoneAccelSample
import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import com.example.peciwearables.integration.protocol.GlassesImuSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

/**
 * Posição estimada via PDR.
 */
data class PdrPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,           // metros estimados
    val source: String,            // "gps", "pdr", "fused"
    val heading: Double,           // graus (0=Norte, 90=Este)
    val speed: Float,              // m/s estimada
    val timestampMs: Long
)


class PedestrianDeadReckoning {

    companion object {
        private const val TAG = "PDR"
        private const val EARTH_RADIUS = 6_371_000.0  // metros
        private const val GRAVITY = 9.81f             // m/s²

        
        private const val STEP_THRESHOLD = 2.0f        // m/s² acima da gravidade
        private const val STEP_MIN_INTERVAL_MS = 300L  // mínimo entre passos (~3 passos/s max)

        // Weinberg step length: L = k * (aMax - aMin)^0.25
        private const val WEINBERG_K = 0.5f
    }

    // --- Estado ---
    private val _position = MutableStateFlow<PdrPosition?>(null)
    val position: StateFlow<PdrPosition?> = _position

    private val _stepCount = MutableStateFlow(0)
    val stepCount: StateFlow<Int> = _stepCount

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    // Kalman state: [lat, lon, vN, vE]
    private var state = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    private var P = Array(4) { i -> DoubleArray(4) { j -> if (i == j) 100.0 else 0.0 } } // covariance
    private var initialized = false

    // Step detection state
    private var lastStepTimeMs = 0L
    private var accelWindow = mutableListOf<Float>()  // magnitude window
    private var accelMin = Float.MAX_VALUE
    private var accelMax = Float.MIN_VALUE

    // Heading (integração do giroscópio)
    private var heading = 0.0  // radianos
    private var lastGyroTimestampNs = 0L

    // Route constraint (opcional — ideia do Clérigo)
    private var routeWaypoints: List<Pair<Double, Double>>? = null

    fun start() {
        _isActive.value = true
        Log.d(TAG, "PDR started")
    }

    fun stop() {
        _isActive.value = false
        Log.d(TAG, "PDR stopped. Steps: ${_stepCount.value}")
    }

    @Synchronized
    fun reset() {
        initialized = false
        state = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        P = Array(4) { i -> DoubleArray(4) { j -> if (i == j) 100.0 else 0.0 } }
        _stepCount.value = 0
        accelWindow.clear()
        accelMin = Float.MAX_VALUE
        accelMax = Float.MIN_VALUE
        heading = 0.0
        lastGyroTimestampNs = 0L
    }


    fun setRoute(waypoints: List<Pair<Double, Double>>?) {
        routeWaypoints = waypoints
        Log.d(TAG, "Route set: ${waypoints?.size ?: 0} waypoints")
    }

    fun onGlassesImu(sample: GlassesImuSample) {
        if (!_isActive.value) return
        // Usar acelerómetro dos óculos para deteção de passos
        val accelSample = PhoneAccelSample(sample.ax, sample.ay, sample.az, System.nanoTime())
        onAccelerometer(accelSample)
        // Usar giroscópio dos óculos para heading (eixo Z = yaw)
        onGyroscope(sample.gz, System.nanoTime())
    }


    @Synchronized
    fun onAccelerometer(sample: PhoneAccelSample) {
        if (!_isActive.value) return

       
        val magnitude = sqrt(sample.x * sample.x + sample.y * sample.y + sample.z * sample.z)
        val deviation = magnitude - GRAVITY
        accelWindow.add(deviation)


        if (magnitude < accelMin) accelMin = magnitude
        if (magnitude > accelMax) accelMax = magnitude

        // Window de ~0.5s a 50Hz = 25 samples
        if (accelWindow.size > 25) accelWindow.removeAt(0)

        // Peak detection sobre o desvio à gravidade.
        val now = sample.timestampNs / 1_000_000  // ms
        if (deviation > STEP_THRESHOLD &&
            (now - lastStepTimeMs) > STEP_MIN_INTERVAL_MS &&
            isPeak(accelWindow)
        ) {
            onStepDetected(now)
        }
    }

    /**
     * Feed de giroscópio — integração do heading.
     */
    @Synchronized
    fun onGyroscope(gyroZ: Float, timestampNs: Long) {
        if (!_isActive.value) return
        if (lastGyroTimestampNs == 0L) {
            lastGyroTimestampNs = timestampNs
            return
        }

        val dtSec = (timestampNs - lastGyroTimestampNs) / 1_000_000_000.0
        lastGyroTimestampNs = timestampNs

        // Integração angular (yaw = eixo Z)
        heading += gyroZ * dtSec
        // Normalizar para [0, 2π]
        heading = ((heading % (2 * PI)) + 2 * PI) % (2 * PI)
    }

    /**
     * Feed de GPS — update do Kalman filter.
     */
    @Synchronized
    fun onGpsUpdate(gps: PhoneGpsLocation) {
        if (!_isActive.value) return

        if (!initialized) {
            // Inicializar estado com primeiro fix GPS
            state[0] = gps.latitude
            state[1] = gps.longitude
            state[2] = 0.0  // vN
            state[3] = 0.0  // vE
            P = Array(4) { i -> DoubleArray(4) { j ->
                if (i == j) (gps.accuracy * gps.accuracy).toDouble() else 0.0
            }}
            initialized = true
            heading = Math.toRadians(gps.bearing.toDouble())
            updatePosition("gps", gps.accuracy, gps.timestampMs)
            return
        }

        // Kalman update com GPS
        kalmanGpsUpdate(gps)
        updatePosition("fused", gps.accuracy * 0.7f, gps.timestampMs)
    }

    // ── Step Detection + PDR Prediction ──────────────────────────────────────

    private fun onStepDetected(timestampMs: Long) {
        _stepCount.value++
        lastStepTimeMs = timestampMs

        
        val rawStep = WEINBERG_K * (accelMax - accelMin).toDouble().pow(0.25)
        val stepLength = rawStep.coerceIn(0.3, 1.2)
        accelMin = Float.MAX_VALUE
        accelMax = Float.MIN_VALUE

        if (!initialized) return

        // Deslocamento em metros
        val dN = stepLength * cos(heading)  // norte
        val dE = stepLength * sin(heading)  // este

        // Converter deslocamento em metros para delta lat/lon
        val dLat = dN / EARTH_RADIUS * (180.0 / PI)
        val dLon = dE / (EARTH_RADIUS * cos(Math.toRadians(state[0]))) * (180.0 / PI)

        // Kalman prediction step
        state[0] += dLat
        state[1] += dLon

        // Aumentar incerteza (process noise)
        val Q = 0.5 // metros de incerteza por passo
        P[0][0] += (Q / EARTH_RADIUS * 180.0 / PI).pow(2)
        P[1][1] += (Q / EARTH_RADIUS * 180.0 / PI).pow(2)

        // Map-matching ao trajeto (se definido)
        routeWaypoints?.let { route ->
            if (route.size >= 2) {
                val snapped = snapToRoute(state[0], state[1], route)
                // Blend: 70% PDR + 30% snap (suave)
                state[0] = state[0] * 0.7 + snapped.first * 0.3
                state[1] = state[1] * 0.7 + snapped.second * 0.3
            }
        }

        updatePosition("pdr", estimateAccuracy().toFloat(), timestampMs)
    }

    // ── Kalman Filter ────────────────────────────────────────────────────────

    private fun kalmanGpsUpdate(gps: PhoneGpsLocation) {


        val accDeg = gps.accuracy / EARTH_RADIUS * (180.0 / PI)
        val R = accDeg * accDeg

        // Kalman gain para lat
        val S0 = P[0][0] + R
        val K0 = if (S0 > 0) P[0][0] / S0 else 0.0

        // Kalman gain para lon
        val S1 = P[1][1] + R
        val K1 = if (S1 > 0) P[1][1] / S1 else 0.0

        // Innovation
        val innov0 = gps.latitude - state[0]
        val innov1 = gps.longitude - state[1]

        // Update state
        state[0] += K0 * innov0
        state[1] += K1 * innov1

        // Update covariance
        P[0][0] *= (1 - K0)
        P[1][1] *= (1 - K1)

        
        if (gps.speed > 0.5f) {
            heading = Math.toRadians(gps.bearing.toDouble())
        }
    }

    private fun estimateAccuracy(): Double {
        return sqrt(P[0][0] + P[1][1]) * EARTH_RADIUS * PI / 180.0
    }

    private fun updatePosition(source: String, accuracy: Float, timestampMs: Long) {
        _position.value = PdrPosition(
            latitude = state[0],
            longitude = state[1],
            accuracy = accuracy,
            source = source,
            heading = Math.toDegrees(heading),
            speed = 0f, // TODO: estimar via cadência de passos
            timestampMs = timestampMs
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun isPeak(window: List<Float>): Boolean {
        if (window.size < 3) return false
        val last = window.size - 1
        return window[last - 1] > window[last - 2] && window[last - 1] > window[last]
    }


    private fun snapToRoute(
        lat: Double, lon: Double,
        waypoints: List<Pair<Double, Double>>
    ): Pair<Double, Double> {
        var bestDist = Double.MAX_VALUE
        var bestPoint = Pair(lat, lon)

        for (i in 0 until waypoints.size - 1) {
            val (aLat, aLon) = waypoints[i]
            val (bLat, bLon) = waypoints[i + 1]
            val snapped = projectPointOnSegment(lat, lon, aLat, aLon, bLat, bLon)
            val dist = haversine(lat, lon, snapped.first, snapped.second)
            if (dist < bestDist) {
                bestDist = dist
                bestPoint = snapped
            }
        }

        return bestPoint
    }

    private fun projectPointOnSegment(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Pair<Double, Double> {
        val apLat = pLat - aLat; val apLon = pLon - aLon
        val abLat = bLat - aLat; val abLon = bLon - aLon
        val ab2 = abLat * abLat + abLon * abLon
        if (ab2 < 1e-12) return Pair(aLat, aLon)
        val t = ((apLat * abLat + apLon * abLon) / ab2).coerceIn(0.0, 1.0)
        return Pair(aLat + t * abLat, aLon + t * abLon)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS * asin(sqrt(a))
    }
}
