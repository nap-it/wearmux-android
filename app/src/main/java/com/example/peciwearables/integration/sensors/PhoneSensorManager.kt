package com.example.peciwearables.integration.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Dados de acelerómetro do telemóvel (sem gravidade).
 */
data class PhoneAccelSample(
    val x: Float,   // m/s²
    val y: Float,
    val z: Float,
    val timestampNs: Long
)

/**
 * Dados GPS do telemóvel.
 */
data class PhoneGpsLocation(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,       // metros
    val speed: Float,          // m/s
    val bearing: Float,        // graus
    val timestampMs: Long
)

/**
 * Gere sensores do telemóvel: GPS (FusedLocationProvider) + Acelerómetro linear.
 */
class PhoneSensorManager(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "PhoneSensor"
        private const val GPS_INTERVAL_MS = 1000L          // 1 Hz
        private const val GPS_FASTEST_INTERVAL_MS = 500L
        private const val ACCEL_SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME  // ~50Hz
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    // Acelerómetro linear (sem gravidade)
    private val linearAccelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    // Giroscópio do telemóvel (para complementar o dos óculos se necessário)
    private val gyroSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // --- StateFlows observáveis ---
    private val _latestAccel = MutableStateFlow(PhoneAccelSample(0f, 0f, 0f, 0L))
    val latestAccel: StateFlow<PhoneAccelSample> = _latestAccel

    private val _latestGps = MutableStateFlow<PhoneGpsLocation?>(null)
    val latestGps: StateFlow<PhoneGpsLocation?> = _latestGps

    private val _latestGyro = MutableStateFlow(floatArrayOf(0f, 0f, 0f))
    val latestGyro: StateFlow<FloatArray> = _latestGyro

    private val _gpsActive = MutableStateFlow(false)
    val gpsActive: StateFlow<Boolean> = _gpsActive

    private val _accelActive = MutableStateFlow(false)
    val accelActive: StateFlow<Boolean> = _accelActive

    // Callbacks para quem quiser amostras raw (ex: PDR pipeline)
    var onAccelSample: ((PhoneAccelSample) -> Unit)? = null
    var onGpsUpdate: ((PhoneGpsLocation) -> Unit)? = null
    var onGyroSample: ((FloatArray, Long) -> Unit)? = null

    private var locationCallback: LocationCallback? = null

    // ── Acelerómetro + Giroscópio ────────────────────────────────────────────

    fun startAccelerometer() {
        if (_accelActive.value) return
        linearAccelSensor?.let {
            sensorManager.registerListener(this, it, ACCEL_SENSOR_DELAY)
            _accelActive.value = true
            Log.d(TAG, "Accelerometer started")
        } ?: Log.w(TAG, "LINEAR_ACCELERATION sensor not available")

        gyroSensor?.let {
            sensorManager.registerListener(this, it, ACCEL_SENSOR_DELAY)
        }
    }

    fun stopAccelerometer() {
        sensorManager.unregisterListener(this)
        _accelActive.value = false
        Log.d(TAG, "Accelerometer stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val sample = PhoneAccelSample(
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    timestampNs = event.timestamp
                )
                _latestAccel.value = sample
                onAccelSample?.invoke(sample)
            }
            Sensor.TYPE_GYROSCOPE -> {
                _latestGyro.value = event.values.copyOf()
                onGyroSample?.invoke(event.values.copyOf(), event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── GPS ──────────────────────────────────────────────────────────────────

    fun startGps() {
        if (_gpsActive.value) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "GPS permission not granted")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
            .setMinUpdateIntervalMillis(GPS_FASTEST_INTERVAL_MS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val gps = PhoneGpsLocation(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitude = loc.altitude,
                    accuracy = loc.accuracy,
                    speed = loc.speed,
                    bearing = loc.bearing,
                    timestampMs = loc.time
                )
                _latestGps.value = gps
                onGpsUpdate?.invoke(gps)
            }
        }

        locationCallback = callback
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        _gpsActive.value = true
        Log.d(TAG, "GPS started (${GPS_INTERVAL_MS}ms interval)")
    }

    fun stopGps() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        _gpsActive.value = false
        Log.d(TAG, "GPS stopped")
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun startAll() {
        startAccelerometer()
        startGps()
    }

    fun stopAll() {
        stopAccelerometer()
        stopGps()
    }
}
