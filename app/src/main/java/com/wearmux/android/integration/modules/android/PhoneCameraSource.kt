package com.wearmux.android.integration.modules.android

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.wearmux.android.integration.WearableService
import java.util.concurrent.Executor

/**
 * Câmara do telemóvel como fonte de frames, via CameraX.
 *
 * Publica cada frame no hub (que lhe aplica o carimbo do Timestamper) e entrega-o
 * a quem o consome já com esse carimbo, para que o instante que segue para a cloud
 * seja o da captura e não o do pedido.
 */
class PhoneCameraSource(
    private val context: Context,
    private val analysisExecutor: Executor,
    private val targetResolution: Size = Size(640, 640),
) {
    private var provider: ProcessCameraProvider? = null

    /**
     * [needsFrame] evita converter o frame para bitmap quando ninguém o consome.
     * [onFrame] recebe o bitmap e o carimbo do hub para esse frame.
     */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        needsFrame: () -> Boolean,
        onFrame: (Bitmap, Long) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(targetResolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                try {
                    if (!needsFrame()) return@setAnalyzer
                    val bitmap = imageProxy.toBitmap()
                    WearableService.updatePhoneCameraBitmap(bitmap)
                    onFrame(bitmap, WearableService.phoneCameraBitmapAtMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame analysis error: ${e.message}")
                } finally {
                    imageProxy.close()
                }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
            )
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        provider?.unbindAll()
    }

    private companion object { const val TAG = "PhoneCameraSource" }
}
