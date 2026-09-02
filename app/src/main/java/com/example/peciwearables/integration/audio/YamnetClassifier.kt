package com.example.peciwearables.integration.audio

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder


class YamnetClassifier private constructor(
    private val interpreter: Interpreter,
    private val classNames: List<String>,
) {


    fun classify(samples: ShortArray, sampleRateHz: Int, topK: Int = 5): List<Pair<String, Float>> {
        val mono16kHz = resampleTo16kHz(samples, sampleRateHz)
        val targetLen = YAMNET_INPUT_SAMPLES
        val input = FloatArray(targetLen)
        val srcLen = mono16kHz.size.coerceAtLeast(1)
        for (i in 0 until targetLen) {
            val srcIdx = (i.toLong() * srcLen / targetLen).toInt().coerceIn(0, srcLen - 1)
            input[i] = mono16kHz[srcIdx] / 32768f
        }


        val outputTensor = interpreter.getOutputTensor(0)
        val outShape = outputTensor.shape()
        val outRank = outShape.size

        return try {
            if (outRank == 2) {
                val patches = outShape[0]
                val classes = outShape[1]
                val out = Array(patches) { FloatArray(classes) }
                interpreter.run(input, out)
                aggregateScores(out, classes, topK)
            } else {
                // fallback: tratar como 1D
                val out = FloatArray(outShape.last())
                interpreter.run(input, out)
                topK(out, topK)
            }
        } catch (e: Exception) {
            Log.w(TAG, "YAMNet inference falhou: ${e.message}")
            emptyList()
        }
    }

    private fun aggregateScores(
        outputs: Array<FloatArray>,
        classes: Int,
        topK: Int,
    ): List<Pair<String, Float>> {
        if (outputs.isEmpty()) return emptyList()
        val avg = FloatArray(classes)
        for (patch in outputs) for (i in 0 until classes) avg[i] += patch[i]
        for (i in 0 until classes) avg[i] /= outputs.size
        return topK(avg, topK)
    }

    private fun topK(scores: FloatArray, k: Int): List<Pair<String, Float>> =
        scores.mapIndexed { i, s -> i to s }
            .sortedByDescending { it.second }
            .take(k)
            .map { (i, s) -> (classNames.getOrNull(i) ?: "class_$i") to s }

    /** Devolve a `SoundCategory` correspondente ao top-1, ou null se
     *  abaixo de `minScore` ou se nenhuma classe mapeia. */
    fun categorize(samples: ShortArray, sampleRateHz: Int, minScore: Float = 0.20f):
        AmbientSoundClassifier.SoundCategory? {
        val top = classify(samples, sampleRateHz, topK = 5)
        for ((label, score) in top) {
            if (score < minScore) continue
            val cat = mapToCategory(label) ?: continue
            return cat
        }
        return null
    }

    private fun resampleTo16kHz(samples: ShortArray, srcRate: Int): ShortArray {
        if (srcRate == TARGET_SAMPLE_RATE) return samples
        val ratio = TARGET_SAMPLE_RATE.toFloat() / srcRate
        val dstLen = (samples.size * ratio).toInt()
        val out = ShortArray(dstLen)
        for (i in 0 until dstLen) {
            val src = (i / ratio).toInt().coerceIn(0, samples.size - 1)
            out[i] = samples[src]
        }
        return out
    }

    fun close() {
        try { interpreter.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "YamnetClassifier"
        private const val MODEL_ASSET = "yamnet.tflite"
        private const val LABELS_ASSET = "yamnet_class_map.csv"
        const val TARGET_SAMPLE_RATE = 16_000
        const val YAMNET_INPUT_SAMPLES = 15_600  // 0.975 s

        fun tryLoad(context: Context): YamnetClassifier? = try {
            val modelBuf = loadAsset(context, MODEL_ASSET) ?: return null
            val labels = loadLabels(context, LABELS_ASSET) ?: return null
            val opts = Interpreter.Options().apply { setNumThreads(2) }
            val interp = Interpreter(modelBuf, opts)
            Log.i(TAG, "YAMNet carregado · ${labels.size} classes · ${modelBuf.capacity()} B")
            YamnetClassifier(interp, labels)
        } catch (e: Exception) {
            Log.w(TAG, "tryLoad falhou: ${e.message}")
            null
        }

        private fun loadAsset(context: Context, name: String): ByteBuffer? = try {
            context.assets.openFd(name).use { afd ->
                val ch = java.io.FileInputStream(afd.fileDescriptor).channel
                ch.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    afd.startOffset, afd.declaredLength,
                )
            }
        } catch (e: Exception) {
            Log.i(TAG, "Asset $name não encontrado — heurística será usada.")
            null
        }

        private fun loadLabels(context: Context, name: String): List<String>? = try {
            BufferedReader(InputStreamReader(context.assets.open(name))).use { br ->
                val all = br.readLines()
                // yamnet_class_map.csv: index,mid,display_name
                // pode ter header
                val header = all.firstOrNull()?.lowercase()
                val rows = if (header?.contains("index") == true) all.drop(1) else all
                rows.map { line ->
                    val parts = line.split(",")
                    parts.getOrNull(2)?.trim()?.trim('"') ?: "class"
                }// Null UDP TX first so the client stops routing commands to UDP
            }
        } catch (_: Exception) { null }

        /**
         * Mapa de classes AudioSet (substring case-insensitive) → categoria
         * interna. Cobre as classes mais úteis para UC4.3.
         */
        private fun mapToCategory(label: String): AmbientSoundClassifier.SoundCategory? {
            val l = label.lowercase()
            return when {
                "siren" in l || "ambulance" in l || "police car" in l || "fire engine" in l ->
                    AmbientSoundClassifier.SoundCategory.SIREN
                "alarm" in l || "smoke detector" in l || "fire alarm" in l || "buzzer" in l ->
                    AmbientSoundClassifier.SoundCategory.ALARM
                "doorbell" in l || "ding-dong" in l ->
                    AmbientSoundClassifier.SoundCategory.DOORBELL
                "knock" in l ->
                    AmbientSoundClassifier.SoundCategory.KNOCK
                "telephone" in l || "phone" in l || "ringtone" in l || "ringer" in l ->
                    AmbientSoundClassifier.SoundCategory.PHONE_RING
                "shout" in l || "yell" in l || "scream" in l || "screaming" in l ->
                    AmbientSoundClassifier.SoundCategory.SHOUT
                else -> null
            }
        }
    }
}
