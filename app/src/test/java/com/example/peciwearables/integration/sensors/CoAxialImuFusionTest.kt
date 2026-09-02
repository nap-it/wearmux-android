package com.example.peciwearables.integration.sensors

import com.example.peciwearables.integration.protocol.GlassesImuSample
import com.example.peciwearables.integration.protocol.ImuSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class CoAxialImuFusionTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var fusion: CoAxialImuFusion
    private val captured = mutableListOf<GlassesImuSample>()
    private var collectorJob: Job? = null

    @Before fun setUp() {
        fusion = CoAxialImuFusion(scope, outputHz = 1, staleMs = 1_000L)
        collectorJob = scope.launch {
            fusion.stream.collect { captured.add(it) }
        }
    }

    @After fun tearDown() {
        collectorJob?.cancel()
        scope.cancel()
    }

    /** Invoca o tick privado para emitir sem precisar do loop coroutine. */
    private fun tickNow(nowMs: Long) = runBlocking {
        val m = CoAxialImuFusion::class.java
            .getDeclaredMethod("tick", Long::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(fusion, nowMs)
        yield()  // deixa o Unconfined dispatcher entregar à collect
    }

    @Test
    fun `sem fontes nao emite nada`() {
        tickNow(NOW)
        assertEquals(0, fusion.lastFusedSourceCount)
        assertEquals(0, captured.size)
    }

    @Test
    fun `apenas oculos = passthrough do accel e mag`() {
        val glasses = GlassesImuSample(
            ax = 1f, ay = 2f, az = 9.8f,
            gx = 5f, gy = 0f, gz = 10f,
            mx = 30f, my = -20f, mz = 5f,
        )
        fusion.feedGlasses(glasses, NOW)
        tickNow(NOW)

        assertEquals(1, captured.size)
        val out = captured.last()
        assertEquals(1, fusion.lastFusedSourceCount)
        assertEquals(1, fusion.lastBodyAxisCount)
        assertNear(1f, out.ax)
        assertNear(2f, out.ay)
        assertNear(9.8f, out.az)
        assertNear(30f, out.mx)
        assertNear(10f, out.gz)
    }

    @Test
    fun `oculos+telemovel = body-axis media do accel`() {
        val glasses = GlassesImuSample(
            ax = 2f, ay = 0f, az = 9.8f,
            gx = 5f, gy = 0f, gz = 10f,
            mx = 30f, my = 0f, mz = 0f,
        )
        fusion.feedGlasses(glasses, NOW)
        fusion.feedPhoneAccel(4f, 0f, 9.8f, NOW)
        tickNow(NOW)

        val out = captured.last()
        assertEquals(2, fusion.lastBodyAxisCount)
        assertEquals(0, fusion.lastWristAxisCount)
        assertNear(3f, out.ax)   // (2+4)/2
        assertNear(0f, out.ay)
        assertNear(9.8f, out.az)
        assertNear(10f, out.gz)
        assertNear(30f, out.mx)
    }

    @Test
    fun `apenas watch+wristband = wrist-axis fallback`() {
        fusion.feedWatch(imuSample(ax = 100, ay = 0, az = 1000), NOW)
        fusion.feedWristband(imuSample(ax = 200, ay = 0, az = 1000), NOW)
        tickNow(NOW)

        val out = captured.last()
        assertEquals(2, fusion.lastFusedSourceCount)
        assertEquals(0, fusion.lastBodyAxisCount)
        assertEquals(2, fusion.lastWristAxisCount)
        // Watch ax=100 mg → 0.981 m/s², Wristband ax=200 mg → 1.961 m/s²
        // Média = 1.471 m/s²
        assertNear(1.471f, out.ax, tol = 0.05f)
    }

    @Test
    fun `4 fontes activas = body media + gyro mag dos oculos`() {
        val glasses = GlassesImuSample(
            ax = 2f, ay = 0f, az = 9.8f,
            gx = 1f, gy = 2f, gz = 3f,
            mx = 40f, my = 50f, mz = 60f,
        )
        fusion.feedGlasses(glasses, NOW)
        fusion.feedPhoneAccel(4f, 0f, 9.8f, NOW)
        fusion.feedWatch(imuSample(ax = 1000, ay = 0, az = 1000), NOW)
        fusion.feedWristband(imuSample(ax = 1000, ay = 0, az = 1000), NOW)
        tickNow(NOW)

        assertEquals(4, fusion.lastFusedSourceCount)
        assertEquals(2, fusion.lastBodyAxisCount)
        assertEquals(2, fusion.lastWristAxisCount)
        val out = captured.last()
        // accel body-axis: (2 + 4)/2 = 3 (watch/sole NÃO entram aqui)
        assertNear(3f, out.ax)
        // gyro/mag exclusivamente dos óculos
        assertNear(1f, out.gx); assertNear(2f, out.gy); assertNear(3f, out.gz)
        assertNear(40f, out.mx); assertNear(50f, out.my); assertNear(60f, out.mz)
    }

    @Test
    fun `samples stale sao ignoradas`() {
        val glasses = GlassesImuSample(
            ax = 5f, ay = 0f, az = 0f, gx = 0f, gy = 0f, gz = 0f,
            mx = 0f, my = 0f, mz = 0f,
        )
        fusion.feedGlasses(glasses, NOW - 5_000L)
        tickNow(NOW)
        assertEquals(0, fusion.lastFusedSourceCount)
        assertEquals(0, captured.size)
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun imuSample(
        ax: Int = 0, ay: Int = 0, az: Int = 0,
        gx: Int = 0, gy: Int = 0, gz: Int = 0,
        mx: Int = 0, my: Int = 0, mz: Int = 0,
    ): ImuSample = ImuSample(
        ax.toShort(), ay.toShort(), az.toShort(),
        gx.toShort(), gy.toShort(), gz.toShort(),
        mx.toShort(), my.toShort(), mz.toShort(),
        ShortArray(8),
    )

    private fun assertNear(expected: Float, actual: Float, tol: Float = 0.01f) {
        assertTrue(
            "esperado $expected, got $actual (tol $tol)",
            abs(expected - actual) <= tol,
        )
    }

    companion object { private const val NOW = 1_000_000L }
}
