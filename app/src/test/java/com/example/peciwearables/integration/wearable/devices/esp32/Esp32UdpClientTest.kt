package com.example.peciwearables.integration.wearable.devices.esp32

import com.example.peciwearables.integration.protocol.ImageChunk
import com.example.peciwearables.integration.protocol.ImuPayload
import com.example.peciwearables.integration.protocol.ImuSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class Esp32UdpClientTest {

    @Test
    fun `parses IMU packet correctly`() {
        val client = Esp32UdpClient(8554)
        var receivedPayload: ImuPayload? = null
        client.onImuPayload = { payload -> receivedPayload = payload }

        // Construct 22-byte IMU packet
        // "IMU\0" (4 bytes)
        // ax, ay, az (6 bytes)
        // gx, gy, gz (6 bytes)
        // mx, my, mz (6 bytes)
        val bb = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("IMU\u0000".toByteArray(Charsets.US_ASCII))
        // accel
        bb.putShort(1000)
        bb.putShort(2000)
        bb.putShort(3000)
        // gyro (0.1 dps)
        bb.putShort(10)
        bb.putShort(20)
        bb.putShort(30)
        // mag (0.1 uT)
        bb.putShort(100)
        bb.putShort(200)
        bb.putShort(300)

        client.processDatagramForTesting(bb.array())

        assertEquals(1, receivedPayload?.sampleCount)
        val sample = receivedPayload?.samples?.first()!!
        assertEquals(1000.toShort(), sample.ax)
        assertEquals(2000.toShort(), sample.ay)
        assertEquals(3000.toShort(), sample.az)
        
        // Gyro is converted from 0.1 dps to mdps (multiplied by 100)
        assertEquals(1000.toShort(), sample.gx)
        assertEquals(2000.toShort(), sample.gy)
        assertEquals(3000.toShort(), sample.gz)
        
        assertEquals(100.toShort(), sample.mx)
        assertEquals(200.toShort(), sample.my)
        assertEquals(300.toShort(), sample.mz)
        assertEquals(ImuSample.MASK_ACCEL or ImuSample.MASK_GYRO or ImuSample.MASK_MAG, receivedPayload?.sensorMask)
    }

    @Test
    fun `reassembles image fragments`() = runTest {
        val client = Esp32UdpClient(8554)
        val chunks = mutableListOf<ImageChunk>()
        client.onImageChunk = { chunk -> chunks.add(chunk) }

        // Build a minimal valid JPEG: SOI + content + EOI
        val jpegContent = ByteArray(2996) { 0x00 }
        val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + jpegContent + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val frag1Payload = imageBytes.copyOfRange(0, 1500)
        val frag2Payload = imageBytes.copyOfRange(1500, imageBytes.size)

        // Fragment 1
        val bb1 = ByteBuffer.allocate(8 + frag1Payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb1.putShort(42)
        bb1.putShort(0)
        bb1.putShort(2)
        bb1.putShort(frag1Payload.size.toShort())
        bb1.put(frag1Payload)

        // Fragment 2
        val bb2 = ByteBuffer.allocate(8 + frag2Payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb2.putShort(42)
        bb2.putShort(1)
        bb2.putShort(2)
        bb2.putShort(frag2Payload.size.toShort())
        bb2.put(frag2Payload)

        client.processDatagramForTesting(bb2.array()) // Out of order!
        assertEquals(0, chunks.size) // Not complete yet

        client.processDatagramForTesting(bb1.array())
        assertEquals(1, chunks.size) // Complete!

        val chunk = chunks[0]
        assertEquals(42L, chunk.frameId)
        assertArrayEquals(imageBytes, chunk.chunkBytes)
    }

    @Test
    fun `discards incomplete frame on new frame ID`() = runTest {
        val client = Esp32UdpClient(8554)
        val chunks = mutableListOf<ImageChunk>()
        client.onImageChunk = { chunk -> chunks.add(chunk) }

        // Frame 10, Frag 0 of 2
        val bb1 = ByteBuffer.allocate(8 + 10).order(ByteOrder.LITTLE_ENDIAN)
        bb1.putShort(10)
        bb1.putShort(0)
        bb1.putShort(2)
        bb1.putShort(10)
        bb1.put(ByteArray(10))

        client.processDatagramForTesting(bb1.array())
        assertEquals(0, chunks.size)

        // Frame 11 comes in (single fragment, valid JPEG), overriding Frame 10
        val minJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0xFF.toByte(), 0xD9.toByte())
        val bb2 = ByteBuffer.allocate(8 + minJpeg.size).order(ByteOrder.LITTLE_ENDIAN)
        bb2.putShort(11)
        bb2.putShort(0)
        bb2.putShort(1)
        bb2.putShort(minJpeg.size.toShort())
        bb2.put(minJpeg)

        client.processDatagramForTesting(bb2.array())
        assertEquals(1, chunks.size)
        assertEquals(11L, chunks[0].frameId)
    }
}
