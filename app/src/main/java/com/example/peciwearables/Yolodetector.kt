package com.example.peciwearables

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

// ── Result types ──────────────────────────────────────────────────────────────

data class Detection(
    val label: String,
    val confidence: Float,  // 0..1
    val left: Float,        // normalized 0..1
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun toPixelRect(canvasW: Float, canvasH: Float) = PixelRect(
        left   = left   * canvasW,
        top    = top    * canvasH,
        right  = right  * canvasW,
        bottom = bottom * canvasH
    )
}

data class PixelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width  get() = right - left
    val height get() = bottom - top
}

// ── Labels ────────────────────────────────────────────────────────────────────

// Subset of COCO classes we actually care about showing in the UI.
val COCO_LABELS = listOf(
    "person",
    "bicycle",
    "car",
    "motorcycle",
    "bus",
    "truck",
    "traffic light",
    "stop sign",
)

// Full COCO-80 class list in the exact order YOLOv8 was trained on.
// This MUST have 80 entries — the detector loops over rows 4..83 of the
// output tensor, so any mismatch causes labels to shift and most classes
// to be silently dropped.
private val COCO_ALL = arrayOf(
    "person", "bicycle", "car", "motorcycle", "airplane",
    "bus", "train", "truck", "boat", "traffic light",
    "fire hydrant", "stop sign", "parking meter", "bench", "bird",
    "cat", "dog", "horse", "sheep", "cow",
    "elephant", "bear", "zebra", "giraffe", "backpack",
    "umbrella", "handbag", "tie", "suitcase", "frisbee",
    "skis", "snowboard", "sports ball", "kite", "baseball bat",
    "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
    "wine glass", "cup", "fork", "knife", "spoon",
    "bowl", "banana", "apple", "sandwich", "orange",
    "broccoli", "carrot", "hot dog", "pizza", "donut",
    "cake", "chair", "couch", "potted plant", "bed",
    "dining table", "toilet", "tv", "laptop", "mouse",
    "remote", "keyboard", "cell phone", "microwave", "oven",
    "toaster", "sink", "refrigerator", "book", "clock",
    "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
)

// ── Detector ──────────────────────────────────────────────────────────────────


class YoloDetector(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = context.assets.open("yolov8n.onnx").readBytes()
        session = env.createSession(bytes, OrtSession.SessionOptions())
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Detect from any [Bitmap] — scaled to 640×640 internally. */
    fun detect(
        bitmap: Bitmap,
        confThreshold: Float = 0.35f,
        iouThreshold: Float  = 0.45f
    ): List<Detection> {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val input = bitmapToChw(scaled)
        scaled.recycle()
        return runInference(input, confThreshold, iouThreshold)
    }

    fun close() { session.close(); env.close() }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun runInference(chw: FloatArray, confThreshold: Float, iouThreshold: Float): List<Detection> {
        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(chw),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )

        val outputMap = session.run(mapOf("images" to tensor))
        val raw = parseOutput(outputMap) ?: return emptyList()

        // raw shape after parsing: [84][8400]
        // rows 0-3 = box (cx,cy,w,h in 640-space), rows 4-83 = class scores
        val numBoxes = raw[0].size
        val detections = mutableListOf<Detection>()

        for (i in 0 until numBoxes) {
            val cx = raw[0][i]; val cy = raw[1][i]
            val w  = raw[2][i]; val h  = raw[3][i]

            // Scan all 80 COCO class scores — rows 4..83 of the YOLOv8 output.
            var maxScore = 0f; var classIdx = -1
            val numClasses = raw.size - 4
            for (c in 0 until numClasses) {
                val score = raw[4 + c][i]
                if (score > maxScore) { maxScore = score; classIdx = c }
            }

            if (maxScore < confThreshold) continue
            if (classIdx < 0 || classIdx >= COCO_ALL.size) continue
            val label = COCO_ALL[classIdx]
            if (label !in COCO_LABELS) continue   // only keep our subset

            detections += Detection(
                label      = label,
                confidence = maxScore,
                left       = ((cx - w / 2f) / INPUT_SIZE).coerceIn(0f, 1f),
                top        = ((cy - h / 2f) / INPUT_SIZE).coerceIn(0f, 1f),
                right      = ((cx + w / 2f) / INPUT_SIZE).coerceIn(0f, 1f),
                bottom     = ((cy + h / 2f) / INPUT_SIZE).coerceIn(0f, 1f)
            )
        }

        return nms(detections, iouThreshold)
    }

   
    private fun parseOutput(outputMap: OrtSession.Result): Array<FloatArray>? {
        return try {
            val raw = outputMap[0].value
            @Suppress("UNCHECKED_CAST")
            when {
                // Case A: [1][84][8400]
                raw is Array<*> && raw[0] is Array<*> && (raw[0] as Array<*>)[0] is FloatArray -> {
                    (raw[0] as Array<FloatArray>)
                }
                // Case B: [84][8400]
                raw is Array<*> && raw[0] is FloatArray -> {
                    raw as Array<FloatArray>
                }
                // Case C: flat float array — reshape into [84][8400]
                raw is FloatArray -> {
                    val cols = raw.size / 84
                    Array(84) { r -> FloatArray(cols) { c -> raw[r * cols + c] } }
                }
                else -> {
                    Log.e(TAG, "Unknown output type: ${raw?.javaClass}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Output parse error: ${e.message}")
            null
        }
    }

    private fun bitmapToChw(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val out = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        val plane = INPUT_SIZE * INPUT_SIZE
        for (i in pixels.indices) {
            val px = pixels[i]
            out[i]             = ((px shr 16 and 0xFF) / 255f) // R
            out[i + plane]     = ((px shr 8  and 0xFF) / 255f) // G
            out[i + plane * 2] = ((px         and 0xFF) / 255f) // B
        }
        return out
    }

    private fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result += best
            sorted.removeAll { iou(best, it) > iouThreshold }
        }
        return result
    }

    private fun iou(a: Detection, b: Detection): Float {
        val iL = max(a.left, b.left);  val iT = max(a.top, b.top)
        val iR = min(a.right, b.right); val iB = min(a.bottom, b.bottom)
        val inter = max(0f, iR - iL) * max(0f, iB - iT)
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)
        return if (aArea + bArea - inter == 0f) 0f else inter / (aArea + bArea - inter)
    }

    companion object {
        private const val TAG = "YoloDetector"
        private const val INPUT_SIZE = 640
    }
}