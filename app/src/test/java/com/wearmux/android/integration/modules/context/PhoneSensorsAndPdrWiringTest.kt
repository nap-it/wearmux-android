package com.wearmux.android.integration.modules.context

import android.content.Context
import android.hardware.SensorManager
import com.wearmux.android.integration.modules.android.PhoneAccelSample
import com.wearmux.android.integration.modules.android.PhoneGpsLocation
import com.wearmux.android.integration.modules.android.PhoneSensorManager
import com.wearmux.android.integration.modules.sensors.CoAxialImuFusion
import com.wearmux.android.integration.protocol.GlassesImuSample
import com.wearmux.android.integration.protocol.ImuSample
import com.wearmux.android.integration.safety.CyclistModeDetector
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneSensorsAndPdrWiringTest {

    private fun fakeContext(): Context {
        val sensorManager = mockk<SensorManager>(relaxed = true)
        every { sensorManager.getDefaultSensor(any()) } returns null
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
        return context
    }

    @Test
    fun `mag sample emits combined accel-gyro-mag sample to phoneImuStream`() {
        // Plain Dispatchers.Unconfined (not kotlinx-coroutines-test): start()
        // launches a long-lived `while(true) { ... delay(1_000) }` loop (the
        // UC1.4 poller) that a virtual-time test scheduler would try to drive
        // to completion, hanging or throwing. cancelAndJoin() (not plain
        // cancel(), which is fire-and-forget) waits for that loop to actually
        // finish unwinding before this test returns, so it can't throw during
        // the NEXT test's runTest in the full suite.
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val phoneSensors = PhoneSensorManager(fakeContext())
        val phoneImuStream = MutableSharedFlow<GlassesImuSample>(
            replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val captured = mutableListOf<GlassesImuSample>()
        val collector = scope.launch { phoneImuStream.collect { captured.add(it) } }

        // Real instances, not mockk(relaxed = true): start() calls .collect()
        // on pdr.position/stepCount and routeManager.routes/activeRoute/
        // isRecording/recordingWaypoints, which are real StateFlows. MockK's
        // relaxed mode fabricates a StateFlow-shaped mock whose collect()
        // does not behave like a real StateFlow collector under Unconfined,
        // which is what produced the KotlinNothingValueException chain.
        val wiring = PhoneSensorsAndPdrWiring(
            scope = scope,
            phoneSensors = phoneSensors,
            pdr = PedestrianDeadReckoning(),
            routeManager = RouteManager(fakeContext()),
            cyclistDetector = mockk(relaxed = true),
            coAxialImuFusion = CoAxialImuFusion(scope),
            phoneAccel = MutableStateFlow(null),
            phoneGps = MutableStateFlow(null),
            pdrPosition = MutableStateFlow(null),
            pdrStepCount = MutableStateFlow(0),
            savedRoutes = MutableStateFlow(emptyList()),
            activeRoute = MutableStateFlow(null),
            routeRecording = MutableStateFlow(false),
            routeRecordingWaypointCount = MutableStateFlow(0),
            routeRecordingWaypoints = MutableStateFlow(emptyList()),
            latestImuSamples = MutableStateFlow(emptyList<ImuSample>()),
            cyclistMode = MutableStateFlow(CyclistModeDetector.Mode.UNKNOWN),
            imuSampleTotalCounter = AtomicLong(0),
            routeImuOnly = { false },
            waitingForAnchorFix = { false },
            onAnchor = { _, _ -> },
            uc1_4Enabled = { false },
            phoneImuStream = phoneImuStream,
        )
        wiring.start()

        phoneSensors.onAccelSample?.invoke(PhoneAccelSample(1f, 2f, 3f, 0L))
        phoneSensors.onGyroSample?.invoke(floatArrayOf(0.1f, 0.2f, 0.3f), 0L)
        phoneSensors.onMagSample?.invoke(floatArrayOf(10f, 20f, 30f), 0L)

        // Dispatchers.Unconfined runs the collector synchronously on each
        // tryEmit above, so no advance/yield is needed before asserting.
        // cancelAndJoin (not cancel()) waits for every launched coroutine,
        // including the UC1.4 poller loop, to actually finish unwinding
        // before this test method returns.
        runBlocking { job.cancelAndJoin() }

        assertEquals(1, captured.size)
        val sample = captured[0]
        assertEquals(1f, sample.ax, 0.001f); assertEquals(2f, sample.ay, 0.001f); assertEquals(3f, sample.az, 0.001f)
        assertEquals(10f, sample.mx, 0.001f); assertEquals(20f, sample.my, 0.001f); assertEquals(30f, sample.mz, 0.001f)
    }
}
