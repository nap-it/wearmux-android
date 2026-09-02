package com.example.peciwearables.integration.ble.devices.omi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.example.peciwearables.integration.CAMERA_QUALITY_FACTOR_MAX
import com.example.peciwearables.integration.CAMERA_QUALITY_FACTOR_MIN
import com.example.peciwearables.integration.CAMERA_RESOLUTION_MAX
import com.example.peciwearables.integration.CAMERA_RESOLUTION_MIN
import com.example.peciwearables.integration.GlassesSettings
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.ble.GlassesMicrophoneProfile
import com.example.peciwearables.integration.ble.OmiRxHandler
import com.example.peciwearables.integration.ble.SdkSensorType
import com.example.peciwearables.integration.protocol.tlv.TlvWriter
import com.example.peciwearables.integration.protocol.tlv.TxRxMessageType
import com.example.peciwearables.integration.WearableService
import com.example.peciwearables.integration.normalizeSensorRateMsForSdk
import com.example.peciwearables.integration.normalizeImuSensorRateMs
import com.example.peciwearables.integration.wearable.GattProfileObserver
import com.example.peciwearables.integration.wearable.GattProfileSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.collections.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BLE client for Omi Glasses.
 *
 * Suporta dois protocolos:
 * - firmware Omi legado (UUID 19B1...)
 * - firmware Omi SDK (UUID ea6d... com TLV TX/RX + Wi‑Fi/UDP). NOTA: este UUID
 *   é partilhado com a Brilliant Sole; a discriminação entre ambos faz-se pelo
 *   sdkDeviceType anunciado e/ou por probes pós-handshake.
 */
@SuppressLint("MissingPermission")
class OmiGlassesBleClient(private val context: Context) : OmiGlassesBleClientApi {

    private enum class OmiProtocolFlavor {
        UNKNOWN,
        LEGACY,
        SDK_FIRMWARE,
    }

    companion object {
        private const val TAG = "OmiGlassesBleClient"

        // ── Omi legado ──
        val LEGACY_SERVICE_UUID: UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
        val AUDIO_DATA_UUID: UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
        val AUDIO_CODEC_UUID: UUID = UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214")
        val IMU_DATA_UUID: UUID = UUID.fromString("19B10003-E8F2-537E-4F6C-D104768A1214")
        val PHOTO_DATA_UUID: UUID = UUID.fromString("19B10005-E8F2-537E-4F6C-D104768A1214")
        val PHOTO_CONTROL_UUID: UUID = UUID.fromString("19B10006-E8F2-537E-4F6C-D104768A1214")

        // ── Omi SDK firmware (UUID partilhado com Brilliant Sole; discriminação por sdkDeviceType/probes) ──
        val SDK_SERVICE_UUID: UUID = UUID.fromString("ea6d0000-a725-4f9b-893d-c3913e33b39f")
        val SDK_RX_UUID: UUID = UUID.fromString("ea6d1000-a725-4f9b-893d-c3913e33b39f")
        val SDK_TX_UUID: UUID = UUID.fromString("ea6d1001-a725-4f9b-893d-c3913e33b39f")
        val SERVICE_DATA_UUID: UUID = UUID.fromString("00000000-0000-1000-8000-00805f9b34fb")

        // Standard BLE Services
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DEVICE_INFO_SERVICE_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val FIRMWARE_REV_UUID: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Protocolo, comandos e timings → OmiGlassesBleProtocol.kt (mesmo package)

        // Photo control command values (match firmware config.h)
        const val PHOTO_CMD_SINGLE: Byte = (-1).toByte()   // Single photo
        const val PHOTO_CMD_STOP: Byte = 0                  // Stop capture / streaming
        const val PHOTO_CMD_STREAM: Byte = 2                // Start video stream
    }

    // ── State ──

    private val _state = MutableStateFlow(BleDeviceState.DISCONNECTED)
    override val state: StateFlow<BleDeviceState> = _state

    private val _batteryLevel = MutableStateFlow(-1)
    override val batteryLevel: StateFlow<Int> = _batteryLevel

    private val _firmwareVersion = MutableStateFlow("")
    override val firmwareVersion: StateFlow<String> = _firmwareVersion

    private val _gattProfile = MutableStateFlow<GattProfileSnapshot?>(null)
    override val gattProfile: StateFlow<GattProfileSnapshot?> = _gattProfile

    private val _glassesSettings = MutableStateFlow(GlassesSettings())
    val glassesSettings: StateFlow<GlassesSettings> = _glassesSettings

    var currentMtu: Int = 23
        private set

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val scanSeenAddresses = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastConnectDevice: BluetoothDevice? = null
    private var connectRetryCount = 0
    private var manualDisconnectRequested = false
    private var preserveRetryCountOnNextScanConnect = false
    private var connectWatchdogRunnable: Runnable? = null
    private var connectWatchdogStartedElapsedMs = 0L
    private var protocolFlavor = OmiProtocolFlavor.UNKNOWN

    private val writeQueue = ConcurrentLinkedQueue<() -> Unit>()
    private var isWritePending = false
    private var activeGattOpName: String? = null
    private var activeGattOpStartedElapsedMs = 0L
    private var gattOpWatchdogRunnable: Runnable? = null
    private var gattOpTimeoutTotal = 0
    private var skipNextDisconnectAutoRetry = false

    // ── Photo chunk reassembly ──
    private var photoBuffer = ByteArrayOutputStream()
    private var photoOrientation: Int = 0
    private var photoChunkCount: Int = 0

    // ── Audio state ──
    private var audioNotificationsEnabled = false
    private var sdkMicrophoneConfigured = false
    private var sdkWifiEnabled: Boolean? = null
    private var sdkWifiConnected: Boolean? = null
    private var sdkWifiSecure: Boolean? = null
    private var sdkWifiIp: String? = null
    private var sdkStreamEnabled = false
    private var sdkStreamDesired = false
    private var sdkStreamTargetFps = 10
    private var sdkStreamCaptureInFlight = false
    private var sdkStreamLastCaptureMs = 0L
    private var sdkStreamLastTimeoutLogMs = 0L
    private var sdkStreamNextCaptureRunnable: Runnable? = null
    private var sdkStreamCaptureTimeoutRunnable: Runnable? = null
    private var sdkUdpTxWindowStartMs = 0L
    private var sdkUdpTxCountInWindow = 0
    private var sdkStreamLastFrameCompletedElapsedMs = 0L
    private var sdkStreamConsecutiveTimeouts = 0
    private var sdkStreamTotalTimeouts = 0
    private var sdkWriteRejectedTotal = 0
    private var sdkStreamSkippedByPendingWrite = 0
    private var sdkStreamLastPendingWriteLogMs = 0L
    private var sdkStreamLastStuckRecoveryMs = 0L
    private var sdkHandshakeTimeoutRunnable: Runnable? = null
    private var sdkLastAccel: Triple<Short, Short, Short>? = null
    private var sdkLastGyro: Triple<Short, Short, Short>? = null
    private var sdkLastMag: Triple<Short, Short, Short>? = null
    private var sdkLastImuForwardAtMs = 0L
    private var imuStreamingDesired = false
    private var imuRateMs = DEFAULT_IMU_RATE_MS

