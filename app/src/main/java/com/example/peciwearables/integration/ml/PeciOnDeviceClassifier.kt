package com.example.peciwearables.integration.ml

import android.content.Context
import com.example.peciwearables.integration.protocol.ImuSample
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class PeciOnDeviceClassifier(
    context: Context,
    private val modelAssetName: String = "peci_model.tflite",
    labelsAssetName: String = "peci_labels.txt",
    private val confidenceThreshold: Float = 0.60f,
) : Closeable {

    data class InferenceResult(
        val label: String,
        val score: Float,
        val timestampMs: Long,
        val values: FloatArray,
    )

    private val windowSize = 40
    private val sampleRateHz = 20f
    private val axisCount = 6
    private val buffer = ArrayDeque<FloatArray>(windowSize)

    private val interpreter: Interpreter
    private val labels: List<String>
    private val inputElementCount: Int
    private val inputDataType: DataType
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val outputDataType: DataType
    private val outputScale: Float
    private val outputZeroPoint: Int
    private val outputClassCount: Int

    init {
        val modelBuffer = loadModelFile(context, modelAssetName)
        val options = Interpreter.Options().apply {
            setNumThreads(2)
        }
        interpreter = Interpreter(modelBuffer, options)

        labels = runCatching {
            context.assets.open(labelsAssetName).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        }.getOrDefault(emptyList())

        val inputTensor = interpreter.getInputTensor(0)
        inputDataType = inputTensor.dataType()
        inputElementCount = inputTensor.shape().fold(1) { acc, dim -> acc * dim }
        inputScale = inputTensor.quantizationParams().scale
        inputZeroPoint = inputTensor.quantizationParams().zeroPoint

        val outputTensor = interpreter.getOutputTensor(0)
        outputDataType = outputTensor.dataType()
        outputScale = outputTensor.quantizationParams().scale
        outputZeroPoint = outputTensor.quantizationParams().zeroPoint
        outputClassCount = outputTensor.shape().lastOrNull() ?: 2
    }

    @Synchronized
    fun addSample(sample: ImuSample, timestampMs: Long = System.currentTimeMillis()): InferenceResult? {
        val fused = toFusedAxes(sample)
        if (buffer.size == windowSize) buffer.removeFirst()
        buffer.addLast(fused)

        if (buffer.size < windowSize) return null

        val features = when (inputElementCount) {
            240 -> buildRaw240(buffer)
            else -> buildSpectral78(buffer)
        }

        val scores = runModel(features)
        if (scores.isEmpty()) return null

        var bestIdx = 0
        var bestScore = scores[0]
        for (i in 1 until scores.size) {
            if (scores[i] > bestScore) {
                bestScore = scores[i]
                bestIdx = i
            }
        }

        if (bestScore < confidenceThreshold) return null

        val label = labels.getOrNull(bestIdx) ?: "class_$bestIdx"
        return InferenceResult(
            label = label,
            score = bestScore,
            timestampMs = timestampMs,
            values = scores,
        )
    }

    private fun runModel(features: FloatArray): FloatArray {
        val expected = inputElementCount
        val input = if (features.size == expected) features else {
            FloatArray(expected) { idx -> features.getOrElse(idx) { 0f } }
        }

        val outputs = FloatArray(outputClassCount)

        if (inputDataType == DataType.FLOAT32) {
            val inBuf = ByteBuffer.allocateDirect(expected * 4).order(ByteOrder.nativeOrder())
            for (v in input) inBuf.putFloat(v)
            inBuf.rewind()

            val outBuf = ByteBuffer.allocateDirect(outputClassCount * 4).order(ByteOrder.nativeOrder())
            outBuf.rewind()
            interpreter.run(inBuf, outBuf)
            outBuf.rewind()
            for (i in 0 until outputClassCount) outputs[i] = outBuf.float
            return outputs
        }

        val inBuf = ByteBuffer.allocateDirect(expected).order(ByteOrder.nativeOrder())
        val inScale = if (inputScale == 0f) 1f else inputScale
        for (v in input) {
            val q = (v / inScale + inputZeroPoint).roundToInt().coerceIn(-128, 127)
            inBuf.put(q.toByte())
        }
        inBuf.rewind()

        if (outputDataType == DataType.FLOAT32) {
            val outBuf = ByteBuffer.allocateDirect(outputClassCount * 4).order(ByteOrder.nativeOrder())
            interpreter.run(inBuf, outBuf)
            outBuf.rewind()
            for (i in 0 until outputClassCount) outputs[i] = outBuf.float
            return outputs
        }

        val outBuf = ByteBuffer.allocateDirect(outputClassCount).order(ByteOrder.nativeOrder())
        interpreter.run(inBuf, outBuf)
        outBuf.rewind()
        val outScaleSafe = if (outputScale == 0f) 1f else outputScale
        for (i in 0 until outputClassCount) {
            val q = outBuf.get().toInt()
            outputs[i] = (q - outputZeroPoint) * outScaleSafe
        }
        return outputs
    }

    private fun buildRaw240(window: ArrayDeque<FloatArray>): FloatArray {
        val out = FloatArray(windowSize * axisCount)
        var pos = 0
        for (row in window) {
            for (i in 0 until axisCount) out[pos++] = row[i]
        }
        return out
    }

    private fun buildSpectral78(window: ArrayDeque<FloatArray>): FloatArray {
        val perAxis = FloatArray(windowSize)
        val out = FloatArray(78)
        var pos = 0

        for (axis in 0 until axisCount) {
            var i = 0
            for (row in window) {
                perAxis[i++] = row[axis]
            }
            val axisFeat = extract13Features(perAxis)
            for (f in axisFeat) out[pos++] = f
        }
        return out
    }

    private fun extract13Features(signal: FloatArray): FloatArray {
        val n = signal.size
        var sum = 0f
        var sumSq = 0f
        for (v in signal) {
            sum += v
            sumSq += v * v
        }
        val mean = sum / n
        val rms = sqrt(sumSq / n)

        var varAcc = 0f
        for (v in signal) {
            val d = v - mean
            varAcc += d * d
        }
        val std = sqrt(varAcc / n)

        val half = n / 2
        val mags = FloatArray(half + 1)
        val freqs = FloatArray(half + 1)
        for (k in 1..half) {
            var re = 0.0
            var im = 0.0
            for (t in signal.indices) {
                val angle = 2.0 * PI * k * t / n
                re += signal[t] * kotlin.math.cos(angle)
                im -= signal[t] * kotlin.math.sin(angle)
            }
            val mag = sqrt((re * re + im * im).toFloat()) / n
            mags[k] = mag
            freqs[k] = k * sampleRateHz / n
        }

        val bands = arrayOf(
            0.1f to 0.5f,
            0.5f to 1.0f,
            1.0f to 2.0f,
            2.0f to 5.0f,
            5.0f to 10.0f,
        )
        val bandPowers = FloatArray(5)
        for (k in 1..half) {
            val freq = freqs[k]
            val power = mags[k] * mags[k]
            for (b in bands.indices) {
                val (f0, f1) = bands[b]
                if (freq >= f0 && freq < f1) {
                    bandPowers[b] += power
                    break
                }
            }
        }

        val peakIdx = (1..half)
            .sortedByDescending { mags[it] }
            .take(3)
        val peakMags = FloatArray(3)
        val peakFreqs = FloatArray(2)
        for (i in peakIdx.indices) {
            peakMags[i] = mags[peakIdx[i]]
            if (i < 2) peakFreqs[i] = freqs[peakIdx[i]]
        }

        return floatArrayOf(
            rms,
            mean,
            std,
            bandPowers[0],
            bandPowers[1],
            bandPowers[2],
            bandPowers[3],
            bandPowers[4],
            peakMags[0],
            peakMags[1],
            peakMags[2],
            peakFreqs[0],
            peakFreqs[1],
        )
    }

    private fun toFusedAxes(sample: ImuSample): FloatArray {
        val accX = sample.ax / 1000f
        val accY = sample.ay / 1000f
        val accZ = sample.az / 1000f

        val mx = sample.mx / 10f
        val my = sample.my / 10f
        val mz = sample.mz / 10f

        val rollRad = atan2(accY.toDouble(), accZ.toDouble()).toFloat()
        val pitchRad = atan2((-accX).toDouble(), sqrt((accY * accY + accZ * accZ).toDouble())).toFloat()

        val mxComp = mx * cos(pitchRad) + mz * sin(pitchRad)
        val myComp = mx * sin(rollRad) * sin(pitchRad) + my * cos(rollRad) - mz * sin(rollRad) * cos(pitchRad)

        var headingDeg = Math.toDegrees(atan2((-myComp).toDouble(), mxComp.toDouble())).toFloat()
        if (headingDeg < 0f) headingDeg += 360f

        val pitchDeg = Math.toDegrees(pitchRad.toDouble()).toFloat()
        val rollDeg = Math.toDegrees(rollRad.toDouble()).toFloat()

        return floatArrayOf(accX, accY, accZ, headingDeg, pitchDeg, rollDeg)
    }

    private fun loadModelFile(context: Context, assetName: String): ByteBuffer {
        context.assets.openFd(assetName).use { afd ->
            afd.createInputStream().channel.use { channel ->
                return channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
            }
        }
    }

    override fun close() {
        interpreter.close()
    }
}
