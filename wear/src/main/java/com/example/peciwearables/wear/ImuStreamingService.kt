package com.example.peciwearables.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong


class ImuStreamingService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "ImuStreamingService"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "peci_imu_stream"

        const val ACTION_START = "com.example.peciwearables.wear.IMU_START"
        const val ACTION_STOP = "com.example.peciwearables.wear.IMU_STOP"
        const val ACTION_SET_RATE = "com.example.peciwearables.wear.IMU_SET_RATE"
        const val EXTRA_SAMPLE_RATE_HZ = "sample_rate_hz"

        // ---- Estado observável (consumido pelo MainActivity Composable) ----
        private val _isStreaming = MutableStateFlow(false)
        val isStreaming: StateFlow<Boolean> = _isStreaming
        private val _sampleRateHz = MutableStateFlow(100)
        val sampleRateHz: StateFlow<Int> = _sampleRateHz
        private val _batteryPct = MutableStateFlow(0)
        val batteryPct: StateFlow<Int> = _batteryPct
        private val _heartRateBpm = MutableStateFlow(0)
        val heartRateBpm: StateFlow<Int> = _heartRateBpm
        private val _sentSampleCount = MutableStateFlow(0L)
        val sentSampleCount: StateFlow<Long> = _sentSampleCount
        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError

        private const val CHUNK_SIZES = 16
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
    }

    // I/O-bound: ChannelClient.write é bloqueante, MessageClient.sendMessage
    // espera ack do BT/Cloud — Dispatchers.IO é o pool certo para isto.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    private lateinit var sensorManager: SensorManager
    private lateinit var channelClient: ChannelClient
    private lateinit var messageClient: MessageClient
    private lateinit var capabilityClient: CapabilityClient

    private var phoneNodeId: String? = null
    private var channel: ChannelClient.Channel? = null
    private var output: OutputStream? = null

    // Buffer de chunk: 16 amostras × 32B
    private val chunkBuffer = ByteBuffer.allocate(CHUNK_SIZES * WatchProtocol.IMU_SAMPLE_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
    private var chunkCount = 0
    private val sentSamples = AtomicLong(0)

    // Última amostra de cada sensor — assemblamos amostras "completas" no
    // tick do giroscópio (taxa-mestre), tal como a NavisensWebView faz no app.
    @Volatile private var latestAxMg: Short = 0
    @Volatile private var latestAyMg: Short = 0
    @Volatile private var latestAzMg: Short = 1000  // ~1g em repouso
    @Volatile private var latestMxX10: Short = 0
    @Volatile private var latestMyX10: Short = 0
    @Volatile private var latestMzX10: Short = 0

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        channelClient = Wearable.getChannelClient(this)
        messageClient = Wearable.getMessageClient(this)
        capabilityClient = Wearable.getCapabilityClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val requestedRate = intent.getIntExtra(EXTRA_SAMPLE_RATE_HZ, _sampleRateHz.value).coerceIn(20, 200)
                _sampleRateHz.value = requestedRate
                startStreaming()
            }
            ACTION_STOP -> {
                stopStreaming()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SET_RATE -> {
                val rate = intent.getIntExtra(EXTRA_SAMPLE_RATE_HZ, 100).coerceIn(20, 200)
                _sampleRateHz.value = rate
                if (_isStreaming.value) {
                    // Re-registar listener com a nova taxa.
                    unregisterSensors()
                    registerSensors()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startStreaming() {
        if (_isStreaming.value) return
        startForeground(NOTIFICATION_ID, buildNotification())
        _isStreaming.value = true
        _lastError.value = null
        sentSamples.set(0)
        _sentSampleCount.value = 0
        _batteryPct.value = readBatteryPct()

        scope.launch {
            try {
                val nodeId = resolvePhoneNode() ?: run {
                    reportError("Telemóvel não encontrado (sem nó com capability ${WatchProtocol.CAPABILITY_PHONE})")
                    stopStreamingInternal()
                    return@launch
                }
                phoneNodeId = nodeId

                val ch = channelClient.openChannel(nodeId, WatchProtocol.CHANNEL_IMU).awaitOrNull()
                    ?: run {
                        reportError("Falha a abrir canal IMU")
                        stopStreamingInternal()
                        return@launch
                    }
                channel = ch
                output = channelClient.getOutputStream(ch).awaitOrNull() ?: run {
                    reportError("Falha a obter OutputStream")
                    stopStreamingInternal()
                    return@launch
                }

                // Cabeçalho.
                val header = ByteBuffer.allocate(WatchProtocol.IMU_HEADER_SIZE)
                    .order(ByteOrder.LITTLE_ENDIAN)
                WatchProtocol.encodeHeader(
                    header,
                    sensorMask = WatchProtocol.SENSOR_MASK_ACCEL or
                        WatchProtocol.SENSOR_MASK_GYRO or
                        WatchProtocol.SENSOR_MASK_MAG,
                    sampleRateHz = _sampleRateHz.value,
                )
                output?.write(header.array())
                output?.flush()

                registerSensors()
                startHeartbeat()
                notifyImuState(true)
            } catch (e: Exception) {
                Log.e(TAG, "startStreaming falhou", e)
                reportError(e.message ?: e.javaClass.simpleName)
                stopStreamingInternal()
            }
        }
    }

    private fun stopStreaming() {
        scope.launch { stopStreamingInternal() }
    }

    private suspend fun stopStreamingInternal() {
        _isStreaming.value = false
        unregisterSensors()
        heartbeatJob?.cancel()
        heartbeatJob = null
        try { output?.close() } catch (_: Exception) {}
        output = null
        try { channel?.let { channelClient.close(it).awaitOrNull() } } catch (_: Exception) {}
        channel = null
        notifyImuState(false)
    }

    private fun registerSensors() {
        // SensorManager.SENSOR_DELAY_GAME ≈ 50 Hz; para 100 Hz usamos
        // microsegundos directos (1_000_000 / rate).
        val periodUs = 1_000_000 / _sampleRateHz.value.coerceAtLeast(1)

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, periodUs)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, periodUs)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, periodUs)
        }
        // Heart rate é amostrado a ~1 Hz pelo Galaxy Watch — pedimos
        // SENSOR_DELAY_NORMAL para o sensor controlar a cadência ele próprio.
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // m/s² → mg : valor / 9.80665 * 1000
                latestAxMg = (event.values[0] * 101.97f).toInt().toShort()
                latestAyMg = (event.values[1] * 101.97f).toInt().toShort()
                latestAzMg = (event.values[2] * 101.97f).toInt().toShort()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                // µT → µT × 10
                latestMxX10 = (event.values[0] * 10f).toInt().toShort()
                latestMyX10 = (event.values[1] * 10f).toInt().toShort()
                latestMzX10 = (event.values[2] * 10f).toInt().toShort()
            }
            Sensor.TYPE_GYROSCOPE -> {
                // rad/s → mdps : valor * (180/π) * 1000
                val radToMdps = 57295.78f
                val gx = (event.values[0] * radToMdps).toInt().toShort()
                val gy = (event.values[1] * radToMdps).toInt().toShort()
                val gz = (event.values[2] * radToMdps).toInt().toShort()
                emitSample(
                    timestampUs = SystemClock.elapsedRealtimeNanos() / 1000,
                    gx = gx, gy = gy, gz = gz,
                )
            }
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.firstOrNull()?.toInt() ?: return
                if (bpm in 30..220) sendHeartRate(bpm)
            }
        }
    }

    private fun sendHeartRate(bpm: Int) {
        _heartRateBpm.value = bpm
        val node = phoneNodeId ?: return
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(bpm.toShort())
            .array()
        scope.launch {
            try {
                messageClient.sendMessage(node, WatchProtocol.PATH_HEART_RATE, payload).awaitOrNull()
            } catch (_: Exception) {}
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun emitSample(timestampUs: Long, gx: Short, gy: Short, gz: Short) {
        val pendingData: ByteArray?
        val pendingCount: Int
        synchronized(chunkBuffer) {
            WatchProtocol.encodeSample(
                chunkBuffer,
                timestampUs,
                latestAxMg, latestAyMg, latestAzMg,
                gx, gy, gz,
                latestMxX10, latestMyX10, latestMzX10,
            )
            chunkCount++
            if (chunkCount >= CHUNK_SIZES) {
                pendingData = chunkBuffer.array().copyOf(chunkCount * WatchProtocol.IMU_SAMPLE_SIZE)
                pendingCount = chunkCount
                chunkBuffer.clear()
                chunkCount = 0
            } else {
                pendingData = null
                pendingCount = 0
            }
        }
        if (pendingData != null) {
            writeChunkToChannel(pendingData, pendingCount)
        }
    }

    private fun writeChunkToChannel(data: ByteArray, count: Int) {
        val out = output ?: return
        try {
            out.write(data)
            out.flush()
            sentSamples.addAndGet(count.toLong())
            _sentSampleCount.value = sentSamples.get()
        } catch (e: Exception) {
            Log.w(TAG, "flushChunk write falhou: ${e.message}")
            reportError("Stream interrompido: ${e.message}")
            scope.launch { stopStreamingInternal() }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (_isStreaming.value) {
                try {
                    val node = phoneNodeId
                    if (node != null) {
                        val battery = readBatteryPct()
                        _batteryPct.value = battery
                        val payload = ByteBuffer.allocate(8)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putShort(_sampleRateHz.value.toShort())
                            .putShort(battery.toShort())
                            .putInt(sentSamples.get().toInt())
                            .array()
                        messageClient.sendMessage(node, WatchProtocol.PATH_HEARTBEAT, payload)
                            .awaitOrNull()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "heartbeat falhou: ${e.message}")
                }
                kotlinx.coroutines.delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun notifyImuState(running: Boolean) {
        val node = phoneNodeId ?: return
        val payload = byteArrayOf(if (running) 1 else 0)
        scope.launch {
            try {
                messageClient.sendMessage(node, WatchProtocol.PATH_IMU_STATE, payload).awaitOrNull()
            } catch (_: Exception) {}
        }
    }

    private fun reportError(msg: String) {
        _lastError.value = msg
        val node = phoneNodeId ?: return
        scope.launch {
            try {
                messageClient.sendMessage(node, WatchProtocol.PATH_ERROR, msg.toByteArray()).awaitOrNull()
            } catch (_: Exception) {}
        }
    }

    private fun readBatteryPct(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }

    private suspend fun resolvePhoneNode(): String? {
        return try {
            val info = capabilityClient
                .getCapability(WatchProtocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .awaitOrNull()
            info?.nodes?.firstOrNull { it.isNearby }?.id ?: info?.nodes?.firstOrNull()?.id
        } catch (e: Exception) {
            Log.w(TAG, "resolvePhoneNode falhou: ${e.message}")
            null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.launch { stopStreamingInternal() }.also { /* fire & forget */ }
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_imu),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.notification_channel_imu_desc)
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_imu_title))
            .setContentText(getString(R.string.notification_imu_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }
}

// Pequeno helper para usar Tasks com coroutines sem trazer kotlinx-coroutines-play-services.
private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitOrNull(timeoutMs: Long = 5_000): T? {
    return withTimeoutOrNull(timeoutMs) {
        try {
            Tasks.await(this@awaitOrNull)
        } catch (_: Exception) {
            null
        }
    }
}
