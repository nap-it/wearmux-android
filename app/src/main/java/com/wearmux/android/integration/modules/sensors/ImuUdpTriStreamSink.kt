package com.wearmux.android.integration.modules.sensors

import com.wearmux.android.integration.api.ImuCoCoSinkConfig
import com.wearmux.android.integration.protocol.GlassesFusedImuSample
import com.wearmux.android.integration.protocol.PhoneFusedImuSample
import com.wearmux.android.integration.protocol.WatchFusedImuSample
import com.wearmux.android.integration.protocol.WristbandFusedImuSample
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

interface ImuUdpTransport {
    fun send(host: String, port: Int, bytes: ByteArray)
}

class DatagramImuUdpTransport : ImuUdpTransport {
    private val socket = DatagramSocket()

    override fun send(host: String, port: Int, bytes: ByteArray) {
        val address = InetAddress.getByName(host)
        socket.send(DatagramPacket(bytes, bytes.size, address, port))
    }

    fun close() {
        runCatching { socket.close() }
    }
}

/**
 * Subscribes directly to the 4 already-fused per-device IMU streams (never
 * CoAxialImuFusion, which averages different body positions) and sends
 * one absolute-orientation UDP packet per device per tick, in the
 * live_demo.py wire protocol.
 */
class ImuUdpTriStreamSink(
    private val scope: CoroutineScope,
    private val config: () -> ImuCoCoSinkConfig,
    glassesImuStream: SharedFlow<GlassesFusedImuSample>,
    watchImuStream: SharedFlow<WatchFusedImuSample>,
    phoneImuStream: SharedFlow<PhoneFusedImuSample>,
    wristbandImuStream: SharedFlow<WristbandFusedImuSample>,
    private val transport: ImuUdpTransport = DatagramImuUdpTransport(),
    private val outputHz: Int = 50,
    private val isGlassesActive: () -> Boolean = { true },
    private val isWatchActive: () -> Boolean = { true },
    private val isPhoneActive: () -> Boolean = { true },
    private val isWristbandActive: () -> Boolean = { true },
) {
    @Volatile private var latestGlasses: GlassesFusedImuSample? = null
    @Volatile private var latestWatch: WatchFusedImuSample? = null
    @Volatile private var latestPhone: PhoneFusedImuSample? = null
    @Volatile private var latestWristband: WristbandFusedImuSample? = null

    private val glassesImuStream = glassesImuStream
    private val watchImuStream = watchImuStream
    private val phoneImuStream = phoneImuStream
    private val wristbandImuStream = wristbandImuStream

    private var pumpJob: Job? = null
    private val collectJobs = mutableListOf<Job>()

    fun start() {
        if (pumpJob != null) return
        collectJobs += scope.launch { glassesImuStream.collect { latestGlasses = it } }
        collectJobs += scope.launch { watchImuStream.collect { latestWatch = it } }
        collectJobs += scope.launch { phoneImuStream.collect { latestPhone = it } }
        collectJobs += scope.launch { wristbandImuStream.collect { latestWristband = it } }
        val periodMs = (1000L / outputHz).coerceAtLeast(5L)
        pumpJob = scope.launch {
            while (true) {
                tick()
                delay(periodMs)
            }
        }
    }

    fun stop() {
        pumpJob?.cancel(); pumpJob = null
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
    }

    private fun tick() {
        val cfg = config()
        if (!cfg.isUsable) return

        if (!isGlassesActive()) latestGlasses = null
        if (!isPhoneActive()) latestPhone = null
        if (!isWatchActive()) latestWatch = null
        if (!isWristbandActive()) latestWristband = null

        latestGlasses?.let {
            sendFusedDevice(cfg, "glasses", it.ax, it.ay, it.az, it.qx, it.qy, it.qz, it.qw)
        }
        latestPhone?.let {
            sendFusedDevice(cfg, "phone", it.ax, it.ay, it.az, it.qx, it.qy, it.qz, it.qw)
        }
        latestWristband?.let {
            sendFusedDevice(cfg, "wristband", it.ax, it.ay, it.az, it.qx, it.qy, it.qz, it.qw)
        }
        latestWatch?.let {
            sendFusedDevice(cfg, "watch", it.ax, it.ay, it.az, it.qx, it.qy, it.qz, it.qw)
        }
    }

    /**
     * Sends an already-fused device sample (glasses, phone, wristband): no Madgwick,
     * gravity/gyro zero-filled since each device's chip already fused its own quaternion.
     */
    private fun sendFusedDevice(
        cfg: ImuCoCoSinkConfig,
        deviceType: String,
        ax: Float, ay: Float, az: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
    ) {
        val timestamp = System.currentTimeMillis()
        val wireSample = "$timestamp $ax $ay $az " +
            "0.0 0.0 0.0 " + // gravity: zero-filled, IMUCoCo's parser never reads this field
            "0.0 0.0 0.0 " + // gyro: zero-filled, IMUCoCo's parser never reads this field
            "$qx $qy $qz $qw"
        val message = "${cfg.effectiveUserId()};$deviceType;motion:$wireSample"

        try {
            transport.send(cfg.host, cfg.port, message.toByteArray(Charsets.US_ASCII))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Network hiccup: drop this tick's packet, keep the pump running.
        }
    }
}
