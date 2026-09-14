package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.protocol.GlassesImuSample
import com.example.peciwearables.integration.protocol.ImuSample
import com.example.peciwearables.integration.sensors.PhoneAccelSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch


class SafetyDiagnostics(
    val headRotation: HeadRotationDetector = HeadRotationDetector(),
    val motion: MotionContinuityDetector = MotionContinuityDetector(),
    val deceleration: DecelerationDetector = DecelerationDetector(),
) {

    private val jobs = mutableListOf<Job>()

    fun start(
        scope: CoroutineScope,
        glassesImuFlow: SharedFlow<GlassesImuSample>,
        latestImuSamplesFlow: StateFlow<List<ImuSample>>,
        phoneAccelFlow: StateFlow<PhoneAccelSample?>,
    ) {
        stop()
        jobs += scope.launch { glassesImuFlow.collect { headRotation.onSample(it) } }
        jobs += scope.launch {
            latestImuSamplesFlow.collect { samples -> samples.forEach { motion.onSample(it) } }
        }
        jobs += scope.launch {
            phoneAccelFlow.collect { it?.let(deceleration::onSample) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        headRotation.reset()
        motion.reset()
        deceleration.reset()
    }

    fun headSpreadDeg(): Float = headRotation.spreadDeg()
    fun motionPeakCount(): Int = motion.peakCountInWindow()
    fun decelerationRatio(): Float = deceleration.ratio()
    fun isDecelerating(): Boolean = deceleration.isDecelerating()
}
