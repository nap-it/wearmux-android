package com.example.peciwearables.integration.ble

import com.example.peciwearables.integration.protocol.tlv.TlvParser
import com.example.peciwearables.integration.protocol.tlv.TlvWriter
import com.example.peciwearables.integration.protocol.tlv.TxRxMessageType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class OmiRxHandlerCameraLatencyTest {

    @Test
    fun comparesCameraReassemblyBaselineVsOptimized() {
        val frames = 400
        val payloadSize = 256
        val chunkSize = 96

        var baselineStartNs = 0L
        val baselineLatencies = mutableListOf<Long>()
        val baselineParser = BaselineCameraReassembly { _, _ ->
            baselineLatencies += (System.nanoTime() - baselineStartNs)
        }

        repeat(frames) { index ->
            val subMessage = framedCameraSubMessage(
                subType = 3,
                payload = ByteArray(payloadSize) { ((it + index) and 0xFF).toByte() },
            )
            val chunks = splitForReassembly(subMessage, chunkSize)
            baselineStartNs = System.nanoTime()
            for (chunk in chunks) {
                val encoded = TlvWriter.encode(TxRxMessageType.CAMERA_DATA, chunk)
                TlvParser.parseTxRx(encoded, 0, encoded.size) { type, payload, _ ->
                    if (type == TxRxMessageType.CAMERA_DATA) {
                        baselineParser.onCameraDataPayload(payload)
                    }
                }
            }
        }

        var optimizedStartNs = 0L
        val optimizedLatencies = mutableListOf<Long>()
        val handler = OmiRxHandler()
        handler.onCameraData = { _, _ ->
            optimizedLatencies += (System.nanoTime() - optimizedStartNs)
        }
        repeat(frames) { index ->
            val subMessage = framedCameraSubMessage(
                subType = 3,
                payload = ByteArray(payloadSize) { ((it + index) and 0xFF).toByte() },
            )
            val chunks = splitForReassembly(subMessage, chunkSize)
            optimizedStartNs = System.nanoTime()
            for (chunk in chunks) {
                handler.onRxBytes(TlvWriter.encode(TxRxMessageType.CAMERA_DATA, chunk))
            }
        }

        assertEquals(frames, baselineLatencies.size)
        assertEquals(frames, optimizedLatencies.size)

        val baseline = summarizeNs(baselineLatencies)
        val optimized = summarizeNs(optimizedLatencies)

        println("METRIC camera_reassembly_baseline_ms p50=${"%.4f".format(baseline.p50Ms)} p95=${"%.4f".format(baseline.p95Ms)}")
        println("METRIC camera_reassembly_optimized_ms p50=${"%.4f".format(optimized.p50Ms)} p95=${"%.4f".format(optimized.p95Ms)}")
    }

    private data class LatencySummary(val p50Ms: Double, val p95Ms: Double)

    private fun summarizeNs(values: List<Long>): LatencySummary {
        val sorted = values.sorted()
        fun percentile(p: Double): Double {
            val rank = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[rank] / 1_000_000.0
        }
        return LatencySummary(
            p50Ms = percentile(0.50),
            p95Ms = percentile(0.95),
        )
    }

    private fun framedCameraSubMessage(subType: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(3 + payload.size)
        out[0] = (subType and 0xFF).toByte()
        out[1] = (payload.size and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 3, payload.size)
        return out
    }

    private fun split(bytes: ByteArray, chunkSize: Int): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + chunkSize).coerceAtMost(bytes.size)
            chunks += bytes.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }

    private fun splitForReassembly(bytes: ByteArray, chunkSize: Int): List<ByteArray> {
        if (bytes.size <= 1) return listOf(bytes)
        val head = bytes.copyOfRange(0, 1)
        val tail = split(bytes.copyOfRange(1, bytes.size), chunkSize)
        return buildList(1 + tail.size) {
            add(head)
            addAll(tail)
        }
    }

    private class BaselineCameraReassembly(
        private val onCameraData: (subType: Int, data: ByteArray) -> Unit,
    ) {
        private val pending = ByteArrayOutputStream(4096)

        fun onCameraDataPayload(payload: ByteArray) {
            if (payload.isEmpty()) return
            pending.write(payload)

            val buffer = pending.toByteArray()
            val bufferSize = buffer.size
            var offset = 0

            while (offset + 3 <= bufferSize) {
                val subType = buffer[offset].toInt() and 0xFF
                val length = (buffer[offset + 1].toInt() and 0xFF) or
                    ((buffer[offset + 2].toInt() and 0xFF) shl 8)

                if (subType !in 0..5 || length > 32 * 1024) {
                    offset += 1
                    continue
                }

                val total = 3 + length
                if (offset + total > bufferSize) {
                    break
                }

                val dataStart = offset + 3
                val dataEnd = dataStart + length
                val data = buffer.copyOfRange(dataStart, dataEnd)
                onCameraData(subType, data)
                offset = dataEnd
            }

            pending.reset()
            if (offset < bufferSize) {
                pending.write(buffer, offset, bufferSize - offset)
            }
        }
    }
}
