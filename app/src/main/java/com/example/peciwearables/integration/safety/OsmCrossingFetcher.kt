package com.example.peciwearables.integration.safety

import android.util.Log
import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit


class OsmCrossingFetcher {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Busca passadeiras num raio. Devolve `CrossingZone` com nome derivado
     * das tags (ex: "Crossing zebra"). IDs estáveis baseados em OSM ID
     * para que múltiplos refreshes não dupliquem entradas.
     */
    suspend fun fetchCrossings(
        gps: PhoneGpsLocation,
        radiusMeters: Int = 500,
        zoneRadiusMeters: Float = 12f,
    ): List<CrossingZone> = withContext(Dispatchers.IO) {
        val query = buildOverpassQuery(gps.latitude, gps.longitude, radiusMeters)
        val endpoints = listOf(
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass-api.de/api/interpreter",
        )
        for (endpoint in endpoints) {
            val zones = tryFetch(endpoint, query, zoneRadiusMeters)
            if (zones != null) return@withContext zones
        }
        emptyList()
    }

    private fun tryFetch(endpoint: String, query: String, zoneRadius: Float): List<CrossingZone>? {
        return try {
            val mediaType = "application/x-www-form-urlencoded".toMediaType()
            val payload = "data=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val body = payload.toRequestBody(mediaType)
            val req = Request.Builder()
                .url(endpoint)
                .post(body)
                .header("User-Agent", "peci-wearables/1.0 (academic project)")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Overpass $endpoint → HTTP ${resp.code}")
                    return null
                }
                val text = resp.body?.string() ?: return null
                parse(text, zoneRadius)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Overpass $endpoint falhou: ${e.message}")
            null
        }
    }

    private fun buildOverpassQuery(lat: Double, lon: Double, radiusM: Int): String =
        """
        [out:json][timeout:10];
        (
          node["highway"="crossing"](around:$radiusM,$lat,$lon);
          way["highway"="crossing"](around:$radiusM,$lat,$lon);
          node["crossing"~"marked|zebra|traffic_signals|uncontrolled"](around:$radiusM,$lat,$lon);
        );
        out center tags;
        """.trimIndent()

    private fun parse(json: String, zoneRadius: Float): List<CrossingZone> {
        val root = JSONObject(json)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<CrossingZone>()
        val seen = HashSet<String>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val type = el.optString("type")
            val id = el.optLong("id")
            val (lat, lon) = when (type) {
                "node" -> el.optDouble("lat", Double.NaN) to el.optDouble("lon", Double.NaN)
                "way" -> {
                    val center = el.optJSONObject("center") ?: continue
                    center.optDouble("lat", Double.NaN) to center.optDouble("lon", Double.NaN)
                }
                else -> continue
            }
            if (lat.isNaN() || lon.isNaN()) continue

            val key = "$type/$id"
            if (!seen.add(key)) continue

            val tags = el.optJSONObject("tags")
            val crossingType = tags?.optString("crossing")?.takeIf { it.isNotBlank() }
            val name = when {
                tags?.optString("name")?.isNotBlank() == true -> tags.getString("name")
                crossingType != null -> "Passadeira ($crossingType)"
                else -> "Passadeira"
            }

            out += CrossingZone(
                id = "osm:$key",
                name = name,
                latitude = lat,
                longitude = lon,
                radiusMeters = zoneRadius,
            )
        }
        Log.i(TAG, "Overpass devolveu ${out.size} passadeiras")
        return out
    }

    companion object {
        private const val TAG = "OsmCrossingFetcher"
    }
}