    private val sdkRxHandler = OmiRxHandler(
        onConnected = {
            sdkHandshakeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            sdkHandshakeTimeoutRunnable = null
            _state.value = BleDeviceState.CONNECTED
            WearableService.appendLog("✅ Omi conectado via BLE (Brilliant Sole)")
            requestSdkRuntimeInformation()
            // IMU sensors stay OFF by default. Enabling 4 sensors at 40 ms sends
            // ~100 notifications/sec over the BLE RX pipe, starving camera chunks
            // and writes → stream collapses to ~1.5 fps. Caller must opt-in via
            // setImuStreamingEnabled() when the head-tracking UI is visible.
            if (imuStreamingDesired) {
                applyImuSensorConfiguration(imuRateMs)
            }
            resumeSdkStreamAfterReconnectIfNeeded()
        },
        onTxNeeded = { bytes -> sendSdkPayload(bytes) },
        onGlassesIpReceived = { ip ->
            sdkWifiIp = ip
            onGlassesIpReceived?.invoke(ip)
            logSdkWifiSnapshot(ip)
        }
    ).apply {
        onCameraData = { subType, data ->
            this@OmiGlassesBleClient.onCameraData?.invoke(subType, data)
        }
        onCameraStatus = { status ->
            handleSdkStreamCameraStatus(status)
            this@OmiGlassesBleClient.onCameraStatus?.invoke(status)
        }
        onMicrophoneData = { data ->
            val cb = this@OmiGlassesBleClient.onAudioPacket
            if (cb == null) {
                WearableService.appendLog("⚠ MICRO DBG: onAudioPacket=null, ${data.size}B descartado (udp=${onUdpTxNeeded != null})")
            } else {
                cb.invoke(data)
            }
        }
        onMicrophoneStatus = { status -> this@OmiGlassesBleClient.onMicrophoneStatus?.invoke(status) }
        onMicrophoneConfiguration = { sampleRate, bitDepth ->
            sdkMicrophoneConfigured = true
            val sdkCodecId = if (bitDepth == 16) 3 else 2 // 3=PCM16, 2=MULAW; mirrors SDK JS logic
            onAudioCodecId?.invoke(sdkCodecId)
            onMicrophoneConfig?.invoke(sampleRate, bitDepth, sdkCodecId)
        }
        onMtu = { mtu ->
            updateGlassesSettings { it.copy(sdkMtu = mtu) }
        }
        onCameraConfiguration = { config ->
            updateGlassesSettings {
                it.copy(
                    resolution = config.resolution ?: it.resolution,
                    qualityFactor = config.qualityFactor ?: it.qualityFactor,
                )
            }
        }
        onSensorConfiguration = { config ->
            updateGlassesSettings {
                it.copy(
                    cameraRateMs = config.cameraRateMs ?: it.cameraRateMs,
                    microphoneRateMs = config.microphoneRateMs ?: it.microphoneRateMs,
                )
            }
        }
        onSdkSensorSample = { sample ->
            when (sample.sensorType) {
                SdkSensorType.ACCELEROMETER -> sdkLastAccel = Triple(sample.x, sample.y, sample.z)
                SdkSensorType.GYROSCOPE -> sdkLastGyro = Triple(sample.x, sample.y, sample.z)
                SdkSensorType.MAGNETOMETER -> sdkLastMag = Triple(sample.x, sample.y, sample.z)
                SdkSensorType.GAME_ROTATION -> {
                    val scale = 1f / 16384f
                    this@OmiGlassesBleClient.onGlassesQuaternion?.invoke(
                        floatArrayOf(
                            sample.x * scale,
                            sample.y * scale,
                            sample.z * scale,
                            sample.w * scale,
                        )
                    )
                }
            }

            val accel = sdkLastAccel
            val gyro = sdkLastGyro
            val mag = sdkLastMag
            if (accel != null && gyro != null && mag != null) {
                val now = SystemClock.elapsedRealtime()
                if (now - sdkLastImuForwardAtMs >= IMU_FORWARD_MIN_INTERVAL_MS) {
                    sdkLastImuForwardAtMs = now
                    val packet = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).apply {
                        putShort(accel.first)
                        putShort(accel.second)
                        putShort(accel.third)
                        putShort(gyro.first)
                        putShort(gyro.second)
                        putShort(gyro.third)
                        putShort(mag.first)
                        putShort(mag.second)
                        putShort(mag.third)
                    }.array()
                    onImuData?.invoke(packet)
                }
            }
        }
        onFileReceived = { fileType, data ->
            if (fileType == FILE_TYPE_CAMERA_IMAGE) {
                onCameraImageFileReceived?.invoke(data)
            } else {
                WearableService.appendLog("📦 Omi FT: ficheiro recebido type=$fileType size=${data.size}B (ignorado para camera)")
            }
        }
        onWifiConnectionEnabledChanged = { enabled ->
            sdkWifiEnabled = enabled
            logSdkWifiSnapshot()
        }
        onWifiConnectedChanged = { connected ->
            sdkWifiConnected = connected
            logSdkWifiSnapshot()
        }
        onWifiSecureChanged = { secure ->
            sdkWifiSecure = secure
            logSdkWifiSnapshot()
        }
    }

    init {
        sdkRxHandler.onDebug = { message ->
            val important =
                message.contains("error", ignoreCase = true) ||
                    message.contains("malformed", ignoreCase = true) ||
                    message.contains("timeout", ignoreCase = true) ||
                    message.contains("reset", ignoreCase = true)
            if (important) {
                WearableService.appendLog("🔎 Omi RX: $message")
            }
        }
        sdkRxHandler.fileTransferHandler.onTrace = { level, message ->
            val shouldLog =
                level != "D" ||
                    message.startsWith("FILE_TYPE") ||
                    message.startsWith("FILE_LENGTH") ||
                    message.startsWith("FILE_CHECKSUM") ||
                    message.startsWith("Transferência completa") ||
                    (message.startsWith("FILE_BLOCK") && message.contains("(100%)")) ||
                    message.startsWith("CRC32 OK")
            if (shouldLog) {
                val prefix = when (level) {
                    "E", "W" -> "⚠"
                    else -> "📦"
                }
                WearableService.appendLog("$prefix Omi FT: $message")
            }
        }
    }

    // ── Callbacks (wired by WearableService) ──

    /** Called when a complete JPEG photo has been received and reassembled. */
    override var onPhotoReceived: ((jpegBytes: ByteArray, orientation: Int) -> Unit)? = null

    /** Called for each audio packet (3-byte BLE header stripped). */
    override var onAudioPacket: ((audioData: ByteArray) -> Unit)? = null

    /** Called when the audio codec ID is read from the device. */
    override var onAudioCodecId: ((Int) -> Unit)? = null

    override var onCameraData: ((Int, ByteArray) -> Unit)? = null
    override var onCameraStatus: ((Int) -> Unit)? = null
    override var onCameraImageFileReceived: ((ByteArray) -> Unit)? = null
    var onSdkStreamCaptureDispatched: ((elapsedRealtimeMs: Long) -> Unit)? = null
    override var onMicrophoneStatus: ((Int) -> Unit)? = null
    override var onMicrophoneConfig: ((Int, Int, Int) -> Unit)? = null

    // IMU callback - receives 18-byte packets (9 x int16: ax,ay,az,gx,gy,gz,mx,my,mz)
    override var onImuData: ((ByteArray) -> Unit)? = null
    // Quaternion callback - FloatArray[4] = {x, y, z, w} from BNO085 game rotation (scaled 1/16384)
    override var onGlassesQuaternion: ((FloatArray) -> Unit)? = null
    override var onGlassesIpReceived: ((String) -> Unit)? = null
    override var onUdpTxNeeded: ((ByteArray) -> Unit)? = null

    override val supportsWifiControl: Boolean
        get() = protocolFlavor == OmiProtocolFlavor.SDK_FIRMWARE

    override val wifiConnectionEnabled: Boolean?
        get() = sdkWifiEnabled

    override val isWifiConnected: Boolean?
        get() = sdkWifiConnected

    override val isWifiSecure: Boolean?
        get() = sdkWifiSecure

    val ipAddress: String?
        get() = sdkWifiIp

    override fun setImuStreamingEnabled(enabled: Boolean, rateMs: Int) { // default in interface
        imuStreamingDesired = enabled
        imuRateMs = rateMs
        if (_state.value == BleDeviceState.CONNECTED) {
            if (enabled) {
                applyImuSensorConfiguration(activeImuRateMs())
            } else {
                disableImuSensors()
            }
        }
    }

    private fun disableImuSensors() {
        sendSdkMessages(
            buildSetSensorRateMessage(SENSOR_TYPE_ACCELERATION, 0),
            buildSetSensorRateMessage(SENSOR_TYPE_GYROSCOPE, 0),
            buildSetSensorRateMessage(SENSOR_TYPE_MAGNETOMETER, 0),
            buildSetSensorRateMessage(SENSOR_TYPE_GAME_ROTATION, 0),
        )
        WearableService.appendLog("🧭 Omi IMU desativado")
    }

    private fun updateGlassesSettings(
        transform: (GlassesSettings) -> GlassesSettings
    ) {
        _glassesSettings.value = transform(_glassesSettings.value)
    }

    // ── Scan ──

    fun startScan() {
        if (isScanning) return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: run {
            Log.e(TAG, "BluetoothAdapter not available")
            WearableService.appendLog("❌ Bluetooth não disponível")
            _state.value = BleDeviceState.ERROR
            return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled")
            WearableService.appendLog("❌ Bluetooth desligado — liga nas definições")
            _state.value = BleDeviceState.ERROR
            return
        }
        scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "BLE scanner not available")
            WearableService.appendLog("❌ Scanner BLE não disponível")
            _state.value = BleDeviceState.ERROR
            return
        }
        val legacyFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(LEGACY_SERVICE_UUID))
            .build()
        val sdkFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SDK_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanSeenAddresses.clear()
        scanner?.startScan(listOf(legacyFilter, sdkFilter), settings, scanCallback)
        isScanning = true
        Log.d(TAG, "BLE scan started for Omi Glasses (legacy + Brilliant Sole)")
        WearableService.appendLog(
            "⏳ A procurar Omi Glasses (BLE) com filtros UUID legacy/sdk. " +
                "Dispositivos sem estes UUIDs podem nao aparecer neste modo."
        )
    }

    fun stopScan() {
        if (!isScanning) return
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: "(sem nome)"
            val address = result.device.address ?: "(sem MAC)"
            val firstObservation = scanSeenAddresses.add(address)

            val match = evaluateGlassesMatch(result)
            val diag = buildScanDiagnostics(result, match)
            if (!match.isMatch) {
                if (firstObservation) {
                    Log.d(TAG, "Ignoring non-glasses candidate during direct Omi scan: $name [$address] $diag")
                    WearableService.appendLog("Omi scan IGNORE: $name [$address] $diag")
                }
                return
            }
            Log.d(TAG, "Omi found: $name [$address] $diag")
            WearableService.appendLog("✅ Omi encontrado: $name [$address] $diag")
            stopScan()
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            WearableService.appendLog("❌ Scan BLE falhou (erro: $errorCode)")
            _state.value = BleDeviceState.ERROR
        }
    }

    // ── Connect ──

    override fun connectDevice(device: BluetoothDevice) {
        stopScan()
        Log.d(TAG, "Connecting to device: ${device.name} [${device.address}]")
        connectInternal(device, resetRetryCount = true, source = "manual")
    }

    private fun connect(device: BluetoothDevice) {
        val preserveRetryCount = preserveRetryCountOnNextScanConnect
        preserveRetryCountOnNextScanConnect = false
        connectInternal(
            device,
            resetRetryCount = !preserveRetryCount,
            source = if (preserveRetryCount) "scan-retry" else "scan"
        )
    }

    private fun connectInternal(device: BluetoothDevice, resetRetryCount: Boolean, source: String) {
        if (resetRetryCount) connectRetryCount = 0
        manualDisconnectRequested = false
        prepareFreshConnectionState()
        _state.value = BleDeviceState.CONNECTING
        lastConnectDevice = device
        WearableService.appendLog("🔗 Omi BLE: a ligar ($source)...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        scheduleConnectAttemptWatchdog(source)
    }

    override fun disconnect(keepUdpRouting: Boolean) {
        manualDisconnectRequested = true
        stopSdkCameraStream(sendStopCommand = false)
        mainHandler.removeCallbacksAndMessages(null)
        cancelConnectAttemptWatchdog()
        cancelGattOpWatchdog()
        connectRetryCount = 0
        lastConnectDevice = null
        skipNextDisconnectAutoRetry = false
        preserveRetryCountOnNextScanConnect = false
        clearActiveGattOperationState(clearQueue = true)
        audioNotificationsEnabled = false
        if (!keepUdpRouting) {
            onUdpTxNeeded = null
        }
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        if (!keepUdpRouting) {
            _glassesSettings.value = GlassesSettings()
            _gattProfile.value = null
            _state.value = BleDeviceState.DISCONNECTED
        }
    }

    private fun prepareFreshConnectionState() {
        mainHandler.removeCallbacksAndMessages(null)
        cancelConnectAttemptWatchdog()
        cancelGattOpWatchdog()
        clearActiveGattOperationState(clearQueue = true)
        currentMtu = 23
        _glassesSettings.value = GlassesSettings()
        audioNotificationsEnabled = false
        sdkMicrophoneConfigured = false
        stopSdkCameraStream(
            sendStopCommand = false,
            keepDesiredState = !manualDisconnectRequested && sdkStreamDesired
        )
        protocolFlavor = OmiProtocolFlavor.UNKNOWN
        _gattProfile.value = null
        photoBuffer.reset()
        photoChunkCount = 0
        sdkRxHandler.reset()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun decodeGattStatus(status: Int): String {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
            8 -> "GATT_CONN_TIMEOUT"
            19 -> "GATT_CONN_TERMINATE_PEER_USER"
            22 -> "GATT_CONN_TERMINATE_LOCAL_HOST"
            62 -> "GATT_CONN_FAIL_ESTABLISH"
            133 -> "GATT_ERROR(133)"
            else -> "status_$status"
        }
    }

    private fun streamDiagSnapshot(): String {
        val now = SystemClock.elapsedRealtime()
        val lastFrameAgeMs = if (sdkStreamLastFrameCompletedElapsedMs > 0L) {
            (now - sdkStreamLastFrameCompletedElapsedMs).coerceAtLeast(0L)
        } else {
            -1L
        }
        val activeOp = activeGattOpName ?: "-"
        val activeOpAgeMs = if (activeGattOpStartedElapsedMs > 0L) {
            (now - activeGattOpStartedElapsedMs).coerceAtLeast(0L)
        } else {
            -1L
        }
        return "streamEnabled=$sdkStreamEnabled inFlight=$sdkStreamCaptureInFlight " +
            "queue=${writeQueue.size} pending=$isWritePending activeOp=$activeOp activeOpAge=${activeOpAgeMs}ms " +
            "lastFrameAge=${lastFrameAgeMs}ms timeoutSeq=$sdkStreamConsecutiveTimeouts timeoutTotal=$sdkStreamTotalTimeouts " +
            "writeRejects=$sdkWriteRejectedTotal mtu=$currentMtu"
    }

    private fun isInConnectFlow(): Boolean {
        return _state.value == BleDeviceState.CONNECTING ||
            _state.value == BleDeviceState.DISCOVERING ||
            _state.value == BleDeviceState.CONFIGURING
    }

    private fun shouldAutoRetry(status: Int): Boolean {
        val transientDisconnectDuringConnect =
            status == BluetoothGatt.GATT_SUCCESS && isInConnectFlow()
        return !manualDisconnectRequested &&
            (status != BluetoothGatt.GATT_SUCCESS || transientDisconnectDuringConnect) &&
            connectRetryCount < MAX_AUTO_CONNECT_RETRIES &&
            lastConnectDevice != null
    }

    private fun autoRetryDelayMs(attempt: Int): Long {
        val exp = (attempt - 1).coerceIn(0, 2)
        return AUTO_RETRY_BASE_DELAY_MS * (1L shl exp)
    }

    private fun cancelConnectAttemptWatchdog() {
        connectWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        connectWatchdogRunnable = null
        connectWatchdogStartedElapsedMs = 0L
    }

    private fun scheduleConnectAttemptWatchdog(source: String) {
        cancelConnectAttemptWatchdog()
        connectWatchdogStartedElapsedMs = SystemClock.elapsedRealtime()
        val watchdog = Runnable {
            connectWatchdogRunnable = null
            if (manualDisconnectRequested || !isInConnectFlow()) {
                return@Runnable
            }
            val ageMs = (SystemClock.elapsedRealtime() - connectWatchdogStartedElapsedMs).coerceAtLeast(0L)
            if (ageMs < CONNECT_ATTEMPT_TIMEOUT_MS) {
                scheduleConnectAttemptWatchdog(source)
                return@Runnable
            }
            WearableService.appendLog(
                "⚠ Omi BLE: tentativa de ligação sem progresso (${ageMs}ms, source=$source). " +
                    "A forçar retry por rescan..."
            )
            try {
                bluetoothGatt?.disconnect()
            } catch (_: Exception) {
            }
            try {
                bluetoothGatt?.close()
            } catch (_: Exception) {
            }
            bluetoothGatt = null
            if (shouldAutoRetry(8)) {
                scheduleAutoRetry(8, reason = "connect-timeout", preferRescan = true)
            } else {
                _state.value = BleDeviceState.DISCONNECTED
            }
        }
        connectWatchdogRunnable = watchdog
        mainHandler.postDelayed(watchdog, CONNECT_ATTEMPT_TIMEOUT_MS)
    }

    private fun scheduleAutoRetry(
        failedStatus: Int,
        reason: String = "disconnect",
        preferRescan: Boolean = false,
    ) {
        val device = lastConnectDevice ?: return
        connectRetryCount++
        val delayMs = autoRetryDelayMs(connectRetryCount)
        _state.value = BleDeviceState.CONNECTING
        WearableService.appendLog(
            "Omi BLE falhou (status=$failedStatus ${decodeGattStatus(failedStatus)}). " +
                "Retry $connectRetryCount/$MAX_AUTO_CONNECT_RETRIES em ${delayMs}ms " +
                "($reason, mode=${if (preferRescan) "rescan" else "direct"}). ${streamDiagSnapshot()}"
        )
        mainHandler.postDelayed({
            if (preferRescan) {
                preserveRetryCountOnNextScanConnect = true
                stopScan()
                startScan()
            } else {
                connectInternal(device, resetRetryCount = false, source = "auto-retry:$reason")
            }
        }, delayMs)
    }

    // ── GATT Callback ──

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val stateLabel = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                else -> "state=$newState"
            }
            Log.i(TAG, "[BLE][Omi] state: ${_state.value} → $stateLabel (gatt status=$status)")
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Omi GATT connected")
                    cancelConnectAttemptWatchdog()
                    connectRetryCount = 0
                    skipNextDisconnectAutoRetry = false
                    // Request fastest connection interval (7.5ms) for low-latency photo transfer
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    WearableService.appendLog("🔗 Ligado via BLE GATT — a descobrir serviços...")
                    _state.value = BleDeviceState.DISCOVERING
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Omi GATT disconnected (status=$status)")
                    cancelConnectAttemptWatchdog()
                    WearableService.appendLog("BLE desconectado (status=$status ${decodeGattStatus(status)}). ${streamDiagSnapshot()}")
                    cancelGattOpWatchdog()
                    clearActiveGattOperationState(clearQueue = true)
                    if (gatt == bluetoothGatt) bluetoothGatt = null
                    gatt.close()
                    if (skipNextDisconnectAutoRetry) {
                        skipNextDisconnectAutoRetry = false
                        return
                    }
                    if (shouldAutoRetry(status)) {
                        val preferRescan = status == BluetoothGatt.GATT_SUCCESS || isInConnectFlow()
                        scheduleAutoRetry(
                            failedStatus = status,
                            reason = if (preferRescan) "connect-drop" else "disconnect",
                            preferRescan = preferRescan
                        )
                    } else if (onUdpTxNeeded != null) {
                        // BLE GATT closed as part of BLE→UDP switch (keepUdpRouting=true).
                        // UDP transport keeps the logical session alive — do NOT move to
                        // DISCONNECTED yet. clearGlassesUdpRouting() will call disconnect(false)
                        // to properly close when the UDP session ends.
                        Log.i(TAG, "[BLE][Omi] GATT closed with UDP routing active — session kept alive via UDP")
                    } else {
                        _state.value = BleDeviceState.DISCONNECTED
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                protocolFlavor = when {
                    gatt.getService(SDK_SERVICE_UUID) != null -> OmiProtocolFlavor.SDK_FIRMWARE
                    gatt.getService(LEGACY_SERVICE_UUID) != null -> OmiProtocolFlavor.LEGACY
                    else -> OmiProtocolFlavor.UNKNOWN
                }
                if (protocolFlavor == OmiProtocolFlavor.UNKNOWN) {
                    WearableService.appendLog("❌ Omi: serviço BLE desconhecido")
                    _state.value = BleDeviceState.ERROR
                    return
                }
                _state.value = BleDeviceState.CONFIGURING
                _gattProfile.value = GattProfileObserver.snapshot(
                    gatt = gatt,
                    firmwareVersion = _firmwareVersion.value,
                    handshakeProbes = buildMap {
                        if (protocolFlavor == OmiProtocolFlavor.SDK_FIRMWARE) {
                            put("sdkDeviceType", SDK_DEVICE_TYPE_GLASSES)
                            put("wifiSupported", true)
                        }
                    },
                )
                gatt.requestMtu(TARGET_MTU)
            } else {
                Log.e(TAG, "Service discovery failed: $status")
                WearableService.appendLog("❌ Omi: falha a descobrir serviços (status=$status)")
                _state.value = BleDeviceState.ERROR
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                WearableService.appendLog("⚠ Omi: MTU request falhou (status=$status), a continuar")
            }
            currentMtu = mtu
            updateGlassesSettings { it.copy(bleNegotiatedMtu = mtu) }
            Log.d(TAG, "MTU = $mtu")
            setupNotificationsAndRead(gatt)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write FAILED: ${characteristic.uuid} status=$status")
                val opName = activeGattOpName ?: "unknown"
                WearableService.appendLog(
                    "Omi BLE write falhou (status=$status ${decodeGattStatus(status)}) op=$opName char=${characteristic.uuid}. ${streamDiagSnapshot()}"
                )
            }
            clearActiveGattOperationState(clearQueue = false)
            processNextWrite()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            when (characteristic.uuid) {
                PHOTO_DATA_UUID -> onPhotoChunk(data)
                AUDIO_DATA_UUID -> onAudioChunk(data)
                SDK_RX_UUID -> onTxRxBytesReceived(data)
                BATTERY_LEVEL_UUID -> {
                    _batteryLevel.value = data[0].toInt() and 0xFF
                    Log.d(TAG, "Battery: ${_batteryLevel.value}%")
                }
                IMU_DATA_UUID -> {
                    if (data.size >= 18) {
                        onImuData?.invoke(data)
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value
                if (data != null) {
                    when (characteristic.uuid) {
                        BATTERY_LEVEL_UUID -> {
                            _batteryLevel.value = data[0].toInt() and 0xFF
                            Log.d(TAG, "Battery read: ${_batteryLevel.value}%")
                        }
                        FIRMWARE_REV_UUID -> {
                            _firmwareVersion.value = String(data, Charsets.UTF_8)
                            Log.d(TAG, "Firmware: ${_firmwareVersion.value}")
                        }
                        AUDIO_CODEC_UUID -> {
                            val codecId = data[0].toInt() and 0xFF
                            Log.d(TAG, "Audio codec ID: $codecId")
                            onAudioCodecId?.invoke(codecId)
                            onMicrophoneConfig?.invoke(DEFAULT_MICROPHONE_SAMPLE_RATE, DEFAULT_MICROPHONE_BIT_DEPTH, codecId)
                        }
                    }
                }
            }
            clearActiveGattOperationState(clearQueue = false)
            processNextWrite()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val opName = activeGattOpName ?: "unknown"
                WearableService.appendLog(
                    "Omi BLE descriptor write falhou (status=$status ${decodeGattStatus(status)}) op=$opName desc=${descriptor.uuid}. ${streamDiagSnapshot()}"
                )
            }
            clearActiveGattOperationState(clearQueue = false)
            processNextWrite()
        }
    }

    // ── Setup notifications after MTU negotiation ──

    private fun setupNotificationsAndRead(gatt: BluetoothGatt) {
        when (protocolFlavor) {
            OmiProtocolFlavor.LEGACY -> setupLegacyNotificationsAndRead(gatt)
            OmiProtocolFlavor.SDK_FIRMWARE -> setupSdkNotificationsAndRead(gatt)
            OmiProtocolFlavor.UNKNOWN -> {
                WearableService.appendLog("❌ Omi: protocolo BLE desconhecido")
                _state.value = BleDeviceState.ERROR
            }
        }
    }

    private fun setupLegacyNotificationsAndRead(gatt: BluetoothGatt) {
        val mainService = gatt.getService(LEGACY_SERVICE_UUID)
        if (mainService == null) {
            Log.e(TAG, "Main service 19B10000 not found!")
            WearableService.appendLog("❌ Omi: serviço principal não encontrado")
            _state.value = BleDeviceState.ERROR
            return
        }

        mainService.getCharacteristic(PHOTO_DATA_UUID)?.let { photoChar ->
            gatt.setCharacteristicNotification(photoChar, true)
            photoChar.getDescriptor(CCCD_UUID)?.let { cccd ->
                enqueueGattOperation("enable photo notify") {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
        }

        mainService.getCharacteristic(AUDIO_DATA_UUID)?.let { audioChar ->
            gatt.setCharacteristicNotification(audioChar, true)
            audioChar.getDescriptor(CCCD_UUID)?.let { cccd ->
                enqueueGattOperation("enable audio notify") {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
            audioNotificationsEnabled = true
        }

        mainService.getCharacteristic(AUDIO_CODEC_UUID)?.let { codecChar ->
            enqueueGattOperation("read audio codec") { gatt.readCharacteristic(codecChar) }
        }

        // Enable IMU notifications
        mainService.getCharacteristic(IMU_DATA_UUID)?.let { imuChar ->
            gatt.setCharacteristicNotification(imuChar, true)
            imuChar.getDescriptor(CCCD_UUID)?.let { cccd ->
                enqueueGattOperation("enable IMU notify") {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
            Log.d(TAG, "IMU notifications enabled")
        }

        setupStandardInfoReads(gatt)

        enqueueWrite {
            _state.value = BleDeviceState.CONNECTED
            Log.d(TAG, "Omi BLE connected — notifications enabled")
            WearableService.appendLog("✅ Omi conectado via BLE! Fotos e áudio prontos.")
            isWritePending = false
            processNextWrite()
        }
    }

    private fun setupSdkNotificationsAndRead(gatt: BluetoothGatt) {
        val mainService = gatt.getService(SDK_SERVICE_UUID)
        if (mainService == null) {
            WearableService.appendLog("❌ Omi SDK: serviço principal não encontrado")
            _state.value = BleDeviceState.ERROR
            return
        }

        mainService.getCharacteristic(SDK_RX_UUID)?.let { rxChar ->
            gatt.setCharacteristicNotification(rxChar, true)
            rxChar.getDescriptor(CCCD_UUID)?.let { cccd ->
                enqueueGattOperation("enable sdk rx notify") {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
        } ?: run {
            WearableService.appendLog("❌ Omi SDK: característica RX não encontrada")
            _state.value = BleDeviceState.ERROR
            return
        }

        setupStandardInfoReads(gatt)

        enqueueWrite {
            _state.value = BleDeviceState.READY
            WearableService.appendLog("🟡 Omi BLE pronto (Brilliant Sole)")
            sendSdkHandshake()
            isWritePending = false
            processNextWrite()
        }
    }

    private fun setupStandardInfoReads(gatt: BluetoothGatt) {
        gatt.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID)?.let { batChar ->
            gatt.setCharacteristicNotification(batChar, true)
            batChar.getDescriptor(CCCD_UUID)?.let { cccd ->
                enqueueGattOperation("enable battery notify") {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
            enqueueGattOperation("read battery level") { gatt.readCharacteristic(batChar) }
        }

        gatt.getService(DEVICE_INFO_SERVICE_UUID)?.getCharacteristic(FIRMWARE_REV_UUID)?.let { fwChar ->
            enqueueGattOperation("read firmware version") { gatt.readCharacteristic(fwChar) }
        }
    }

    // ── Photo chunk reassembly ──

    private fun onPhotoChunk(data: ByteArray) {
        // End marker: [0xFF, 0xFF]
        if (data.size == 2 && data[0] == 0xFF.toByte() && data[1] == 0xFF.toByte()) {
            val jpeg = photoBuffer.toByteArray()
            if (jpeg.isNotEmpty()) {
                Log.d(TAG, "Photo complete: ${jpeg.size} bytes, orientation=$photoOrientation")
                onPhotoReceived?.invoke(jpeg, photoOrientation)
            }
            photoBuffer.reset()
            photoChunkCount = 0
            return
        }

        if (data.size < 3) return

        val frameIdx = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)

        if (frameIdx == 0) {
            // First chunk of new photo: [idx_lo, idx_hi, orientation, jpeg...]
            photoBuffer.reset()
            photoOrientation = data[2].toInt() and 0xFF
            if (data.size > 3) {
                photoBuffer.write(data, 3, data.size - 3)
            }
            photoChunkCount = 1
        } else {
            // Subsequent chunk: [idx_lo, idx_hi, jpeg...]
            if (data.size > 2) {
                photoBuffer.write(data, 2, data.size - 2)
            }
            photoChunkCount++
        }
    }

    // ── Audio packet handling ──

    private fun onAudioChunk(data: ByteArray) {
        // Format: [packetIdx_lo, packetIdx_hi, subIdx, audio_data...]
        if (data.size <= 3) return
        val audioData = ByteArray(data.size - 3)
        System.arraycopy(data, 3, audioData, 0, audioData.size)
        onAudioPacket?.invoke(audioData)
    }

    // ── Photo control commands ──

    override fun takePicture() {
        when (protocolFlavor) {
            OmiProtocolFlavor.LEGACY -> {
                writePhotoControl(PHOTO_CMD_SINGLE)
                WearableService.appendLog("📷 Comando: tirar foto")
            }
            OmiProtocolFlavor.SDK_FIRMWARE -> {
                ensureCameraSensorRateForCapture()
                sendSdkMessages(TlvWriter.Message(TxRxMessageType.CAMERA_COMMAND, byteArrayOf(CAMERA_COMMAND_TAKE_PICTURE.toByte())))
                WearableService.appendLog("📷 Comando câmera (SDK): tirar foto")
            }
            OmiProtocolFlavor.UNKNOWN -> Unit
        }
    }

    override fun stopCamera() {
        when (protocolFlavor) {
            OmiProtocolFlavor.LEGACY -> writePhotoControl(PHOTO_CMD_STOP)
            OmiProtocolFlavor.SDK_FIRMWARE -> sendSdkMessages(
                TlvWriter.Message(TxRxMessageType.CAMERA_COMMAND, byteArrayOf(CAMERA_COMMAND_STOP.toByte()))
            )
            OmiProtocolFlavor.UNKNOWN -> Unit
        }
    }

    override fun setStreamMode(enable: Boolean, targetFps: Int?) {
        when (protocolFlavor) {
            OmiProtocolFlavor.LEGACY -> {
                writePhotoControl(if (enable) PHOTO_CMD_STREAM else PHOTO_CMD_STOP)
                WearableService.appendLog("🎬 Stream ${if (enable) "iniciado" else "parado"}")
            }
            OmiProtocolFlavor.SDK_FIRMWARE -> {
                sdkStreamDesired = enable
                if (enable) {
                    startSdkCameraStream(targetFps ?: sdkStreamTargetFps)
                } else {
                    stopSdkCameraStream(sendStopCommand = true, keepDesiredState = false)
                }
            }
            OmiProtocolFlavor.UNKNOWN -> Unit
        }
    }

    override fun updateStreamTargetFps(targetFps: Int) {
        val clampedFps = targetFps.coerceIn(2, SDK_STREAM_MAX_FPS)
        sdkStreamTargetFps = clampedFps
        if (sdkStreamEnabled) {
            WearableService.appendLog("🎬 Stream SDK: novo alvo ${clampedFps}fps")
        }
    }

    private fun startSdkCameraStream(targetFps: Int) {
        val clampedFps = targetFps.coerceIn(2, SDK_STREAM_MAX_FPS)
        sdkStreamDesired = true
        sdkStreamEnabled = true
        sdkStreamTargetFps = clampedFps
        if (imuStreamingDesired && onUdpTxNeeded != null) {
            applyImuSensorConfiguration(activeImuRateMs())
        }
        sdkStreamCaptureInFlight = false
        sdkStreamLastCaptureMs = 0L
        sdkStreamLastTimeoutLogMs = 0L
        sdkStreamNextCaptureRunnable?.let { mainHandler.removeCallbacks(it) }
        sdkStreamNextCaptureRunnable = null
        sdkStreamCaptureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        sdkStreamCaptureTimeoutRunnable = null
        sdkStreamLastFrameCompletedElapsedMs = 0L
        sdkStreamConsecutiveTimeouts = 0
        sdkStreamTotalTimeouts = 0
        sdkWriteRejectedTotal = 0
        sdkStreamSkippedByPendingWrite = 0
        sdkStreamLastPendingWriteLogMs = 0L
        sdkStreamLastStuckRecoveryMs = 0L

        // Apply a default stream profile only on the very first stream start when
        // the firmware has not yet received any camera configuration. Once the user
        // (or the app) sets resolution/quality/rate, those values stick.
        val current = _glassesSettings.value
        if (current.resolution == null && current.qualityFactor == null && current.cameraRateMs == null) {
            applyCameraSettings(
                resolution = 480,
                qualityFactor = 50,
                cameraRateMs = 20,
            )
            WearableService.appendLog("🎛 Stream default profile: res=480 qf=50 rate=20ms")
        } else if ((current.resolution ?: CAMERA_RESOLUTION_MIN) < CAMERA_RESOLUTION_MIN) {
            applyCameraSettings(
                resolution = CAMERA_RESOLUTION_MIN,
                qualityFactor = null,
                cameraRateMs = null,
            )
            WearableService.appendLog("🎛 Stream profile ajustado: res>=${CAMERA_RESOLUTION_MIN} para YOLO")
        }

        ensureCameraSensorRateForCapture()
        val delayWakeMs = if (onUdpTxNeeded == null && (isWritePending || activeGattOpName != null || writeQueue.isNotEmpty())) 200L else 0L
        mainHandler.postDelayed({
            if (!sdkStreamEnabled) return@postDelayed
            sendSdkMessages(TlvWriter.Message(TxRxMessageType.CAMERA_COMMAND, byteArrayOf(CAMERA_COMMAND_WAKE.toByte())))
            scheduleNextSdkStreamCapture("start", ignoreRatePacing = true)
            WearableService.appendLog("🎬 Stream SDK iniciado (${clampedFps} FPS alvo)")
        }, delayWakeMs)
    }

    private fun stopSdkCameraStream(sendStopCommand: Boolean, keepDesiredState: Boolean = false) {
        val wasEnabled = sdkStreamEnabled
        if (!keepDesiredState) {
            sdkStreamDesired = false
        }
        sdkStreamEnabled = false
        if (imuStreamingDesired && onUdpTxNeeded != null) {
            applyImuSensorConfiguration(activeImuRateMs())
        }
        sdkStreamCaptureInFlight = false
        sdkRxHandler.resetCameraDataReassembly("stream stop")
        sdkStreamCaptureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        sdkStreamCaptureTimeoutRunnable = null
        sdkStreamNextCaptureRunnable?.let { mainHandler.removeCallbacks(it) }
        sdkStreamNextCaptureRunnable = null
        clearActiveGattOperationState(clearQueue = false)
        if (sendStopCommand && protocolFlavor == OmiProtocolFlavor.SDK_FIRMWARE) {
            sendSdkMessages(TlvWriter.Message(TxRxMessageType.CAMERA_COMMAND, byteArrayOf(CAMERA_COMMAND_STOP.toByte())))
        }
        if (wasEnabled) {
            WearableService.appendLog("⏹ Stream SDK parado")
        }
    }

    private fun handleSdkStreamCameraStatus(status: Int) {
        if (!sdkStreamEnabled) return
        if (status == 3) {
            sendSdkMessages(TlvWriter.Message(TxRxMessageType.CAMERA_COMMAND, byteArrayOf(CAMERA_COMMAND_WAKE.toByte())))
        }
    }

    override fun onSdkStreamImageAssembled() {
        onSdkStreamFrameCompleted("jpeg_assembled")
    }

    private fun onSdkStreamFrameCompleted(source: String) {
        if (!sdkStreamEnabled) {
            WearableService.appendLog("⚠ Stream: frame ignorado (disabled, source=$source)")
            return
        }
        sdkStreamLastFrameCompletedElapsedMs = SystemClock.elapsedRealtime()
        sdkStreamConsecutiveTimeouts = 0
        sdkStreamNextCaptureRunnable?.let { mainHandler.removeCallbacks(it) }
        sdkStreamNextCaptureRunnable = null
        if (sdkStreamCaptureInFlight) {
            sdkStreamCaptureInFlight = false
            sdkStreamCaptureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            sdkStreamCaptureTimeoutRunnable = null
        }
        scheduleNextSdkStreamCapture(source)
    }

    private fun scheduleNextSdkStreamCapture(source: String, ignoreRatePacing: Boolean = false) {
        if (!sdkStreamEnabled || protocolFlavor != OmiProtocolFlavor.SDK_FIRMWARE || sdkStreamCaptureInFlight) return
        if (sdkStreamNextCaptureRunnable != null) return

        val intervalMs = (1000L / sdkStreamTargetFps.coerceAtLeast(1)).coerceAtLeast(20L)
        val elapsedSinceLastCaptureMs = (System.currentTimeMillis() - sdkStreamLastCaptureMs).coerceAtLeast(0L)
        val delayMs = if (ignoreRatePacing) 0L else (intervalMs - elapsedSinceLastCaptureMs).coerceAtLeast(0L)

        val runnable = Runnable {
            sdkStreamNextCaptureRunnable = null
            if (!sdkStreamEnabled || protocolFlavor != OmiProtocolFlavor.SDK_FIRMWARE || sdkStreamCaptureInFlight) {
                return@Runnable
            }

            val hasPendingWrite = onUdpTxNeeded == null && (isWritePending || activeGattOpName != null || writeQueue.isNotEmpty())
            if (hasPendingWrite) {
                sdkStreamSkippedByPendingWrite++
                val nowUnixMs = System.currentTimeMillis()
                if (nowUnixMs - sdkStreamLastPendingWriteLogMs >= SDK_STREAM_PENDING_LOG_COOLDOWN_MS) {
                    val nowElapsedMs = SystemClock.elapsedRealtime()
                    val activeAgeMs = if (activeGattOpStartedElapsedMs > 0L) {
                        (nowElapsedMs - activeGattOpStartedElapsedMs).coerceAtLeast(0L)
                    } else {
                        -1L
                    }
                    val stalledLabel = if (activeAgeMs >= SDK_STREAM_STALLED_WRITE_WARN_MS) " (stalled)" else ""
                    WearableService.appendLog(
                        "⚠ Stream: write pendente$stalledLabel, frame ignorado " +
                            "(source=$source skipped=$sdkStreamSkippedByPendingWrite activeOp=${activeGattOpName ?: "-"} " +
                            "activeAge=${activeAgeMs}ms queue=${writeQueue.size})"
                    )
                    sdkStreamLastPendingWriteLogMs = nowUnixMs
                }
                mainHandler.postDelayed(
                    { scheduleNextSdkStreamCapture("pending_write:$source", ignoreRatePacing = true) },
                    SDK_STREAM_PENDING_WRITE_RETRY_MS
                )
                return@Runnable
            }

            sendSdkMessages(
                TlvWriter.Message(
                    TxRxMessageType.CAMERA_COMMAND,
                    byteArrayOf(CAMERA_COMMAND_TAKE_PICTURE.toByte())
                )
            )
            sdkStreamCaptureInFlight = true
            sdkStreamLastCaptureMs = System.currentTimeMillis()
            onSdkStreamCaptureDispatched?.invoke(SystemClock.elapsedRealtime())
            scheduleSdkStreamCaptureTimeout(source)
        }

        sdkStreamNextCaptureRunnable = runnable
        if (delayMs > 0L) {
            mainHandler.postDelayed(runnable, delayMs)
        } else {
            mainHandler.post(runnable)
        }
    }

    /**
     * Quando o stream encadeia muitos timeouts sem receber uma foto inteira
     * (sintoma típico do firmware Omi em WiFi/UDP a anunciar o ficheiro via
     * SET_FILE_TYPE e a não enviar depois os blocos), mandamos um STOP+WAKE
     * para o tirar desse estado. O cooldown evita martelar o firmware com
     * vários recovers seguidos quando o problema é externo (sinal Wi-Fi).
     */
    private fun maybeRecoverStuckStream(nowMs: Long) {
        if (sdkStreamConsecutiveTimeouts < SDK_STREAM_STUCK_TIMEOUTS_THRESHOLD) return
        if (nowMs - sdkStreamLastStuckRecoveryMs < SDK_STREAM_STUCK_COOLDOWN_MS) return
        sdkStreamLastStuckRecoveryMs = nowMs
        sdkStreamConsecutiveTimeouts = 0
        sdkRxHandler.resetCameraDataReassembly("stuck stream recovery")
        WearableService.appendLog(
            "🔁 Stream preso (timeouts repetidos sem FILE_BLOCK): a fazer STOP→WAKE para destrancar o firmware"
        )
        sendSdkMessages(TlvWriter.Message(TxRxMessageType.CAMERA_COMMAND, byteArrayOf(CAMERA_COMMAND_STOP.toByte())))
        // Pequeno intervalo entre STOP e WAKE para o firmware processar o STOP
        // antes de receber a nova ordem.
        mainHandler.postDelayed({
            if (!sdkStreamEnabled) return@postDelayed
            sendSdkMessages(TlvWriter.Message(TxRxMessageType.CAMERA_COMMAND, byteArrayOf(CAMERA_COMMAND_WAKE.toByte())))
        }, 400L)
    }

    private fun scheduleSdkStreamCaptureTimeout(source: String) {
        sdkStreamCaptureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val timeoutRunnable = Runnable {
            sdkStreamCaptureTimeoutRunnable = null
            if (!sdkStreamEnabled || protocolFlavor != OmiProtocolFlavor.SDK_FIRMWARE || !sdkStreamCaptureInFlight) {
                return@Runnable
            }
            val now = System.currentTimeMillis()
            if (now - sdkStreamLastCaptureMs >= SDK_STREAM_CAPTURE_TIMEOUT_MS) {
                sdkStreamCaptureInFlight = false
                sdkStreamConsecutiveTimeouts++
                sdkStreamTotalTimeouts++
                if (now - sdkStreamLastTimeoutLogMs > 2000L) {
                    WearableService.appendLog(
                        "⚠ Stream: timeout de frame, a continuar " +
                            "(source=$source seq=$sdkStreamConsecutiveTimeouts total=$sdkStreamTotalTimeouts). ${streamDiagSnapshot()}"
                    )
                    sdkStreamLastTimeoutLogMs = now
                }
                maybeRecoverStuckStream(now)
                scheduleNextSdkStreamCapture("timeout:$source")
            } else {
                scheduleSdkStreamCaptureTimeout(source)
            }
        }
        sdkStreamCaptureTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, SDK_STREAM_CAPTURE_TIMEOUT_MS)
    }

    fun setIntervalCapture(seconds: Int) {
        writePhotoControl(seconds.coerceIn(5, 300).toByte())
        WearableService.appendLog("📷 Intervalo: ${seconds}s")
    }

    private fun writePhotoControl(command: Byte) {
        val gatt = bluetoothGatt ?: run { Log.e(TAG, "Not connected"); return }
        val char = gatt.getService(LEGACY_SERVICE_UUID)?.getCharacteristic(PHOTO_CONTROL_UUID)
            ?: run { Log.e(TAG, "Photo control char not found"); return }

        enqueueGattOperation("photo control cmd=${command.toInt()}") {
            @Suppress("DEPRECATION")
            char.value = byteArrayOf(command)
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(char)
        }
    }

    // ── Audio notification control ──
    // The firmware always runs the microphone; subscribing to AUDIO_DATA enables the data flow.
    // Audio notifications are enabled during setupNotificationsAndRead(), so
    // startMicrophone/stopMicrophone toggle the subscription.

    override fun startMicrophone(sensorRate: Int) {
        when (protocolFlavor) {
            OmiProtocolFlavor.LEGACY -> startLegacyMicrophone()
            OmiProtocolFlavor.SDK_FIRMWARE -> startSdkMicrophone(sensorRate)
            OmiProtocolFlavor.UNKNOWN -> Unit
        }
    }

    override fun stopMicrophone() {
        when (protocolFlavor) {
            OmiProtocolFlavor.LEGACY -> stopLegacyMicrophone()
            OmiProtocolFlavor.SDK_FIRMWARE -> {
                val caller = Thread.currentThread().stackTrace
                    .drop(2).take(12)
                    .joinToString("\n  ") { "${it.className.substringAfterLast('.')}::${it.methodName}:${it.lineNumber}" }
                WearableService.appendLog("🛑 stopMic caller: $caller")
                sendSdkMessages(TlvWriter.Message(TxRxMessageType.MICROPHONE_COMMAND, byteArrayOf(MICROPHONE_COMMAND_STOP.toByte())))
                WearableService.appendLog("🎤 Microfone: comando stop (SDK)")
            }
            OmiProtocolFlavor.UNKNOWN -> Unit
        }
    }

    // ── Camera stubs (firmware camera is always active when connected) ──

    override fun wakeCamera() {
        if (protocolFlavor == OmiProtocolFlavor.SDK_FIRMWARE) {
            sendSdkMessages(TlvWriter.Message(TxRxMessageType.CAMERA_COMMAND, byteArrayOf(CAMERA_COMMAND_WAKE.toByte())))
        }
    }

    fun sleepCamera() {
        if (protocolFlavor == OmiProtocolFlavor.SDK_FIRMWARE) {
            sendSdkMessages(TlvWriter.Message(TxRxMessageType.CAMERA_COMMAND, byteArrayOf(CAMERA_COMMAND_SLEEP.toByte())))
        }
    }

    fun focusCamera() {
        if (protocolFlavor == OmiProtocolFlavor.SDK_FIRMWARE) {
            ensureCameraSensorRateForCapture()
            sendSdkMessages(TlvWriter.Message(TxRxMessageType.CAMERA_COMMAND, byteArrayOf(CAMERA_COMMAND_FOCUS.toByte())))
        }
    }

    // ── WiFi control ──

    fun sendWifiSSID(ssid: String) {
        when (protocolFlavor) {
            OmiProtocolFlavor.SDK_FIRMWARE -> sendSdkMessages(
                TlvWriter.Message(TxRxMessageType.SET_WIFI_SSID, ssid.toByteArray(Charsets.UTF_8))
            )
            OmiProtocolFlavor.LEGACY, OmiProtocolFlavor.UNKNOWN -> {
                Log.w(TAG, "WiFi not supported by this firmware")
                WearableService.appendLog("⚠ WiFi não suportado por este firmware")
            }
        }
    }

    fun sendWifiPassword(password: String) {
        when (protocolFlavor) {
            OmiProtocolFlavor.SDK_FIRMWARE -> sendSdkMessages(
                TlvWriter.Message(TxRxMessageType.SET_WIFI_PASSWORD, password.toByteArray(Charsets.UTF_8))
            )
            OmiProtocolFlavor.LEGACY, OmiProtocolFlavor.UNKNOWN -> Log.w(TAG, "WiFi not supported by this firmware")
        }
    }

    override fun sendWifiEnabled(enabled: Boolean) {
        when (protocolFlavor) {
            OmiProtocolFlavor.SDK_FIRMWARE -> sendSdkMessages(
                TlvWriter.Message(TxRxMessageType.SET_WIFI_CONNECTION_ENABLED, byteArrayOf(if (enabled) 1 else 0)),
                TlvWriter.Message(TxRxMessageType.IS_WIFI_CONNECTED),
                TlvWriter.Message(TxRxMessageType.IP_ADDRESS)
            )
            OmiProtocolFlavor.LEGACY, OmiProtocolFlavor.UNKNOWN -> Log.w(TAG, "WiFi not supported by this firmware")
        }
    }

    override fun sendWifiCredentialsAndEnable(ssid: String, password: String) {
        when (protocolFlavor) {
            OmiProtocolFlavor.SDK_FIRMWARE -> sendSdkMessages(
                TlvWriter.Message(TxRxMessageType.SET_WIFI_SSID, ssid.toByteArray(Charsets.UTF_8)),
                TlvWriter.Message(TxRxMessageType.SET_WIFI_PASSWORD, password.toByteArray(Charsets.UTF_8)),
                TlvWriter.Message(TxRxMessageType.SET_WIFI_CONNECTION_ENABLED, byteArrayOf(1)),
                TlvWriter.Message(TxRxMessageType.IS_WIFI_CONNECTED),
                TlvWriter.Message(TxRxMessageType.IP_ADDRESS)
            )
            OmiProtocolFlavor.LEGACY, OmiProtocolFlavor.UNKNOWN -> {
                Log.w(TAG, "WiFi not supported by this firmware")
                WearableService.appendLog("⚠ WiFi não suportado por este firmware")
            }
        }
    }

    override fun requestWifiInfo() {
        when (protocolFlavor) {
            OmiProtocolFlavor.SDK_FIRMWARE -> sendSdkMessages(
                TlvWriter.Message(TxRxMessageType.GET_WIFI_SSID),
                TlvWriter.Message(TxRxMessageType.GET_WIFI_CONNECTION_ENABLED),
                TlvWriter.Message(TxRxMessageType.IS_WIFI_CONNECTED),
                TlvWriter.Message(TxRxMessageType.IS_WIFI_SECURE),
                TlvWriter.Message(TxRxMessageType.IP_ADDRESS)
            )
            OmiProtocolFlavor.LEGACY, OmiProtocolFlavor.UNKNOWN -> Log.w(TAG, "WiFi not supported by this firmware")
        }
    }

    override fun applyMicrophoneProfile(profile: GlassesMicrophoneProfile) {
        if (protocolFlavor != OmiProtocolFlavor.SDK_FIRMWARE) {
            WearableService.appendLog("Omi: configuracao de microfone so disponivel no protocolo SDK")
            return
        }
        sendSdkMessages(
            TlvWriter.Message(TxRxMessageType.SET_MICROPHONE_CONFIGURATION, profile.toConfigurationPayload()),
            TlvWriter.Message(TxRxMessageType.GET_MICROPHONE_CONFIGURATION),
            TlvWriter.Message(TxRxMessageType.MICROPHONE_STATUS),
        )
        WearableService.appendLog("Omi config microfone aplicada: ${profile.profileId}")
    }

    override fun onTxRxBytesReceived(data: ByteArray) {
        if (protocolFlavor == OmiProtocolFlavor.SDK_FIRMWARE || onUdpTxNeeded != null) {
            sdkRxHandler.onRxBytes(data)
        }
    }

    private fun startLegacyMicrophone() {
        if (audioNotificationsEnabled) {
            WearableService.appendLog("🎤 Áudio já ativo (u-law streaming)")
            return
        }
        val gatt = bluetoothGatt ?: return
        val audioChar = gatt.getService(LEGACY_SERVICE_UUID)?.getCharacteristic(AUDIO_DATA_UUID) ?: return
        gatt.setCharacteristicNotification(audioChar, true)
        audioChar.getDescriptor(CCCD_UUID)?.let { cccd ->
            enqueueGattOperation("enable audio notify") {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
        audioNotificationsEnabled = true
        WearableService.appendLog("🎤 Microfone: ativado (u-law)")
    }

    private fun stopLegacyMicrophone() {
        if (!audioNotificationsEnabled) return
        val gatt = bluetoothGatt ?: return
        val audioChar = gatt.getService(LEGACY_SERVICE_UUID)?.getCharacteristic(AUDIO_DATA_UUID) ?: return
        gatt.setCharacteristicNotification(audioChar, false)
        audioChar.getDescriptor(CCCD_UUID)?.let { cccd ->
            enqueueGattOperation("disable audio notify") {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
        audioNotificationsEnabled = false
        WearableService.appendLog("🎤 Microfone: desativado")
    }

    override fun attachCameraMetrics(metrics: com.example.peciwearables.integration.image.camera.CameraMetrics?) {
        // Liga (ou desliga, com null) o agregador ao FileTransferHandler interno.
        sdkRxHandler.fileTransferHandler.metrics = metrics
    }

    override fun applyCameraSettings(
        resolution: Int?,
        qualityFactor: Int?,
        cameraRateMs: Int?,
    ) {
        if (protocolFlavor != OmiProtocolFlavor.SDK_FIRMWARE) {
            WearableService.appendLog("⚠ Omi: configurações de câmera só disponíveis no protocolo SDK")
            return
        }

        val messages = mutableListOf<TlvWriter.Message<TxRxMessageType>>()

        cameraRateMs?.let { requestedRate ->
            val clampedRate = normalizeSensorRateMsForSdk(requestedRate)
            messages += buildSetSensorRateMessage(SENSOR_TYPE_CAMERA, clampedRate)
        }

        val cameraConfigPayload = buildCameraConfigurationPayload(
            resolution = resolution,
            qualityFactor = qualityFactor,
        )
        if (cameraConfigPayload != null) {
            messages += TlvWriter.Message(TxRxMessageType.SET_CAMERA_CONFIGURATION, cameraConfigPayload)
            messages += TlvWriter.Message(TxRxMessageType.GET_CAMERA_CONFIGURATION)
            messages += TlvWriter.Message(TxRxMessageType.CAMERA_STATUS)
        }

        if (messages.isEmpty()) {
            WearableService.appendLog("ℹ Omi: nenhuma configuração de câmera para aplicar")
            return
        }

        sdkRxHandler.resetCameraDataReassembly("camera config update")
        sendSdkMessages(*messages.toTypedArray())
        WearableService.appendLog(
            "🛠 Omi config câmera aplicada: " +
                "res=${resolution ?: "-"} qf=${qualityFactor ?: "-"} rate=${cameraRateMs ?: "-"}ms"
        )
    }

    private fun buildCameraConfigurationPayload(
        resolution: Int?,
        qualityFactor: Int?,
    ): ByteArray? {
        val parts = mutableListOf<Byte>()

        resolution?.coerceIn(CAMERA_RESOLUTION_MIN, CAMERA_RESOLUTION_MAX)?.let { value ->
            parts += CAMERA_CONFIGURATION_RESOLUTION.toByte()
            parts += (value and 0xFF).toByte()
            parts += ((value shr 8) and 0xFF).toByte()
        }

        qualityFactor?.coerceIn(CAMERA_QUALITY_FACTOR_MIN, CAMERA_QUALITY_FACTOR_MAX)?.let { value ->
            parts += CAMERA_CONFIGURATION_QUALITY_FACTOR.toByte()
            parts += (value and 0xFF).toByte()
            parts += ((value shr 8) and 0xFF).toByte()
        }

        return if (parts.isEmpty()) null else parts.toByteArray()
    }

    private fun startSdkMicrophone(sensorRate: Int) {
        val requestedRate = sensorRate.takeIf { it > 0 }
        ensureMicrophoneSensorRateForCapture(requestedRate)
        if (!sdkMicrophoneConfigured) {
            sendSdkMessages(TlvWriter.Message(TxRxMessageType.GET_MICROPHONE_CONFIGURATION))
        }
        sendSdkMessages(
            TlvWriter.Message(TxRxMessageType.MICROPHONE_COMMAND, byteArrayOf(MICROPHONE_COMMAND_START.toByte()))
        )
        WearableService.appendLog("🎤 Microfone: ativado (SDK)")
    }

    private fun enableCameraSensor(rateMs: Int = DEFAULT_CAMERA_RATE_MS) {
        sendSdkMessages(
            buildSetSensorRateMessage(SENSOR_TYPE_CAMERA, normalizeSensorRateMsForSdk(rateMs))
        )
    }

    private fun enableMicrophoneSensor(rateMs: Int = DEFAULT_MICROPHONE_RATE_MS) {
        sendSdkMessages(
            buildSetSensorRateMessage(SENSOR_TYPE_MICROPHONE, normalizeSensorRateMsForSdk(rateMs))
        )
    }

    private fun ensureCameraSensorRateForCapture(requestedRateMs: Int? = null) {
        val currentRate = _glassesSettings.value.cameraRateMs
        val targetRate = when {
            requestedRateMs != null -> normalizeSensorRateMsForSdk(requestedRateMs)
            currentRate == 0 -> DEFAULT_CAMERA_RATE_MS
            else -> null
        }
        if (targetRate != null && currentRate != targetRate) {
            enableCameraSensor(targetRate)
        }
    }

    private fun ensureMicrophoneSensorRateForCapture(requestedRateMs: Int? = null) {
        val currentRate = _glassesSettings.value.microphoneRateMs
        val targetRate = when {
            requestedRateMs != null -> normalizeSensorRateMsForSdk(requestedRateMs)
            currentRate == 0 -> DEFAULT_MICROPHONE_RATE_MS
            else -> null
        }
        if (targetRate != null && currentRate != targetRate) {
            enableMicrophoneSensor(targetRate)
        }
    }

    private fun buildSetSensorRateMessage(sensorType: Int, rateMs: Int): TlvWriter.Message<TxRxMessageType> =
        TlvWriter.Message(
            TxRxMessageType.SET_SENSOR_CONFIGURATION,
            byteArrayOf(
                sensorType.toByte(),
                (rateMs and 0xFF).toByte(),
                ((rateMs shr 8) and 0xFF).toByte()
            )
        )

    private fun activeImuRateMs(): Int =
        if (onUdpTxNeeded != null && sdkStreamEnabled) {
            WIFI_VIDEO_IMU_RATE_MS
        } else {
            imuRateMs
        }

    private fun applyImuSensorConfiguration(rateMs: Int = DEFAULT_IMU_RATE_MS) {
        val normalized = normalizeImuSensorRateMs(rateMs)
        sendSdkMessages(
            buildSetSensorRateMessage(SENSOR_TYPE_ACCELERATION, 0),
            buildSetSensorRateMessage(SENSOR_TYPE_GYROSCOPE, 0),
            buildSetSensorRateMessage(SENSOR_TYPE_MAGNETOMETER, 0),
            buildSetSensorRateMessage(SENSOR_TYPE_GAME_ROTATION, normalized),
        )
        WearableService.appendLog("🧭 Omi orientation SDK configurado (${normalized}ms, quat-only)")
    }

    private fun sendSdkHandshake() {
        sdkRxHandler.reset()
        sendSdkMessages(
            TlvWriter.Message(TxRxMessageType.IS_CHARGING),
            TlvWriter.Message(TxRxMessageType.GET_BATTERY_CURRENT),
            TlvWriter.Message(TxRxMessageType.GET_ID),
            TlvWriter.Message(TxRxMessageType.GET_MTU),
            TlvWriter.Message(TxRxMessageType.GET_NAME),
            TlvWriter.Message(TxRxMessageType.GET_TYPE),
            TlvWriter.Message(TxRxMessageType.GET_CURRENT_TIME),
            TlvWriter.Message(TxRxMessageType.GET_SENSOR_CONFIGURATION),
            TlvWriter.Message(TxRxMessageType.GET_SENSOR_SCALARS),
            TlvWriter.Message(TxRxMessageType.GET_VIBRATION_LOCATIONS),
            TlvWriter.Message(TxRxMessageType.GET_FILE_TYPES),
            TlvWriter.Message(TxRxMessageType.IS_WIFI_AVAILABLE)
        )

        sdkHandshakeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val timeoutRunnable = Runnable {
            sdkHandshakeTimeoutRunnable = null
            if (!sdkRxHandler.isConnected) {
                val missing = sdkRxHandler.pendingResponseTypes().joinToString(",") { it.name }
                WearableService.appendLog("⚠ Omi SDK: handshake timeout — forçado (missing=$missing)")
                sdkRxHandler.forceHandshakeComplete()
            }
        }
        sdkHandshakeTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, 3000L)
    }

    private fun requestSdkRuntimeInformation() {
        sendSdkMessages(
            TlvWriter.Message(TxRxMessageType.GET_MTU),
            TlvWriter.Message(TxRxMessageType.GET_SENSOR_CONFIGURATION),
            TlvWriter.Message(TxRxMessageType.GET_CAMERA_CONFIGURATION),
            TlvWriter.Message(TxRxMessageType.CAMERA_STATUS),
            TlvWriter.Message(TxRxMessageType.GET_MICROPHONE_CONFIGURATION),
            TlvWriter.Message(TxRxMessageType.MICROPHONE_STATUS),
            TlvWriter.Message(TxRxMessageType.GET_WIFI_CONNECTION_ENABLED),
            TlvWriter.Message(TxRxMessageType.IS_WIFI_CONNECTED),
            TlvWriter.Message(TxRxMessageType.IS_WIFI_SECURE),
            TlvWriter.Message(TxRxMessageType.IP_ADDRESS)
        )
    }

    private fun sendSdkMessages(vararg messages: TlvWriter.Message<TxRxMessageType>) {
        if (messages.isEmpty()) return
        val packets = if (onUdpTxNeeded != null) {
            listOf(TlvWriter.encodeAll(*messages))
        } else {
            TlvWriter.encodePartitioned(messages.toList(), currentMtu)
        }
        if (packets.size > 1) {
            WearableService.appendLog("ℹ Omi SDK: ${messages.size} TLVs repartidos em ${packets.size} pacotes (MTU=$currentMtu)")
        }
        packets.forEach { packet ->
            sendSdkPayload(packet)
        }
    }

    private fun sendSdkPayload(payload: ByteArray) {
        onUdpTxNeeded?.let {
            val now = System.currentTimeMillis()
            if (sdkUdpTxWindowStartMs == 0L || now - sdkUdpTxWindowStartMs >= 1000L) {
                if (sdkUdpTxCountInWindow > 0) {
                    WearableService.appendLog("📡 Omi SDK TX UDP: ${sdkUdpTxCountInWindow} msg/s")
                }
                sdkUdpTxWindowStartMs = now
                sdkUdpTxCountInWindow = 0
            }
            sdkUdpTxCountInWindow++
            it(payload)
            return
        }

        val gatt = bluetoothGatt
        if (gatt == null) {
            WearableService.appendLog("⚠ Omi SDK: sem rota TX (BLE/UDP indisponível)")
            return
        }
        val txChar = gatt.getService(SDK_SERVICE_UUID)?.getCharacteristic(SDK_TX_UUID)
            ?: run {
                WearableService.appendLog("⚠ Omi SDK: característica TX não encontrada")
                return
            }

        val maxPayload = currentMtu - 3
        if (maxPayload > 0 && payload.size > maxPayload) {
            WearableService.appendLog("⚠ Omi SDK: payload ${payload.size}B excede MTU útil ${maxPayload}B")
            return
        }

        enqueueGattOperation("sdk tx ${payload.size}B") {
            @Suppress("DEPRECATION")
            txChar.value = payload
            txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(txChar)
        }
    }

    private data class GlassesMatch(
        val isMatch: Boolean,
        val reason: String,
        val sdkTypeFromManufacturer: Int?,
        val sdkTypeFromServiceData: Int?,
    )

    private fun evaluateGlassesMatch(result: ScanResult): GlassesMatch {
        val scanRecord = result.scanRecord
        val serviceUuids = scanRecord?.serviceUuids.orEmpty()
        if (serviceUuids.any { it.uuid == LEGACY_SERVICE_UUID }) {
            return GlassesMatch(
                isMatch = true,
                reason = "svc_uuid_legacy",
                sdkTypeFromManufacturer = rawSdkTypeFromManufacturer(scanRecord),
                sdkTypeFromServiceData = rawSdkTypeFromServiceData(scanRecord),
            )
        }

        val sdkTypeFromManufacturer = rawSdkTypeFromManufacturer(scanRecord)
        if (sdkTypeFromManufacturer == SDK_DEVICE_TYPE_GLASSES) {
            return GlassesMatch(
                isMatch = true,
                reason = "manufacturer_type=GLASSES(4)",
                sdkTypeFromManufacturer = sdkTypeFromManufacturer,
                sdkTypeFromServiceData = rawSdkTypeFromServiceData(scanRecord),
            )
        }

        val sdkTypeFromServiceData = rawSdkTypeFromServiceData(scanRecord)
        if (sdkTypeFromServiceData == SDK_DEVICE_TYPE_GLASSES) {
            return GlassesMatch(
                isMatch = true,
                reason = "service_data_type=GLASSES(4)",
                sdkTypeFromManufacturer = sdkTypeFromManufacturer,
                sdkTypeFromServiceData = sdkTypeFromServiceData,
            )
        }

        val lower = result.device.name?.lowercase().orEmpty()
        if ("omi" in lower || "glass" in lower) {
            return GlassesMatch(
                isMatch = true,
                reason = "name_hint",
                sdkTypeFromManufacturer = sdkTypeFromManufacturer,
                sdkTypeFromServiceData = sdkTypeFromServiceData,
            )
        }

        return GlassesMatch(
            isMatch = false,
            reason = "no_glasses_signal",
            sdkTypeFromManufacturer = sdkTypeFromManufacturer,
            sdkTypeFromServiceData = sdkTypeFromServiceData,
        )
    }

    private fun rawSdkTypeFromManufacturer(scanRecord: ScanRecord?): Int? {
        val manufacturerData = scanRecord?.manufacturerSpecificData ?: return null
        for (index in 0 until manufacturerData.size()) {
            val payload = manufacturerData.valueAt(index) ?: continue
            if (payload.size < 3) continue
            return payload[2].toInt() and 0xFF
        }
        return null
    }

    private fun rawSdkTypeFromServiceData(scanRecord: ScanRecord?): Int? {
        val serviceData = scanRecord?.getServiceData(ParcelUuid(SERVICE_DATA_UUID)) ?: return null
        if (serviceData.isEmpty()) return null
        return serviceData[0].toInt() and 0xFF
    }

    private fun buildScanDiagnostics(result: ScanResult, match: GlassesMatch): String {
        val serviceUuids = result.scanRecord?.serviceUuids
            ?.joinToString(",") { it.uuid.toString().substring(0, 8) }
            ?: "none"
        val manufacturerInfo = buildString {
            val manufacturerData = result.scanRecord?.manufacturerSpecificData
            if (manufacturerData == null || manufacturerData.size() == 0) {
                append("none")
            } else {
                for (index in 0 until manufacturerData.size()) {
                    if (index > 0) append(",")
                    val key = manufacturerData.keyAt(index)
                    val payload = manufacturerData.valueAt(index)
                    append("0x${key.toString(16)}:${payload?.size ?: 0}B")
                }
            }
        }
        return "rssi=${result.rssi} reason=${match.reason} " +
            "svc=[$serviceUuids] mfg=[$manufacturerInfo] " +
            "sdkType(mfg=${match.sdkTypeFromManufacturer ?: "?"},svc=${match.sdkTypeFromServiceData ?: "?"})"
    }

    private fun isLikelyGlasses(result: ScanResult): Boolean {
        return evaluateGlassesMatch(result).isMatch
    }

    private fun logSdkWifiSnapshot(ip: String? = null) {
        val secureLabel = when (sdkWifiSecure) {
            true -> "segura"
            false -> "aberta"
            null -> "?"
        }
        WearableService.appendLog(
            "📶 Omi Wi‑Fi: enabled=${sdkWifiEnabled ?: "?"} connected=${sdkWifiConnected ?: "?"} ip=${ip ?: "?"} secure=$secureLabel"
        )
    }

    // ── Write queue ──

    private fun resumeSdkStreamAfterReconnectIfNeeded() {
        if (!sdkStreamDesired || sdkStreamEnabled || protocolFlavor != OmiProtocolFlavor.SDK_FIRMWARE) {
            return
        }
        WearableService.appendLog("♻ Omi BLE: a retomar stream após reconnect")
        mainHandler.postDelayed(
            { startSdkCameraStream(sdkStreamTargetFps) },
            180L
        )
    }

    private fun clearActiveGattOperationState(clearQueue: Boolean) {
        cancelGattOpWatchdog()
        activeGattOpName = null
        activeGattOpStartedElapsedMs = 0L
        isWritePending = false
        if (clearQueue) {
            writeQueue.clear()
        }
    }

    private fun cancelGattOpWatchdog() {
        gattOpWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        gattOpWatchdogRunnable = null
    }

    private fun scheduleGattOpWatchdog(opName: String, opStartedElapsedMs: Long) {
        cancelGattOpWatchdog()
        val watchdog = Runnable {
            gattOpWatchdogRunnable = null
            if (!isWritePending || activeGattOpName != opName || activeGattOpStartedElapsedMs != opStartedElapsedMs) {
                return@Runnable
            }
            val ageMs = (SystemClock.elapsedRealtime() - opStartedElapsedMs).coerceAtLeast(0L)
            if (ageMs < GATT_OP_TIMEOUT_MS) {
                scheduleGattOpWatchdog(opName, opStartedElapsedMs)
                return@Runnable
            }
            handleGattOpWatchdogTimeout(opName, ageMs)
        }
        gattOpWatchdogRunnable = watchdog
        mainHandler.postDelayed(watchdog, GATT_OP_TIMEOUT_MS)
    }

    private fun handleGattOpWatchdogTimeout(opName: String, ageMs: Long) {
        gattOpTimeoutTotal++
        sdkWriteRejectedTotal++
        clearActiveGattOperationState(clearQueue = true)
        WearableService.appendLog(
            "⚠ Omi BLE: watchdog detectou write sem callback ($opName, ${ageMs}ms, total=$gattOpTimeoutTotal). " +
                "A recuperar ligação…"
        )

        val gatt = bluetoothGatt
        if (gatt == null) {
            if (shouldAutoRetry(8)) {
                scheduleAutoRetry(8, reason = "watchdog-no-gatt")
            } else {
                _state.value = BleDeviceState.ERROR
            }
            return
        }

        if (shouldAutoRetry(8)) {
            skipNextDisconnectAutoRetry = true
            scheduleAutoRetry(8, reason = "watchdog:$opName")
        }
        _state.value = BleDeviceState.CONNECTING
        try {
            gatt.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt.close()
        } catch (_: Exception) {
        }
        if (bluetoothGatt === gatt) {
            bluetoothGatt = null
        }
    }

    private fun enqueueWrite(op: () -> Unit) {
        writeQueue.add(op)
        if (!isWritePending) processNextWrite()
    }

    private fun enqueueGattOperation(opName: String, op: () -> Boolean) {
        enqueueWrite {
            activeGattOpName = opName
            activeGattOpStartedElapsedMs = SystemClock.elapsedRealtime()
            val accepted = try {
                op()
            } catch (e: Exception) {
                Log.e(TAG, "$opName threw exception: ${e.message}")
                false
            }
            if (!accepted) {
                sdkWriteRejectedTotal++
                Log.e(TAG, "$opName rejected by GATT stack")
                WearableService.appendLog(
                    "⚠ Omi BLE: operação rejeitada ($opName). ${streamDiagSnapshot()}"
                )
                clearActiveGattOperationState(clearQueue = false)
                mainHandler.postDelayed({ processNextWrite() }, 40L)
                return@enqueueWrite
            }
            scheduleGattOpWatchdog(opName, activeGattOpStartedElapsedMs)
        }
    }

    private fun processNextWrite() {
        val next = writeQueue.poll() ?: return
        isWritePending = true
        next()
    }
}
