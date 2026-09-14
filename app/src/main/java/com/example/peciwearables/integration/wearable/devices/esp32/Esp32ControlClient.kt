package com.example.peciwearables.integration.wearable.devices.esp32

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class Esp32Status(
    val status: String,
    val psram: Boolean,
    val udpActive: Boolean
)

open class Esp32ControlClient(
    private val ip: String = "192.168.4.1",
    private val client: OkHttpClient = OkHttpClient()
) {

    private val baseUrl = "http://$ip"

    open suspend fun checkStatus(): Esp32Status? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/status")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                parseStatus(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    open suspend fun startUdpStream(port: Int, ip: String? = null): Boolean = withContext(Dispatchers.IO) {
        val url = if (ip != null) "$baseUrl/udp_stream?port=$port&ip=$ip" else "$baseUrl/udp_stream?port=$port"
        val request = Request.Builder()
            .url(url)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    open suspend fun stopUdpStream(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/udp_stop")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    open suspend fun setQuality(quality: Int): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/control?quality=$quality")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun parseStatus(json: String): Esp32Status? {
        val statusMatch = """"status"\s*:\s*"([^"]+)"""".toRegex().find(json)
        val psramMatch = """"psram"\s*:\s*(true|false)""".toRegex().find(json)
        val udpMatch = """"udp_active"\s*:\s*(true|false)""".toRegex().find(json)

        if (statusMatch != null) {
            return Esp32Status(
                status = statusMatch.groupValues[1],
                psram = psramMatch?.groupValues?.get(1) == "true",
                udpActive = udpMatch?.groupValues?.get(1) == "true"
            )
        }
        return null
    }
}
