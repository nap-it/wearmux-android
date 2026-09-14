package com.example.peciwearables.integration.image.camera

import com.example.peciwearables.integration.ble.BleDeviceState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Estado agregado e pronto-a-usar-pela-UI da stream de câmara dos óculos.
 *
 * Combina sinais já existentes ([WearableService][com.example.peciwearables.integration.WearableService]
 * `streamFps`/`streamFrameCount`/`glassesCameraStreamState`) num único snapshot — não introduz
 * novos contadores nem substitui `CameraMetrics` (diagnóstico mais detalhado, apenas caminho BLE).
 */
data class CameraStreamHealth(
    val isStreaming: Boolean,
    val framesReceived: Long,
    val fps: Float,
    val lastFrameTimestampMs: Long?,
    val lastFrameAgeMs: Long?,
    val status: CameraStreamStatus,
) {
    companion object {
        val EMPTY = CameraStreamHealth(
            isStreaming = false,
            framesReceived = 0L,
            fps = 0f,
            lastFrameTimestampMs = null,
            lastFrameAgeMs = null,
            status = CameraStreamStatus.DISCONNECTED,
        )
    }
}

enum class CameraStreamStatus { DISCONNECTED, IDLE, GOOD, UNSTABLE, LOST }

private const val UNSTABLE_AGE_MS = 2_000L
private const val LOST_AGE_MS = 5_000L
private const val MIN_GOOD_FPS = 1f

/**
 * Função pura (sem side-effects) que classifica o estado da stream a partir dos sinais
 * já publicados pelo serviço. Mantida separada para ser testável em isolamento.
 *
 * Nota: não depende de `GlassesCameraStreamState` — esse enum reflete o sub-estado de
 * captura de foto do dispositivo (IDLE/FOCUSING/CAPTURING/SLEEPING) e não é atualizado de
 * forma fiável durante streaming de vídeo puro (fica preso em STARTING). A chegada real de
 * frames (`frameCount`/`lastFrameAgeMs`) é o sinal decisivo.
 */
fun computeCameraStreamStatus(
    glassesState: BleDeviceState,
    frameCount: Long,
    fps: Float,
    lastFrameAgeMs: Long?,
): CameraStreamStatus {
    if (glassesState == BleDeviceState.DISCONNECTED || glassesState == BleDeviceState.ERROR) {
        return CameraStreamStatus.DISCONNECTED
    }
    if (frameCount == 0L || lastFrameAgeMs == null) {
        return CameraStreamStatus.IDLE
    }
    return when {
        lastFrameAgeMs > LOST_AGE_MS -> CameraStreamStatus.LOST
        lastFrameAgeMs > UNSTABLE_AGE_MS || fps < MIN_GOOD_FPS -> CameraStreamStatus.UNSTABLE
        else -> CameraStreamStatus.GOOD
    }
}

/**
 * Regista a chegada de um frame de vídeo (janela móvel de 1s para FPS, contagem total e
 * timestamp do último frame). Ponto único partilhado pelos transportes BLE e UDP/ESP32 —
 * substitui a lógica que estava duplicada em `CameraObserversWiring` e `handleStreamingJpeg`.
 */
fun recordStreamFrameArrival(
    streamFpsTimestamps: MutableList<Long>,
    streamFps: MutableStateFlow<Float>,
    streamFrameCount: MutableStateFlow<Int>,
    lastFrameTimestampMs: MutableStateFlow<Long?>,
    nowMs: Long = System.currentTimeMillis(),
) {
    streamFpsTimestamps.add(nowMs)
    while (streamFpsTimestamps.isNotEmpty() && nowMs - streamFpsTimestamps.first() > 1000L) {
        streamFpsTimestamps.removeAt(0)
    }
    streamFps.value = streamFpsTimestamps.size.toFloat()
    streamFrameCount.value++
    lastFrameTimestampMs.value = nowMs
}
