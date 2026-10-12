package com.wearmux.android.integration.modules.sensors

import com.wearmux.android.integration.api.ImuCoCoSinkConfig
import com.wearmux.android.integration.protocol.GlassesImuSample
import com.wearmux.android.integration.protocol.ImuSample
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
 * Subscribes directly to the 3 unfused per-device IMU streams (never
 * CoAxialImuFusion, which averages different body positions) and sends
 * one absolute-orientation UDP packet per device per tick, in the
 * live_demo.py wire protocol.
 */
class ImuUdpTriStreamSink(
    private val scope: CoroutineScope,
    private val config: () -> ImuCoCoSinkConfig,
    glassesImuStream: SharedFlow<GlassesImuSample>,
    watchImuStream: SharedFlow<ImuSample>,
    phoneImuStream: SharedFlow<GlassesImuSample>,
    private val transport: ImuUdpTransport = DatagramImuUdpTransport(),
    private val outputHz: Int = 50,
) {
    @Volatile private var latestGlasses: GlassesImuSample? = null
    @Volatile private var latestWatch: ImuSample? = null
    @Volatile private var latestPhone: GlassesImuSample? = null

    private val glassesFilter = MadgwickAhrs(sampleFreqHz = outputHz.toFloat())
    private val watchFilter = MadgwickAhrs(sampleFreqHz = outputHz.toFloat())
    private val phoneFilter = MadgwickAhrs(sampleFreqHz = outputHz.toFloat())

    private val glassesImuStream = glassesImuStream
    private val watchImuStream = watchImuStream
    private val phoneImuStream = phoneImuStream

    private var pumpJob: Job? = null
    private val collectJobs = mutableListOf<Job>()

    fun start() {
        if (pumpJob != null) return
        collectJobs += scope.launch { glassesImuStream.collect { latestGlasses = it } }
        collectJobs += scope.launch { watchImuStream.collect { latestWatch = it } }
        collectJobs += scope.launch { phoneImuStream.collect { latestPhone = it } }
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

        latestGlasses?.let {
            sendDevice(cfg, "glasses", glassesFilter, it.ax, it.ay, it.az, degToRad(it.gx), degToRad(it.gy), degToRad(it.gz), it.mx, it.my, it.mz)
        }
        latestPhone?.let {
            sendDevice(cfg, "phone", phoneFilter, it.ax, it.ay, it.az, degToRad(it.gx), degToRad(it.gy), degToRad(it.gz), it.mx, it.my, it.mz)
        }
        latestWatch?.let { w ->
            // Raw watch units: mg, mdps, uT*10 -> SI (same conversion as CoAxialImuFusion.toSiSnap).
            val ax = w.ax / 1000f * 9.80665f
            val ay = w.ay / 1000f * 9.80665f
            val az = w.az / 1000f * 9.80665f
            val gx = degToRad(w.gx / 1000f)
            val gy = degToRad(w.gy / 1000f)
            val gz = degToRad(w.gz / 1000f)
            val mx = w.mx / 10f
            val my = w.my / 10f
            val mz = w.mz / 10f
            sendDevice(cfg, "watch", watchFilter, ax, ay, az, gx, gy, gz, mx, my, mz)
        }
    }

    private fun degToRad(deg: Float): Float = deg * (kotlin.math.PI.toFloat() / 180f)

    private fun sendDevice(
        cfg: ImuCoCoSinkConfig,
        deviceType: String,
        filter: MadgwickAhrs,
        ax: Float, ay: Float, az: Float,
        gxRad: Float, gyRad: Float, gzRad: Float,
        mx: Float, my: Float, mz: Float,
    ) {
        filter.update(gxRad, gyRad, gzRad, ax, ay, az, mx, my, mz)
        val gravity = filter.gravityVector()
        val quat = filter.quaternion()
        val userAx = ax - gravity[0]
        val userAy = ay - gravity[1]
        val userAz = az - gravity[2]
        val timestamp = System.currentTimeMillis()

        val sample = "$timestamp $userAx $userAy $userAz " +
            "${gravity[0]} ${gravity[1]} ${gravity[2]} " +
            "$gxRad $gyRad $gzRad " +
            "${quat[0]} ${quat[1]} ${quat[2]} ${quat[3]}"
        val message = "${cfg.effectiveUserId()};$deviceType;motion:$sample"

        try {
            transport.send(cfg.host, cfg.port, message.toByteArray(Charsets.US_ASCII))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Network hiccup: drop this tick's packet, keep the pump running.
        }
    }
}
