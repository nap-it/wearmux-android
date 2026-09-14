package com.example.peciwearables.integration.wearable.devices.esp32

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Esp32ControlClientTest {

    private class FakeInterceptor(val responseBody: String, val statusCode: Int = 200) : Interceptor {
        var lastRequestUrl: String = ""

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            lastRequestUrl = request.url.toString()

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(statusCode)
                .message(if (statusCode == 200) "OK" else "Error")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    @Test
    fun `checkStatus parses success response correctly`() = runBlocking {
        val interceptor = FakeInterceptor("""{"status":"ok","psram":true,"udp_active":false}""")
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val esp32Client = Esp32ControlClient("192.168.4.1", client)

        val status = esp32Client.checkStatus()

        assertEquals("http://192.168.4.1/status", interceptor.lastRequestUrl)
        assertTrue(status != null)
        assertEquals("ok", status?.status)
        assertTrue(status?.psram ?: false)
        assertFalse(status?.udpActive ?: true)
    }

    @Test
    fun `checkStatus handles failure`() = runBlocking {
        val interceptor = FakeInterceptor("Not Found", 404)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val esp32Client = Esp32ControlClient("192.168.4.1", client)

        val status = esp32Client.checkStatus()

        assertEquals("http://192.168.4.1/status", interceptor.lastRequestUrl)
        assertTrue(status == null)
    }

    @Test
    fun `startUdpStream makes correct request`() = runBlocking {
        val interceptor = FakeInterceptor("""{"status":"streaming"}""")
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val esp32Client = Esp32ControlClient("192.168.4.1", client)

        val result = esp32Client.startUdpStream(8554)

        assertEquals("http://192.168.4.1/udp_stream?port=8554", interceptor.lastRequestUrl)
        assertTrue(result)
    }

    @Test
    fun `stopUdpStream makes correct request`() = runBlocking {
        val interceptor = FakeInterceptor("OK")
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val esp32Client = Esp32ControlClient("192.168.4.1", client)

        val result = esp32Client.stopUdpStream()

        assertEquals("http://192.168.4.1/udp_stop", interceptor.lastRequestUrl)
        assertTrue(result)
    }

    @Test
    fun `setQuality makes correct request`() = runBlocking {
        val interceptor = FakeInterceptor("OK")
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val esp32Client = Esp32ControlClient("192.168.4.1", client)

        val result = esp32Client.setQuality(30)

        assertEquals("http://192.168.4.1/control?quality=30", interceptor.lastRequestUrl)
        assertTrue(result)
    }
}
