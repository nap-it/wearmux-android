package com.example.peciwearables.integration.bridge

import android.util.Log
import com.example.peciwearables.integration.protocol.QuaternionToEuler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Reporta o pose dos óculos (yaw/pitch/roll do quaternião BNO085) para
 * `POST {baseUrl}/inputs/glasses_pose` a 4 Hz quando há quaternião fresco.
 */
class GlassesPoseReporter(
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val latestQuaternion: () -> FloatArray?,
    private val latestQuaternionMs: () -> Long,
) {
    private var job: Job? = null

    fun start(baseUrl: String) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            val mediaType = "application/json".toMediaType()
            var nextAllowedAttemptMs = 0L
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    if (now < nextAllowedAttemptMs || now - latestQuaternionMs() > 1_500L) {
                        delay(250L); continue
                    }
                    val euler = latestQuaternion()?.let(QuaternionToEuler::fromQuaternion)
                    val yaw = euler?.yaw
                    if (yaw != null) {
                        val json = buildString {
                            append("""{"hub_timestamp":${System.currentTimeMillis()}""")
                            append(""","yaw_deg":$yaw""")
                            euler.pitch.let { append(""","pitch_deg":$it""") }
                            euler.roll.let  { append(""","roll_deg":$it""") }
                            append(",\"source\":\"bno085_quaternion_relative_app_axes\"")
                            append("}")
                        }
                        val req = Request.Builder()
                            .url("$baseUrl/inputs/glasses_pose")
                            .post(json.toRequestBody(mediaType))
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) nextAllowedAttemptMs = now + 1_000L
                        }
                    }
                } catch (t: Throwable) {
                    nextAllowedAttemptMs = System.currentTimeMillis() + 1_000L
                    Log.d("GlassesPoseReporter", "tick: ${t.message}")
                }
                delay(250L)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
    }
}
