package com.example.peciwearables.integration.wearable.devices.omi

import android.bluetooth.BluetoothDevice
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.ble.GlassesMicrophoneProfile
import com.example.peciwearables.integration.ble.devices.omi.OmiGlassesBleClientApi
import com.example.peciwearables.integration.wearable.GattProfileSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeOmiGlassesBleClient : OmiGlassesBleClientApi {

    private val _state = MutableStateFlow(BleDeviceState.CONNECTED)
    override val state: StateFlow<BleDeviceState> = _state

    private val _battery = MutableStateFlow(80)
    override val batteryLevel: StateFlow<Int> = _battery

    private val _firmware = MutableStateFlow("1.0.0")
    override val firmwareVersion: StateFlow<String> = _firmware

    private val _gattProfile = MutableStateFlow<GattProfileSnapshot?>(null)
    override val gattProfile: StateFlow<GattProfileSnapshot?> = _gattProfile

    override var onPhotoReceived: ((ByteArray, Int) -> Unit)? = null
    override var onAudioPacket: ((ByteArray) -> Unit)? = null
    override var onAudioCodecId: ((Int) -> Unit)? = null
    override var onCameraData: ((Int, ByteArray) -> Unit)? = null
    override var onCameraStatus: ((Int) -> Unit)? = null
    override var onCameraImageFileReceived: ((ByteArray) -> Unit)? = null
    override var onMicrophoneStatus: ((Int) -> Unit)? = null
    override var onMicrophoneConfig: ((Int, Int, Int) -> Unit)? = null
    override var onImuData: ((ByteArray) -> Unit)? = null
    override var onGlassesQuaternion: ((FloatArray) -> Unit)? = null
    override var onGlassesIpReceived: ((String) -> Unit)? = null
    override var onUdpTxNeeded: ((ByteArray) -> Unit)? = null

    override val supportsWifiControl: Boolean = true
    override val wifiConnectionEnabled: Boolean? = false
    override val isWifiConnected: Boolean? = false
    override val isWifiSecure: Boolean? = true

    // Invocações gravadas
    var connectDeviceCalls = 0
    var disconnectCalls = 0
    var takePictureCalls = 0
    var stopCameraCalls = 0
    var setStreamModeCalls = mutableListOf<Pair<Boolean, Int?>>()
    var updateStreamTargetFpsCalls = mutableListOf<Int>()
    var onSdkStreamImageAssembledCalls = 0
    var startMicrophoneCalls = 0
    var stopMicrophoneCalls = 0
    var setImuCalls = mutableListOf<Pair<Boolean, Int>>()
    var wakeCameraCalls = 0
    var sendWifiEnabledCalls = mutableListOf<Boolean>()
    var sendWifiCredentialsCalls = mutableListOf<Pair<String, String>>()
    var requestWifiInfoCalls = 0
    var onTxRxBytesReceivedCalls = mutableListOf<ByteArray>()
    var applyMicrophoneProfileCalls = mutableListOf<GlassesMicrophoneProfile>()
    var applyCameraSettingsCalls = mutableListOf<Triple<Int?, Int?, Int?>>()

    // Helpers de simulação
    fun simulatePhoto(bytes: ByteArray, orientation: Int) = onPhotoReceived?.invoke(bytes, orientation)
    fun simulateAudioPacket(bytes: ByteArray) = onAudioPacket?.invoke(bytes)
    fun simulateAudioCodecId(id: Int) = onAudioCodecId?.invoke(id)
    fun simulateCameraData(subType: Int, bytes: ByteArray) = onCameraData?.invoke(subType, bytes)
    fun simulateCameraImageFile(bytes: ByteArray) = onCameraImageFileReceived?.invoke(bytes)
    fun simulateImuData(bytes: ByteArray) = onImuData?.invoke(bytes)
    fun simulateQuaternion(q: FloatArray) = onGlassesQuaternion?.invoke(q)
    fun simulateBattery(pct: Int) { _battery.value = pct }
    fun simulateFirmware(fw: String) { _firmware.value = fw }
    fun simulateState(s: BleDeviceState) { _state.value = s }
    fun simulateGattProfile(profile: GattProfileSnapshot?) { _gattProfile.value = profile }

    override fun connectDevice(device: BluetoothDevice) { connectDeviceCalls++ }
    override fun disconnect(keepUdpRouting: Boolean) { disconnectCalls++ }
    override fun takePicture() { takePictureCalls++ }
    override fun stopCamera() { stopCameraCalls++ }
    override fun setStreamMode(enable: Boolean, targetFps: Int?) { setStreamModeCalls += enable to targetFps }
    override fun updateStreamTargetFps(targetFps: Int) { updateStreamTargetFpsCalls += targetFps }
    override fun onSdkStreamImageAssembled() { onSdkStreamImageAssembledCalls++ }
    override fun startMicrophone(sensorRate: Int) { startMicrophoneCalls++ }
    override fun stopMicrophone() { stopMicrophoneCalls++ }
    override fun setImuStreamingEnabled(enabled: Boolean, rateMs: Int) { setImuCalls += enabled to rateMs }
    override fun wakeCamera() { wakeCameraCalls++ }
    override fun sendWifiEnabled(enabled: Boolean) { sendWifiEnabledCalls += enabled }
    override fun sendWifiCredentialsAndEnable(ssid: String, password: String) {
        sendWifiCredentialsCalls += ssid to password
    }
    override fun requestWifiInfo() { requestWifiInfoCalls++ }
    override fun onTxRxBytesReceived(data: ByteArray) { onTxRxBytesReceivedCalls += data }
    override fun applyMicrophoneProfile(profile: GlassesMicrophoneProfile) {
        applyMicrophoneProfileCalls += profile
    }
    override fun applyCameraSettings(resolution: Int?, qualityFactor: Int?, cameraRateMs: Int?) {
        applyCameraSettingsCalls += Triple(resolution, qualityFactor, cameraRateMs)
    }
}
