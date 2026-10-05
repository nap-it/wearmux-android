package com.wearmux.android.integration.diagnostics

import com.wearmux.android.integration.modules.camera.BleCameraPipeline
import com.wearmux.android.integration.modules.camera.ImagePipeline
import com.wearmux.android.integration.modules.microphone.AudioPipeline
import com.wearmux.android.integration.safety.SafetyDiagnostics
import com.wearmux.android.integration.sync.TimeSyncManager
import com.wearmux.android.integration.udp.UdpServer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Encapsula:
 *  - `startDiagnosticsLoop`: amostra head-spread/motion/deceleration/rate IMU @2Hz.
 *  - `startStatsLoop`: linhas de status UDP/audio/imagem/camera/sync @0.5Hz.
 */
class DiagnosticsLoopRunner(
    private val scope: CoroutineScope,
    private val safetyDiagnostics: SafetyDiagnostics,
    private val imuSampleTotalCounter: AtomicLong,
    private val headRotationSpreadDeg: MutableStateFlow<Float>,
    private val motionPeakCount: MutableStateFlow<Int>,
    private val decelerationRatio: MutableStateFlow<Float>,
    private val imuRateHz: MutableStateFlow<Int>,
    private val stats: MutableStateFlow<String>,
    private val udpServer: UdpServer,
    private val audioPipeline: AudioPipeline,
    private val imagePipeline: ImagePipeline,
    private val bleCameraPipeline: BleCameraPipeline,
    private val timeSyncManager: TimeSyncManager,
    private val udpStatus: () -> String?,
) {
    fun start() {
        scope.launch {
            var lastTs = 0L; var lastCnt = 0L
            while (true) {
                headRotationSpreadDeg.value = safetyDiagnostics.headSpreadDeg()
                motionPeakCount.value = safetyDiagnostics.motionPeakCount()
                decelerationRatio.value = safetyDiagnostics.decelerationRatio()
                val now = System.currentTimeMillis(); val cnt = imuSampleTotalCounter.get()
                if (lastTs > 0) {
                    val dt = (now - lastTs) / 1000f
                    imuRateHz.value = if (dt > 0) ((cnt - lastCnt).coerceAtLeast(0) / dt).toInt() else 0
                }
                lastTs = now; lastCnt = cnt
                delay(500)
            }
        }
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(2000)
                stats.value = buildString {
                    appendLine(udpServer.getStats())
                    udpStatus()?.let { appendLine("UDP Omi: $it") }
                    appendLine(audioPipeline.getStats())
                    appendLine(imagePipeline.getStats())
                    appendLine(bleCameraPipeline.getStats())
                    val syncStats = timeSyncManager.getStats()
                    if (syncStats.isNotEmpty()) appendLine("Sync: $syncStats")
                }
            }
        }
    }
}
