package com.example.peciwearables.integration.wearable.devices.esp32

import android.util.Log
import com.example.peciwearables.integration.protocol.ImageChunk
import com.example.peciwearables.integration.protocol.ImuPayload
import com.example.peciwearables.integration.protocol.ImuSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder

open class Esp32UdpClient(private val port: Int = 8554) {

    companion object {
        private const val TAG = "Esp32UdpClient"
        private const val MAX_PACKET_SIZE = 1500
        private const val FRAME_ASSEMBLY_TIMEOUT_MS = 600L
        private val IMU_MAGIC = "IMU\u0000".toByteArray(Charsets.US_ASCII)
    }

    var onImageChunk: ((ImageChunk) -> Unit)? = null
    var onImuPayload: ((ImuPayload) -> Unit)? = null

    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null

    // Defragmentation state
    private var currentFrameId: Int = -1
    private var currentFragments = arrayOfNulls<ByteArray>(0)
    private var expectedFragments: Int = 0
    private var fragmentsReceived: Int = 0

    private var keepaliveJob: Job? = null

    open fun startListening(scope: CoroutineScope, esp32Ip: String? = null) {
        if (socket?.isBound == true && !socket!!.isClosed) return

        try {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
                soTimeout = 0 // blocking
                try {
                    receiveBufferSize = 1024 * 1024 // 1MB buffer
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set UDP receive buffer: ${e.message}")
                }
            }
        } catch (e: SocketException) {
            Log.e(TAG, "Failed to bind UDP socket: ${e.message}")
            return
        }

        receiveJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(MAX_PACKET_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)
            var lastStartedAt = 0L

