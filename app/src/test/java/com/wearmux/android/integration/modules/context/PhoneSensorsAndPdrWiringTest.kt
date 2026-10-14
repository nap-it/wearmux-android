package com.wearmux.android.integration.modules.context

import android.content.Context
import android.hardware.SensorManager
import com.wearmux.android.integration.modules.android.PhoneAccelSample
import com.wearmux.android.integration.modules.android.PhoneGpsLocation
import com.wearmux.android.integration.modules.android.PhoneSensorManager
import com.wearmux.android.integration.modules.sensors.CoAxialImuFusion
import com.wearmux.android.integration.protocol.GlassesImuSample
import com.wearmux.android.integration.protocol.ImuSample
import com.wearmux.android.integration.protocol.PhoneFusedImuSample
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
    fun `game rotation sample emits PhoneFusedImuSample once accel has arrived`() {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val phoneSensors = PhoneSensorManager(fakeContext())
        val phoneImuStream = MutableSharedFlow<PhoneFusedImuSample>(
            replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val captured = mutableListOf<PhoneFusedImuSample>()
        val collector = scope.launch { phoneImuStream.collect { captured.add(it) } }

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
        phoneSensors.onGameRotationSample?.invoke(floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f), 0L)

        runBlocking { job.cancelAndJoin() }

        assertEquals(1, captured.size)
        val sample = captured[0]
        assertEquals(1f, sample.ax, 0.001f); assertEquals(2f, sample.ay, 0.001f); assertEquals(3f, sample.az, 0.001f)
        assertEquals(0.1f, sample.qx, 0.0001f); assertEquals(0.2f, sample.qy, 0.0001f)
        assertEquals(0.3f, sample.qz, 0.0001f); assertEquals(0.9f, sample.qw, 0.0001f)
    }
}
