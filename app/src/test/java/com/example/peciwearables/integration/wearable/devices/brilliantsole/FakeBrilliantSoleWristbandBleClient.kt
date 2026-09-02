package com.example.peciwearables.integration.wearable.devices.brilliantsole

import android.bluetooth.BluetoothDevice
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.ble.devices.brilliantsole.BrilliantSoleWristbandBleClientApi
import com.example.peciwearables.integration.protocol.ImuPayload
import com.example.peciwearables.integration.protocol.ImuSample
import com.example.peciwearables.integration.wearable.GattProfileSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeBrilliantSoleWristbandBleClient : BrilliantSoleWristbandBleClientApi {

    private val _state = MutableStateFlow(BleDeviceState.CONNECTED)
    override val state: StateFlow<BleDeviceState> = _state

    private val _battery = MutableStateFlow(70)
    override val batteryLevel: StateFlow<Int> = _battery

    private val _firmware = MutableStateFlow("2.0.0")
    override val firmwareVersion: StateFlow<String> = _firmware

    private val _deviceName = MutableStateFlow("BrilliantSole")
    override val deviceName: StateFlow<String> = _deviceName

    private val _gattProfile = MutableStateFlow<GattProfileSnapshot?>(null)
    override val gattProfile: StateFlow<GattProfileSnapshot?> = _gattProfile

    override var onImuSample: ((ImuSample, Int, Long) -> Unit)? = null
    override var onTfliteInference: ((String, Float, Long, FloatArray) -> Unit)? = null

    var connectDeviceCalls = 0
    var disconnectCalls = 0
    val setSensorsConfigCalls = mutableListOf<Int>()
    val sendHapticCalls = mutableListOf<Pair<Int, Int>>()
    val sendWaveformCalls = mutableListOf<Triple<Float, Int, Int>>()
    var sendStopAlertVibrationCalls = 0
    var sendGoAlertVibrationCalls = 0
    val uploadTfliteAssetCalls = mutableListOf<String>()
    val setTfliteSampleRateCalls = mutableListOf<Int>()
    val setTfliteTaskCalls = mutableListOf<Int>()
    val setTfliteCaptureDelayCalls = mutableListOf<Int>()
    val setTfliteSensorTypesCalls = mutableListOf<ByteArray>()
    val setTfliteThresholdCalls = mutableListOf<Float>()
    var enableTfliteInferencingCalls = 0
    var disableTfliteInferencingCalls = 0

    fun simulateImuSample(
        ax: Short = 100, ay: Short = 0, az: Short = 0,
        gx: Short = 0, gy: Short = 0, gz: Short = 0,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val sample = ImuSample(ax, ay, az, gx, gy, gz, 0, 0, 0, ShortArray(8))
        onImuSample?.invoke(sample, 0, timestampMs)
    }

    fun simulateInference(label: String, score: Float, values: FloatArray = floatArrayOf()) {
        onTfliteInference?.invoke(label, score, System.currentTimeMillis(), values)
    }

    fun simulateBattery(pct: Int) { _battery.value = pct }
    fun simulateState(s: BleDeviceState) { _state.value = s }
    fun simulateGattProfile(profile: GattProfileSnapshot?) { _gattProfile.value = profile }

    override fun connectDevice(device: BluetoothDevice) { connectDeviceCalls++ }
    override fun disconnect() { disconnectCalls++ }
    override fun setSensorsConfig(rateMs: Int, includeMagnetometer: Boolean, includePressure: Boolean, includeLinearAcceleration: Boolean) {
        setSensorsConfigCalls += rateMs
    }
    override fun sendHapticCommand(effectIndex: Int, locationBitmask: Int) {
        sendHapticCalls += effectIndex to locationBitmask
    }
    override fun sendWaveformVibration(amplitude: Float, durationMs: Int, locationBitmask: Int) {
        sendWaveformCalls += Triple(amplitude, durationMs, locationBitmask)
    }
    override fun sendStopAlertVibration() { sendStopAlertVibrationCalls++ }
    override fun sendGoAlertVibration() { sendGoAlertVibrationCalls++ }
    override suspend fun uploadTfliteAsset(assetName: String): Boolean {
        uploadTfliteAssetCalls += assetName
        return true
    }
    override fun setTfliteSampleRate(sampleRateHz: Int) { setTfliteSampleRateCalls += sampleRateHz }
    override fun setTfliteTask(taskIndex: Int) { setTfliteTaskCalls += taskIndex }
    override fun setTfliteCaptureDelay(captureDelayMs: Int) { setTfliteCaptureDelayCalls += captureDelayMs }
    override fun setTfliteSensorTypes(sensorTypeEnums: ByteArray) { setTfliteSensorTypesCalls += sensorTypeEnums }
    override fun setTfliteThreshold(threshold: Float) { setTfliteThresholdCalls += threshold }
    override fun enableTfliteInferencing() { enableTfliteInferencingCalls++ }
    override fun disableTfliteInferencing() { disableTfliteInferencingCalls++ }
}