            while (isActive) {
                try {
                    socket?.receive(packet) ?: break
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    
                    if (data.size == 22 && data.take(4).toByteArray().contentEquals(IMU_MAGIC)) {
                        parseImu(data)
                        continue
                    }
                    
                    if (data.size < 8) continue
                    
                    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    val frameId = bb.short.toInt() and 0xFFFF
                    val fragIdx = bb.short.toInt() and 0xFFFF
                    val totalFrags = bb.short.toInt() and 0xFFFF
                    val payloadLen = bb.short.toInt() and 0xFFFF
                    
                    if (totalFrags <= 0 || fragIdx >= totalFrags) continue
                    if (8 + payloadLen > data.size) continue

                    val now = System.currentTimeMillis()

                    val isNewFrame = currentFrameId == -1 || 
                                     (frameId > currentFrameId && frameId - currentFrameId < 32768) ||
                                     (frameId < currentFrameId && currentFrameId - frameId > 32768)
                    
                    val isStale = currentFrameId != -1 && (now - lastStartedAt > FRAME_ASSEMBLY_TIMEOUT_MS)

                    if (isNewFrame || isStale) {
                        currentFrameId = frameId
                        expectedFragments = totalFrags
                        currentFragments = arrayOfNulls(totalFrags)
                        fragmentsReceived = 0
                        lastStartedAt = now
                    }

                    if (frameId != currentFrameId) continue
                    if (totalFrags != expectedFragments) continue

                    if (currentFragments[fragIdx] == null) {
                        val payload = ByteArray(payloadLen)
                        System.arraycopy(data, 8, payload, 0, payloadLen)
                        currentFragments[fragIdx] = payload
                        fragmentsReceived++

                        if (fragmentsReceived == expectedFragments) {
                            assembleAndEmitFrame(frameId)
                        }
                    }
                } catch (e: SocketException) {
                    if (isActive) Log.e(TAG, "Socket error: ${e.message}")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Receive error: ${e.message}")
                }
            }
        }

        if (esp32Ip != null) {
            startKeepalive(scope, esp32Ip)
        }
    }

    private fun startKeepalive(scope: CoroutineScope, esp32Ip: String) {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch(Dispatchers.IO) {
            val address = java.net.InetAddress.getByName(esp32Ip)
            val dummyData = byteArrayOf(0, 0, 0, 0)
            val packet = DatagramPacket(dummyData, dummyData.size, address, port)

            while (isActive) {
                try {
                    socket?.send(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "Keepalive send error: ${e.message}")
                }
                kotlinx.coroutines.delay(5000) // Every 5 seconds
            }
        }
    }

    open fun stop() {
        receiveJob?.cancel()
        keepaliveJob?.cancel()
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket: ${e.message}")
        }
        socket = null
        currentFragments = arrayOfNulls(0)
    }

    internal fun processDatagramForTesting(data: ByteArray) {
        if (data.size == 22 && data.take(4).toByteArray().contentEquals(IMU_MAGIC)) {
            parseImu(data)
            return
        }
        if (data.size < 8) return
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val frameId = bb.short.toInt() and 0xFFFF
        val fragIdx = bb.short.toInt() and 0xFFFF
        val totalFrags = bb.short.toInt() and 0xFFFF
        val payloadLen = bb.short.toInt() and 0xFFFF
        if (totalFrags <= 0 || fragIdx >= totalFrags) return
        if (8 + payloadLen > data.size) return
        val isNewFrame = currentFrameId == -1 ||
            (frameId > currentFrameId && frameId - currentFrameId < 32768) ||
            (frameId < currentFrameId && currentFrameId - frameId > 32768)
        if (isNewFrame) {
            currentFrameId = frameId
            expectedFragments = totalFrags
            currentFragments = arrayOfNulls(totalFrags)
            fragmentsReceived = 0
        }
        if (frameId != currentFrameId || totalFrags != expectedFragments) return
        if (currentFragments[fragIdx] == null) {
            val payload = ByteArray(payloadLen)
            System.arraycopy(data, 8, payload, 0, payloadLen)
            currentFragments[fragIdx] = payload
            fragmentsReceived++
            if (fragmentsReceived == expectedFragments) assembleAndEmitFrame(frameId)
        }
    }

    private fun parseImu(data: ByteArray) {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4) // Skip "IMU\0"

        val ax = bb.short
        val ay = bb.short
        val az = bb.short

        // Firmware gyro is 0.1 dps, Android expects mdps (0.001 dps) => multiply by 100
        val gx = (bb.short * 100).toShort()
        val gy = (bb.short * 100).toShort()
        val gz = (bb.short * 100).toShort()

        // Mag is 0.1 uT in both (assuming firmware 0.1 uT and Android uses same)
        val mx = bb.short
        val my = bb.short
        val mz = bb.short

        val sample = ImuSample(
            ax, ay, az,
            gx, gy, gz,
            mx, my, mz,
            ShortArray(8)
        )

        val payload = ImuPayload(
            sensorMask = ImuSample.MASK_ACCEL or ImuSample.MASK_GYRO or ImuSample.MASK_MAG,
            sampleCount = 1,
            samplePeriodUs = 0,
            baseTimestampUs = System.currentTimeMillis() * 1000, // Or elapsedRealtimeNanos
            samples = listOf(sample)
        )

        onImuPayload?.invoke(payload)
    }

    private fun extractJpegPayload(buf: ByteArray): ByteArray? {
        var soi = -1
        for (i in 0 until buf.size - 1) {
            if (buf[i] == 0xFF.toByte() && buf[i + 1] == 0xD8.toByte()) {
                soi = i
                break
            }
        }
        if (soi == -1) return null

        var eoi = -1
        for (i in buf.size - 1 downTo 1) {
            if (buf[i - 1] == 0xFF.toByte() && buf[i] == 0xD9.toByte()) {
                eoi = i
                break
            }
        }
        if (eoi == -1 || eoi <= soi) return null

        val len = eoi + 1 - soi
        val result = ByteArray(len)
        System.arraycopy(buf, soi, result, 0, len)
        return result
    }

    private fun assembleAndEmitFrame(frameId: Int) {
        var totalSize = 0
        for (frag in currentFragments) {
            if (frag == null) return // Shouldn't happen if fragmentsReceived == expectedFragments
            totalSize += frag.size
        }

        val fullImage = ByteArray(totalSize)
        var offset = 0
        for (frag in currentFragments) {
            System.arraycopy(frag!!, 0, fullImage, offset, frag.size)
            offset += frag.size
        }

        val cleanJpeg = extractJpegPayload(fullImage) ?: return

        val chunk = ImageChunk(
            imgFormat = ImageChunk.FORMAT_JPEG,
            width = 640,  // Defaults
            height = 480,
            frameId = frameId.toLong(),
            chunkIdx = 0,
            chunkCount = 1,
            totalBytes = cleanJpeg.size,
            chunkBytes = cleanJpeg
        )

        onImageChunk?.invoke(chunk)

        // Clear state
        currentFragments = arrayOfNulls(0)
        fragmentsReceived = 0
    }
}
