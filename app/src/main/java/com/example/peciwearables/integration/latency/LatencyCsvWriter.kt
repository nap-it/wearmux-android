package com.example.peciwearables.integration.latency

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes latency samples to CSV files, one per pipeline.
 *
 * Thread-safe: each file has its own PrintWriter.
 * Append mode: CSVs accumulate across app sessions.
 * Auto-header: written the first time a file is created or is empty.
 *
 * Usage:
 *   csvWriter.append(
 *       filename = "yolo_latency.csv",
 *       header   = "trace_id,hub_timestamp,http_rtt_ms,...",
 *       row      = "YOLO-1,1748900000,134.2,..."
 *   )
 *
 * Instantiate with getExternalFilesDir("benchmarks") in WearableService.
 */
class LatencyCsvWriter(private val dir: File) {

    private val writers = ConcurrentHashMap<String, PrintWriter>()

    init {
        dir.mkdirs()
        Log.i(TAG, "CSV benchmark dir: ${dir.absolutePath}")
    }

    /**
     * Appends [row] to [filename] inside [dir].
     * Writes [header] first if the file does not exist or is empty.
     */
    fun append(filename: String, header: String, row: String) {
        try {
            val writer = writers.getOrPut(filename) {
                val f = File(dir, filename)
                val needsHeader = !f.exists() || f.length() == 0L
                PrintWriter(FileWriter(f, true), false).also {
                    if (needsHeader) {
                        it.println(header)
                    }
                }
            }
            writer.println(row)
            writer.flush()
        } catch (e: Exception) {
            Log.w(TAG, "CSV write failed ($filename): ${e.message}")
        }
    }

    /** Closes all open writers. Call from Service onDestroy or cleanupRuntimeResources. */
    fun close() {
        writers.values.forEach { runCatching { it.close() } }
        writers.clear()
    }

    companion object {
        private const val TAG = "LatencyCsvWriter"
    }
}
