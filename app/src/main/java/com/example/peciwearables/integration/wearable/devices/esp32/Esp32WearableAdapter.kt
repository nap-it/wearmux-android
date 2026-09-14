package com.example.peciwearables.integration.wearable.devices.esp32

import com.example.peciwearables.integration.wearable.WearableAdapter
import com.example.peciwearables.integration.wearable.WearableConnectedDeviceProfile
import com.example.peciwearables.integration.wearable.WearableRealtimeSink
import com.example.peciwearables.integration.wearable.WearableScanCandidate
import com.example.peciwearables.integration.wearable.WearableSession
import kotlinx.coroutines.CoroutineScope

class Esp32WearableAdapter(
    private val realtimeSink: WearableRealtimeSink = WearableRealtimeSink.NONE
) : WearableAdapter {

    override val id: String = ADAPTER_ID

    companion object {
        const val ADAPTER_ID = "esp32-cam"
    }

    override fun probableMatch(scan: WearableScanCandidate): Boolean {
        // We only match synthetic candidates specifically created for this manual IP connection.
        val lowerName = scan.displayName.lowercase()
        return scan.id.raw.startsWith("esp32-cam-") || lowerName.contains("esp32-cam")
    }

    override fun confirmMatch(profile: WearableConnectedDeviceProfile): Boolean {
        return probableMatch(profile.scan)
    }

    override suspend fun createSession(
        profile: WearableConnectedDeviceProfile,
        scope: CoroutineScope
    ): WearableSession {
        return Esp32WearableSession(
            id = profile.id,
            scope = scope,
            realtimeSink = realtimeSink
        )
    }

    override suspend fun connectAndCreateSession(
        scan: WearableScanCandidate,
        scope: CoroutineScope
    ): Result<WearableSession> {
        // Synthetic bridge for manual connection.
        // It bypasses the normal BLE connect flow and jumps straight to Session.
        val profile = WearableConnectedDeviceProfile(
            scan = scan,
            discoveredServices = emptySet(),
            discoveredCharacteristics = emptySet(),
            firmwareVersion = null,
            deviceName = scan.displayName
        )
        val session = createSession(profile, scope) as Esp32WearableSession
        val success = session.connect()
        return if (success) {
            Result.success(session)
        } else {
            Result.failure(Exception("Failed to connect to ESP32 REST API"))
        }
    }
}
