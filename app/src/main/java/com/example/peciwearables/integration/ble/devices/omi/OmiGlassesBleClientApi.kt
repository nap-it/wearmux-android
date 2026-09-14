package com.example.peciwearables.integration.ble.devices.omi

import android.bluetooth.BluetoothDevice
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.ble.GlassesMicrophoneProfile
import com.example.peciwearables.integration.wearable.GattProfileSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Test seam: subset da API pública do [OmiGlassesBleClient] usado pelo adapter
 * genérico (`OmiGlassesWearableAdapter`/`OmiGlassesWearableSession`).
 *
 * O cliente real continua a expor mais coisas — esta interface declara apenas o
 * que o adapter precisa, para que os testes possam injetar um fake leve sem ter
 * de implementar o cliente todo.
 */
interface OmiGlassesBleClientApi {

    // ── State ──
    val state: StateFlow<BleDeviceState>
    val batteryLevel: StateFlow<Int>
    val firmwareVersion: StateFlow<String>
    val gattProfile: StateFlow<GattProfileSnapshot?>

    // ── Callbacks RX (atribuíveis pelo adapter) ──
    var onPhotoReceived: ((jpegBytes: ByteArray, orientation: Int) -> Unit)?
    var onAudioPacket: ((audioData: ByteArray) -> Unit)?
    var onAudioCodecId: ((Int) -> Unit)?
    var onCameraData: ((Int, ByteArray) -> Unit)?
    var onCameraStatus: ((Int) -> Unit)?
    var onCameraImageFileReceived: ((ByteArray) -> Unit)?
    var onMicrophoneStatus: ((Int) -> Unit)?
    var onMicrophoneConfig: ((sampleRate: Int, bitDepth: Int, codecId: Int) -> Unit)?
    var onImuData: ((ByteArray) -> Unit)?
    var onGlassesQuaternion: ((FloatArray) -> Unit)?
    var onGlassesIpReceived: ((String) -> Unit)?
    var onUdpTxNeeded: ((ByteArray) -> Unit)?

    val supportsWifiControl: Boolean
    val wifiConnectionEnabled: Boolean?
    val isWifiConnected: Boolean?
    val isWifiSecure: Boolean?

    // ── Conexão ──
    fun connectDevice(device: BluetoothDevice)
    fun disconnect(keepUdpRouting: Boolean = false)

    // ── Comandos ──
    fun takePicture()
    fun stopCamera()
    fun setStreamMode(enable: Boolean, targetFps: Int? = null)
    fun updateStreamTargetFps(targetFps: Int)
    fun onSdkStreamImageAssembled()
    fun startMicrophone(sensorRate: Int = 0)
    fun stopMicrophone()
    fun setImuStreamingEnabled(enabled: Boolean, rateMs: Int = 20)
    fun wakeCamera()
    fun sendWifiEnabled(enabled: Boolean)
    fun sendWifiCredentialsAndEnable(ssid: String, password: String)
    fun requestWifiInfo()
    fun onTxRxBytesReceived(data: ByteArray)
    fun applyMicrophoneProfile(profile: GlassesMicrophoneProfile)
    fun applyCameraSettings(
        resolution: Int? = null,
        qualityFactor: Int? = null,
        cameraRateMs: Int? = null,
    )

    /**
     * Liga (ou desliga, com `null`) o agregador de [CameraMetrics] ao [FileTransferHandler]
     * interno deste cliente, permitindo reportar CRC/timeouts/overshoot via métricas
     * centralizadas. Default null (no-op).
     */
    fun attachCameraMetrics(metrics: com.example.peciwearables.integration.image.camera.CameraMetrics?) {
        // Default implementation: no-op para fakes que não usam métricas.
    }
}
