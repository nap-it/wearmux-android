package com.example.peciwearables.integration.sensors

import com.example.peciwearables.integration.protocol.GlassesImuSample
import com.example.peciwearables.integration.protocol.ImuSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch


class CoAxialImuFusion(
    private val scope: CoroutineScope,
    /** Frequência de emissão do stream fundido (Hz). */
    private val outputHz: Int = 50,
    /** Idade máxima de uma amostra para entrar na fusão. */
    private val staleMs: Long = 200L,
) {
    private data class Snap(
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float,
        val mx: Float, val my: Float, val mz: Float,
        val tMs: Long,
    )

    @Volatile private var glasses: Snap? = null
    @Volatile private var watch: Snap? = null
    @Volatile private var wristband: Snap? = null
    @Volatile private var phoneAccel: Triple<Float, Float, Float>? = null
    @Volatile private var phoneAccelMs: Long = 0L

    private val _stream = MutableSharedFlow<GlassesImuSample>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val stream: SharedFlow<GlassesImuSample> = _stream.asSharedFlow()

    /** Quantas fontes contribuíram para a última amostra (1..4). */
    @Volatile var lastFusedSourceCount: Int = 0
        private set

    /** Quais dos pares foram fundidos no último tick. */
    @Volatile var lastBodyAxisCount: Int = 0
        private set

    @Volatile var lastWristAxisCount: Int = 0
        private set

    private var pumpJob: Job? = null

    fun start() {
        if (pumpJob != null) return
        val periodMs = (1000L / outputHz).coerceAtLeast(5L)
        pumpJob = scope.launch {
            while (true) {
                tick(System.currentTimeMillis())
                delay(periodMs)
            }
        }
    }

    fun stop() {
        pumpJob?.cancel()
        pumpJob = null
    }

    fun feedGlasses(s: GlassesImuSample, nowMs: Long = System.currentTimeMillis()) {
        glasses = Snap(s.ax, s.ay, s.az, s.gx, s.gy, s.gz, s.mx, s.my, s.mz, nowMs)
    }

    fun feedWatch(s: ImuSample, nowMs: Long = System.currentTimeMillis()) {
        watch = toSiSnap(s, nowMs)
    }

    fun feedWristband(s: ImuSample, nowMs: Long = System.currentTimeMillis()) {
        wristband = toSiSnap(s, nowMs)
    }

    /** Acelerómetro do telemóvel em m/s² (`TYPE_LINEAR_ACCELERATION` já sem gravidade). */
    fun feedPhoneAccel(ax: Float, ay: Float, az: Float, nowMs: Long = System.currentTimeMillis()) {
        phoneAccel = Triple(ax, ay, az)
        phoneAccelMs = nowMs
    }

    private fun toSiSnap(s: ImuSample, nowMs: Long): Snap = Snap(
        ax = s.ax / 1000f * 9.80665f,
        ay = s.ay / 1000f * 9.80665f,
        az = s.az / 1000f * 9.80665f,
        gx = s.gx / 1000f,
        gy = s.gy / 1000f,
        gz = s.gz / 1000f,
        mx = s.mx / 10f,
        my = s.my / 10f,
        mz = s.mz / 10f,
        tMs = nowMs,
    )

    private fun tick(nowMs: Long) {
        val g = glasses?.takeIf { nowMs - it.tMs <= staleMs }
        val w = watch?.takeIf { nowMs - it.tMs <= staleMs }
        val r = wristband?.takeIf { nowMs - it.tMs <= staleMs }
        val pAccel = phoneAccel?.takeIf { nowMs - phoneAccelMs <= staleMs }

        // ── Body-axis (óculos + telemóvel) ──────────────────────────────
        var bodyCount = 0
        var bodyAx = 0f; var bodyAy = 0f; var bodyAz = 0f
        if (g != null) { bodyAx += g.ax; bodyAy += g.ay; bodyAz += g.az; bodyCount++ }
        if (pAccel != null) {
            bodyAx += pAccel.first; bodyAy += pAccel.second; bodyAz += pAccel.third; bodyCount++
        }

        // ── Wrist-axis (watch + wristband) ──────────────────────────────
        var wristCount = 0
        var wristAx = 0f; var wristAy = 0f; var wristAz = 0f
        if (w != null) { wristAx += w.ax; wristAy += w.ay; wristAz += w.az; wristCount++ }
        if (r != null) { wristAx += r.ax; wristAy += r.ay; wristAz += r.az; wristCount++ }

        lastBodyAxisCount = bodyCount
        lastWristAxisCount = wristCount
        val totalSources = (if (g != null) 1 else 0) +
            (if (w != null) 1 else 0) +
            (if (r != null) 1 else 0) +
            (if (pAccel != null) 1 else 0)
        lastFusedSourceCount = totalSources

        if (totalSources == 0) return

        // Output accel: prefere body-axis; se body-axis vazio, cai para
        // wrist-axis (último recurso — não ideal para "movimento do corpo"
        // mas melhor que silêncio).
        val (outAx, outAy, outAz) = when {
            bodyCount > 0 -> Triple(bodyAx / bodyCount, bodyAy / bodyCount, bodyAz / bodyCount)
            wristCount > 0 -> Triple(wristAx / wristCount, wristAy / wristCount, wristAz / wristCount)
            else -> return
        }

        // Output gyro + mag: SEMPRE óculos (BNO085). Se não houver óculos,
        // usa watch/wristband apenas como fallback para gyro (sem mag).
        val gyroSrc = g ?: w ?: r
        val (outGx, outGy, outGz) = if (gyroSrc != null)
            Triple(gyroSrc.gx, gyroSrc.gy, gyroSrc.gz) else Triple(0f, 0f, 0f)
        val (outMx, outMy, outMz) = if (g != null)
            Triple(g.mx, g.my, g.mz) else Triple(0f, 0f, 0f)

        _stream.tryEmit(
            GlassesImuSample(
                outAx, outAy, outAz,
                outGx, outGy, outGz,
                outMx, outMy, outMz,
            )
        )
    }
}
