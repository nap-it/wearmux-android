package com.example.peciwearables.integration.ble.devices.brilliantsole

import android.bluetooth.BluetoothDevice
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.protocol.ImuSample
import com.example.peciwearables.integration.wearable.GattProfileSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Test seam: subset da API pública do [BrilliantSoleWristbandBleClient] usado
 * pelo adapter genérico.
 */
interface BrilliantSoleWristbandBleClientApi {

    val state: StateFlow<BleDeviceState>
    val batteryLevel: StateFlow<Int>
    val firmwareVersion: StateFlow<String>
    val deviceName: StateFlow<String>
    val gattProfile: StateFlow<GattProfileSnapshot?>

    var onImuSample: ((sample: ImuSample, timestampRaw16: Int, timestampEstimatedMs: Long) -> Unit)?
    var onTfliteInference: ((label: String, score: Float, timestampEstimatedMs: Long, values: FloatArray) -> Unit)?

    fun connectDevice(device: BluetoothDevice)
    fun disconnect()
    fun setSensorsConfig(
        rateMs: Int,
        includeMagnetometer: Boolean = false,
        includePressure: Boolean = false,
        includeLinearAcceleration: Boolean = false,
    )
    fun sendHapticCommand(effectIndex: Int, locationBitmask: Int)
    fun sendWaveformVibration(amplitude: Float, durationMs: Int, locationBitmask: Int)
    fun sendStopAlertVibration()
    fun sendGoAlertVibration()
    suspend fun uploadTfliteAsset(assetName: String = "trained.tflite"): Boolean
    fun setTfliteSampleRate(sampleRateHz: Int)
    fun setTfliteTask(taskIndex: Int)
    fun setTfliteCaptureDelay(captureDelayMs: Int)
    fun setTfliteSensorTypes(sensorTypeEnums: ByteArray)
    fun setTfliteThreshold(threshold: Float)
    fun enableTfliteInferencing()
    fun disableTfliteInferencing()
}
