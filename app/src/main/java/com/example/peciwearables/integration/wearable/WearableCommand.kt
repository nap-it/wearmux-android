package com.example.peciwearables.integration.wearable

/**
 * Comandos genéricos enviáveis a uma [WearableSession]. Cada adapter traduz para
 * a chamada específica do client BLE/protocolo do dispositivo.
 */
sealed interface WearableCommand {

    data object TakePicture : WearableCommand

    data class StartVideoStream(val targetFps: Int? = null) : WearableCommand
    data object StopVideoStream : WearableCommand

    data object StartAudioStream : WearableCommand
    data object StopAudioStream : WearableCommand

    data class SetImuStreaming(val enabled: Boolean, val rateMs: Int? = null) : WearableCommand

    data class Vibrate(val durationMs: Int, val intensity: Float? = null) : WearableCommand

    data class ConfigureWifi(val ssid: String, val password: String) : WearableCommand
    data object ConnectViaWifi : WearableCommand

    data object Disconnect : WearableCommand
}
