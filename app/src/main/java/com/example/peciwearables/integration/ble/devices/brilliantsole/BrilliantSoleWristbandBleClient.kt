package com.example.peciwearables.integration.ble.devices.brilliantsole

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.example.peciwearables.integration.WearableService
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.ble.GattWatchdog
import com.example.peciwearables.integration.ble.SDK_DEVICE_TYPE_GLASSES_RAW
import com.example.peciwearables.integration.ble.SDK_SERVICE_DATA_UUID
import com.example.peciwearables.integration.ble.hasGlassesSdkDeviceTypeHint
import com.example.peciwearables.integration.ble.sdkDeviceTypeHintFromServiceData
import com.example.peciwearables.integration.ble.sdkDeviceTypeHintsFromManufacturerPayloads
import com.example.peciwearables.integration.protocol.ImuSample
import com.example.peciwearables.integration.wearable.GattProfileObserver
import com.example.peciwearables.integration.wearable.GattProfileSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Cliente BLE para Brilliant Sole (wristband / insole com sensores).
 *
 * UUIDs reais obtidos do JavaScript SDK oficial:
 * https://github.com/brilliantsole/BrilliantSole-JavaScript-SDK
 *
 * Protocolo TX/RX:
 * - Main Service ea6d0000 contém RX (notify) e TX (write)
 * - Mensagens: [messageTypeEnum (1B)] [dataLength (1B)] [data...]
 * - Dados recebidos via RX (notify), comandos enviados via TX (write)
 * - Battery e Device Info são serviços BLE standard
 */
@SuppressLint("MissingPermission")
class BrilliantSoleWristbandBleClient(private val context: Context) : BrilliantSoleWristbandBleClientApi {

    companion object {
        private const val TAG = "BrilliantSoleWristbandBleClient"

        // ── UUIDs do Brilliant Sole (do JS SDK bluetoothUUIDs.ts) ──
        // Main service: ea6d + "0000" + base
        val SERVICE_UUID: UUID = UUID.fromString("ea6d0000-a725-4f9b-893d-c3913e33b39f")
        // RX characteristic (NOTIFY — receber dados do dispositivo)
        val CHAR_RX_UUID: UUID = UUID.fromString("ea6d1000-a725-4f9b-893d-c3913e33b39f")
        // TX characteristic (WRITE — enviar comandos ao dispositivo)
        val CHAR_TX_UUID: UUID = UUID.fromString("ea6d1001-a725-4f9b-893d-c3913e33b39f")

        // Battery Service (Bluetooth SIG standard)
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        // Device Information Service (standard)
        val DEVICE_INFO_SERVICE_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val MANUFACTURER_NAME_UUID: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        val MODEL_NUMBER_UUID: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val FIRMWARE_REV_UUID: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        val HARDWARE_REV_UUID: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
        val SOFTWARE_REV_UUID: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TARGET_MTU = 517

        internal fun shouldConnectSharedSdkAdvertiser(
            manufacturerPayloads: Iterable<ByteArray>,
            serviceData: ByteArray? = null,
        ): Boolean = !hasGlassesSdkDeviceTypeHint(manufacturerPayloads, serviceData)
    }

    private val _state = MutableStateFlow(BleDeviceState.DISCONNECTED)
    override val state: StateFlow<BleDeviceState> = _state

    private val _batteryLevel = MutableStateFlow(-1)
    override val batteryLevel: StateFlow<Int> = _batteryLevel

    private val _deviceName = MutableStateFlow("")
    override val deviceName: StateFlow<String> = _deviceName

    private val _firmwareVersion = MutableStateFlow("")
    override val firmwareVersion: StateFlow<String> = _firmwareVersion

    private val _gattProfile = MutableStateFlow<GattProfileSnapshot?>(null)
    override val gattProfile: StateFlow<GattProfileSnapshot?> = _gattProfile

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false

    private val writeQueue = ConcurrentLinkedQueue<() -> Unit>()
    private var isWritePending = false
    private var negotiatedMtu: Int = 23
    private var protocolMtu: Int = 0

    private val clientScope = CoroutineScope(Dispatchers.Main.immediate)
    private val gattWatchdog = GattWatchdog(timeoutMs = 1_800L, scope = clientScope)

    // Callback para dados RX recebidos do dispositivo
    var onRxData: ((messageType: Int, data: ByteArray) -> Unit)? = null

    // Callback para publicar amostras IMU para a camada de Service/UI
    // inclui timestamp cru (16-bit) do pacote e timestamp estimado em ms
    override var onImuSample: ((sample: ImuSample, timestampRaw16: Int, timestampEstimatedMs: Long) -> Unit)? = null

    // Callback para resultados de inferência TFLite executada na pulseira
    override var onTfliteInference: ((label: String, score: Float, timestampEstimatedMs: Long, values: FloatArray) -> Unit)? = null

    // Ultima amostra conhecida por componente (alguns pacotes podem trazer so parte dos sensores)
    private var lastAx: Short = 0
    private var lastAy: Short = 0
    private var lastAz: Short = 0
    private var lastGx: Short = 0
    private var lastGy: Short = 0
    private var lastGz: Short = 0
    private var lastMx: Short = 0
    private var lastMy: Short = 0
    private var lastMz: Short = 0
    private var lastPressure: ShortArray = ShortArray(8)
    private var tfliteIsReady: Boolean = false
    private var tfliteInferencingEnabled: Boolean = false
    private var pendingTfliteEnable: Boolean = false
    private val defaultTfliteClasses = arrayOf("0_idle", "1_walk")
    private val tfliteClasses: List<String> = loadTfliteClassesFromAssets()
    private var currentTfliteName: String = ""
    private var currentFileTypeEnum: Int = -1
    private var currentFileLengthBytes: Long = -1L
    private var currentFileChecksum: Long = -1L
    private var currentFileTransferStatus: Int = FILE_TRANSFER_STATUS_IDLE
    private var fileBytesTransferredAck: Long = 0L
    private var ts16BaseUnixMs: Long = 0L
    private var ts16EpochOffset: Long = 0L
    private var ts16LastRaw: Int = -1
    private var ts16LastEstimatedMs: Long = 0L

    // ── Scan ──

    fun startScan() {
        if (isScanning) return

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Log.e(TAG, "BluetoothAdapter not available")
            WearableService.appendLog("❌ Bluetooth não disponível")
            _state.value = BleDeviceState.ERROR
            return
        }

        scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "BLE scanner not available")
            WearableService.appendLog("❌ Scanner BLE não disponível")
            _state.value = BleDeviceState.ERROR
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        Log.d(TAG, "BLE scan started for Brilliant Sole (ea6d0000)")
        WearableService.appendLog("⏳ A procurar Brilliant Sole (ea6d0000)...")
    }

    fun stopScan() {
        if (!isScanning) return
        scanner?.stopScan(scanCallback)
        isScanning = false
        Log.d(TAG, "BLE scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val match = evaluateSoleAdvertiser(result)
            if (!match.shouldConnect) {
                Log.d(TAG, "Ignoring ea6d0000 advertiser for Brilliant Sole: ${match.reason}")
                WearableService.appendLog("Ignorado candidato B.Sole: ${match.reason}")
                return
            }
            Log.d(TAG, "Brilliant Sole found: ${result.device.name} [${result.device.address}]")
            WearableService.appendLog("✅ B.Sole: ${result.device.name ?: "?"} [${result.device.address}]")
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            WearableService.appendLog("❌ Scan BS falhou — erro: $errorCode")
            _state.value = BleDeviceState.ERROR
        }
    }

    // ── Connect ──

    private data class SoleAdvertiserMatch(
        val shouldConnect: Boolean,
        val reason: String,
    )

    private fun evaluateSoleAdvertiser(result: ScanResult): SoleAdvertiserMatch {
        val scanRecord = result.scanRecord
        val manufacturerPayloads = manufacturerPayloads(scanRecord)
        val serviceData = scanRecord?.getServiceData(ParcelUuid(SDK_SERVICE_DATA_UUID))
        val manufacturerTypes = sdkDeviceTypeHintsFromManufacturerPayloads(manufacturerPayloads)
        val serviceDataType = sdkDeviceTypeHintFromServiceData(serviceData)

        if (!shouldConnectSharedSdkAdvertiser(manufacturerPayloads, serviceData)) {
            val details = buildList {
                if (SDK_DEVICE_TYPE_GLASSES_RAW in manufacturerTypes) add("manufacturer_type=GLASSES(4)")
                if (serviceDataType == SDK_DEVICE_TYPE_GLASSES_RAW) add("service_data_type=GLASSES(4)")
            }.joinToString(", ")
            return SoleAdvertiserMatch(
                shouldConnect = false,
                reason = details.ifEmpty { "sdkDeviceType=GLASSES(4)" },
            )
        }

        return SoleAdvertiserMatch(shouldConnect = true, reason = "not_glasses")
    }

    private fun manufacturerPayloads(scanRecord: ScanRecord?): List<ByteArray> {
        val manufacturerData = scanRecord?.manufacturerSpecificData ?: return emptyList()
        return buildList {
            for (index in 0 until manufacturerData.size()) {
                manufacturerData.valueAt(index)?.let { add(it) }
            }
        }
    }

    override fun connectDevice(device: BluetoothDevice) {
        stopScan()
        Log.d(TAG, "Connecting to provided Brilliant Sole device: ${device.name} [${device.address}]")
        connect(device)
    }

    private fun connect(device: BluetoothDevice) {
        _state.value = BleDeviceState.CONNECTING
        WearableService.appendLog("🔗 A conectar ao Brilliant Sole...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect() {
        gattWatchdog.disarm()
        clientScope.cancel()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _gattProfile.value = null
        _state.value = BleDeviceState.DISCONNECTED
    }

    // ── GATT Callback ──

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to Brilliant Sole GATT")
                    WearableService.appendLog("🔗 Ligado ao GATT (B.Sole)")
                    _state.value = BleDeviceState.DISCOVERING
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from Brilliant Sole GATT")
                    _gattProfile.value = null
                    WearableService.appendLog("🔌 B.Sole desconectado")
                    _state.value = BleDeviceState.DISCONNECTED
                    gatt.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val serviceUuids = gatt.services.map { it.uuid.toString().take(8) }
                Log.d(TAG, "Services discovered: $serviceUuids")
                WearableService.appendLog("📋 Serviços BS: ${serviceUuids.joinToString(", ")}")

                // Verificar serviço principal
                val mainService = gatt.getService(SERVICE_UUID)
                if (mainService == null) {
                    Log.e(TAG, "Main service ea6d0000 not found!")
                    WearableService.appendLog("❌ Serviço ea6d0000 não encontrado!")
                    _state.value = BleDeviceState.ERROR
                    return
                }

                val chars = mainService.characteristics.map { it.uuid.toString().take(8) }
                WearableService.appendLog("📋 Chars BS: ${chars.joinToString(", ")}")

                _state.value = BleDeviceState.CONFIGURING
                _gattProfile.value = GattProfileObserver.snapshot(
                    gatt = gatt,
                    firmwareVersion = _firmwareVersion.value,
                    deviceName = _deviceName.value,
                    handshakeProbes = mapOf(
                        "hapticSupported" to true,
                        "tfliteReady" to true,
                    ),
                )
                gatt.requestMtu(TARGET_MTU)
            } else {
                Log.e(TAG, "Service discovery failed: $status")
                WearableService.appendLog("❌ Descoberta serviços BS falhou: $status")
                _state.value = BleDeviceState.ERROR
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu (status=$status)")
            negotiatedMtu = mtu.coerceAtLeast(23)
            WearableService.appendLog("📐 MTU BS: $mtu")
            setupNotificationsAndRead(gatt)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            gattWatchdog.disarm()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Write OK: ${characteristic.uuid.toString().take(8)}")
            } else {
                Log.e(TAG, "Write FAILED: ${characteristic.uuid.toString().take(8)} status=$status")
            }
            isWritePending = false
            processNextWrite()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return

            when (characteristic.uuid) {
                CHAR_RX_UUID -> {
                    // Protocolo: [msgTypeEnum (1B)] [msgLength (1B)] [data...]
                    parseRxMessages(data)
                }
                BATTERY_LEVEL_UUID -> {
                    if (data.isNotEmpty()) {
                        val level = data[0].toInt() and 0xFF
                        _batteryLevel.value = level
                        Log.d(TAG, "Battery: $level%")
                        WearableService.appendLog("🔋 Bateria BS: $level%")
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            gattWatchdog.disarm()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value ?: return
                when (characteristic.uuid) {
                    BATTERY_LEVEL_UUID -> {
                        val level = data[0].toInt() and 0xFF
                        _batteryLevel.value = level
                        WearableService.appendLog("🔋 Bateria BS lida: $level%")
                    }
                    MANUFACTURER_NAME_UUID -> {
                        val name = String(data, Charsets.UTF_8)
                        WearableService.appendLog("🏭 BS Fabricante: $name")
                    }
                    MODEL_NUMBER_UUID -> {
                        val model = String(data, Charsets.UTF_8)
                        WearableService.appendLog("📟 BS Modelo: $model")
                    }
                    FIRMWARE_REV_UUID -> {
                        val fw = String(data, Charsets.UTF_8)
                        _firmwareVersion.value = fw
                        WearableService.appendLog("📱 BS Firmware: $fw")
                    }
                    HARDWARE_REV_UUID -> {
                        val hw = String(data, Charsets.UTF_8)
                        WearableService.appendLog("🔧 BS Hardware: $hw")
                    }
                    SOFTWARE_REV_UUID -> {
                        val sw = String(data, Charsets.UTF_8)
                        WearableService.appendLog("💻 BS Software: $sw")
                    }
                }
            }
            isWritePending = false
            processNextWrite()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            gattWatchdog.disarm()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write OK: ${descriptor.characteristic.uuid.toString().take(8)}")
            }
            isWritePending = false
            processNextWrite()
        }
    }

    // ── Parse RX messages (from device) ──

    private fun parseRxMessages(data: ByteArray) {
        // Outer TxRx framing: [msgType (1B)] [dataLength (2B uint16 LE)] [data...]
        // (cf. BaseConnectionManager.ts → sendTxMessages / parseRxMessage with parseMessageLengthAsUint16=true)
        var offset = 0
        while (offset <= data.size - 3) {
            val msgType = data[offset].toInt() and 0xFF
            offset++

            // 2-byte little-endian length
            val msgLength = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2

            if (offset + msgLength > data.size) {
                WearableService.appendLog("⚠️ RX parse: comprimento $msgLength excede buffer. Parando.")
                break
            }

            val msgData = if (msgLength > 0) {
                data.copyOfRange(offset, offset + msgLength)
            } else {
                byteArrayOf()
            }
            offset += msgLength

            handleRxMessage(msgType, msgData)
        }
    }

    private fun handleRxMessage(msgType: Int, data: ByteArray) {
        when (msgType) {
            MSG_GET_NAME -> {
                if (data.isNotEmpty()) {
                    val name = String(data, Charsets.UTF_8)
                    _deviceName.value = name
                    WearableService.appendLog("📛 BS Nome: $name")
                }
            }
            MSG_GET_TYPE -> {
                if (data.isNotEmpty()) {
                    val typeNames = arrayOf("leftInsole", "rightInsole", "leftGlove", "rightGlove", "glasses", "generic")
                    val typeIdx = data[0].toInt() and 0xFF
                    val typeName = typeNames.getOrElse(typeIdx) { "unknown($typeIdx)" }
                    WearableService.appendLog("📋 BS Tipo: $typeName")
                }
            }
            MSG_IS_CHARGING -> {
                if (data.isNotEmpty()) {
                    val charging = data[0].toInt() != 0
                    WearableService.appendLog("🔌 BS Carregando: $charging")
                }
            }
            MSG_GET_BATTERY_CURRENT -> {
                if (data.size >= 2) {
                    val current = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    WearableService.appendLog("🔋 BS Corrente: ${current}mA")
                }
            }
            MSG_GET_MTU -> {
                if (data.size >= 2) {
                    var mtu = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    mtu = mtu.coerceAtMost(512)
                    protocolMtu = mtu
                    WearableService.appendLog("📐 BS protocolo MTU: $protocolMtu")
                }
            }
            MSG_GET_SENSOR_CONFIG, MSG_SET_SENSOR_CONFIG -> {
                WearableService.appendLog("📊 BS SensorConfig recebido (${data.size}B)")
            }
            MSG_TFLITE_IS_READY -> {
                tfliteIsReady = data.firstOrNull()?.toInt()?.let { it != 0 } == true
                WearableService.appendLog("🧠 BS TFLite ready: $tfliteIsReady")
                if (tfliteIsReady && pendingTfliteEnable && !tfliteInferencingEnabled) {
                    setTfliteInferencingEnabled(true)
                    sendTxMessage(MSG_GET_TFLITE_INFERENCING_ENABLED)
                }
            }
            MSG_GET_TFLITE_INFERENCING_ENABLED, MSG_SET_TFLITE_INFERENCING_ENABLED -> {
                tfliteInferencingEnabled = data.firstOrNull()?.toInt()?.let { it != 0 } == true
                WearableService.appendLog("🧠 BS inferência ativa: $tfliteInferencingEnabled")
                if (tfliteInferencingEnabled) {
                    pendingTfliteEnable = false
                } else if (pendingTfliteEnable) {
                    pendingTfliteEnable = false
                }
            }
            MSG_GET_TFLITE_NAME, MSG_SET_TFLITE_NAME -> {
                if (data.isNotEmpty()) {
                    val name = String(data, Charsets.UTF_8)
                    currentTfliteName = name
                    WearableService.appendLog("🧠 BS TFLite: $name")
                }
            }
            MSG_GET_FILE_TYPE, MSG_SET_FILE_TYPE -> {
                if (data.isNotEmpty()) {
                    currentFileTypeEnum = data[0].toInt() and 0xFF
                    WearableService.appendLog("📁 BS fileType=$currentFileTypeEnum")
                }
            }
            MSG_GET_FILE_LENGTH, MSG_SET_FILE_LENGTH -> {
                if (data.size >= 4) {
                    currentFileLengthBytes = readUInt32LE(data, 0)
                    WearableService.appendLog("📁 BS fileLength=$currentFileLengthBytes")
                }
            }
            MSG_GET_FILE_CHECKSUM, MSG_SET_FILE_CHECKSUM -> {
                if (data.size >= 4) {
                    currentFileChecksum = readUInt32LE(data, 0)
                    WearableService.appendLog("📁 BS fileChecksum=$currentFileChecksum")
                }
            }
            MSG_FILE_TRANSFER_STATUS -> {
                if (data.isNotEmpty()) {
                    currentFileTransferStatus = data[0].toInt() and 0xFF
                    WearableService.appendLog("📁 BS transferStatus=$currentFileTransferStatus")
                }
            }
            MSG_FILE_BYTES_TRANSFERRED -> {
                if (data.size >= 4) {
                    fileBytesTransferredAck = readUInt32LE(data, 0)
                }
            }
            MSG_TFLITE_INFERENCE -> {
                parseTfliteInference(data)
                onRxData?.invoke(msgType, data)
            }
            MSG_SENSOR_DATA -> {
                // Pacote: [timestamp (2B)] então blocos: [sensorType (1B)] [dataLength (1B)] [data...]
                if (data.size >= 2) {
                    val timestampRaw16 = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    val timestampEstimatedMs = estimateTimestampFromLower16(timestampRaw16)
                    var offset = 2
                    var hasImuUpdate = false

                    while (offset < data.size) {
                        if (offset >= data.size) break
                        val sensorType = data[offset].toInt() and 0xFF
                        offset++

                        if (offset >= data.size) break
                        val sensorDataLength = data[offset].toInt() and 0xFF
                        offset++

                        if (offset + sensorDataLength > data.size) {
                            WearableService.appendLog("⚠️ Sensor $sensorType: comprimento $sensorDataLength excede buffer. Parando parse.")
                            break
                        }

                        when (sensorType) {
                            SENSOR_TYPE_PRESSURE -> {
                                if (sensorDataLength >= 16) {
                                    val p = ShortArray(8)
                                    for (i in 0 until 8) {
                                        p[i] = readInt16LE(data, offset + i * 2)
                                    }
                                    lastPressure = p
                                    hasImuUpdate = true
                                }
                            }

                            SENSOR_TYPE_ACCELERATION -> {
                                if (sensorDataLength >= 6) {
                                    lastAx = readInt16LE(data, offset)
                                    lastAy = readInt16LE(data, offset + 2)
                                    lastAz = readInt16LE(data, offset + 4)
                                    hasImuUpdate = true
                                }
                            }

                            SENSOR_TYPE_GYROSCOPE -> {
                                if (sensorDataLength >= 6) {
                                    lastGx = readInt16LE(data, offset)
                                    lastGy = readInt16LE(data, offset + 2)
                                    lastGz = readInt16LE(data, offset + 4)
                                    hasImuUpdate = true
                                }
                            }

                            SENSOR_TYPE_MAGNETOMETER -> {
                                if (sensorDataLength >= 6) {
                                    lastMx = readInt16LE(data, offset)
                                    lastMy = readInt16LE(data, offset + 2)
                                    lastMz = readInt16LE(data, offset + 4)
                                    hasImuUpdate = true
                                }
                            }

                            SENSOR_TYPE_GRAVITY, SENSOR_TYPE_LINEAR_ACCELERATION, 8 -> { /* ignorar */ }
                            6, 7 -> { /* saltar */ }

                            10, 11, 12, 13 -> { /* saltar */ }

                            else -> { /* ignorar sensores desconhecidos para evitar ruído/overhead */ }
                        }

                        offset += sensorDataLength
                    }

                    if (hasImuUpdate) {
                        onImuSample?.invoke(
                            ImuSample(
                                ax = lastAx, ay = lastAy, az = lastAz,
                                gx = lastGx, gy = lastGy, gz = lastGz,
                                mx = lastMx, my = lastMy, mz = lastMz,
                                pressure = lastPressure.copyOf()
                            ),
                            timestampRaw16,
                            timestampEstimatedMs
                        )
                    }
                }
                onRxData?.invoke(msgType, data)
            }
            MSG_GET_VIBRATION_LOCATIONS -> {
                val locations = data.map { b ->
                    when (b.toInt() and 0xFF) {
                        0 -> "front"
                        1 -> "rear"
                        else -> "?(${b.toInt()})"
                    }
                }
                WearableService.appendLog("📳 BS Vibração: ${locations.joinToString(", ")}")
            }
            else -> {
                Log.d(TAG, "RX msg type=$msgType len=${data.size}")
                onRxData?.invoke(msgType, data)
            }
        }
    }

    // ── Setup notifications + reads ──

    private fun setupNotificationsAndRead(gatt: BluetoothGatt) {
        val mainService = gatt.getService(SERVICE_UUID)

        // 1. Subscribe RX characteristic (NOTIFY — receive all data from device)
        mainService?.getCharacteristic(CHAR_RX_UUID)?.let { rxChar ->
            gatt.setCharacteristicNotification(rxChar, true)
            rxChar.getDescriptor(CCCD_UUID)?.let { cccd ->
                enqueueWrite {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
            WearableService.appendLog("🔔 Subscrito RX (B.Sole)")
        }

        // 2. Battery — subscribe + read
        val battService = gatt.getService(BATTERY_SERVICE_UUID)
        battService?.getCharacteristic(BATTERY_LEVEL_UUID)?.let { batChar ->
            gatt.setCharacteristicNotification(batChar, true)
            batChar.getDescriptor(CCCD_UUID)?.let { cccd ->
                enqueueWrite {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
            enqueueWrite { gatt.readCharacteristic(batChar) }
        }

        // 3. Device Info reads
        val infoService = gatt.getService(DEVICE_INFO_SERVICE_UUID)
        infoService?.getCharacteristic(MANUFACTURER_NAME_UUID)?.let {
            enqueueWrite { gatt.readCharacteristic(it) }
        }
        infoService?.getCharacteristic(MODEL_NUMBER_UUID)?.let {
            enqueueWrite { gatt.readCharacteristic(it) }
        }
        infoService?.getCharacteristic(FIRMWARE_REV_UUID)?.let {
            enqueueWrite { gatt.readCharacteristic(it) }
        }
        infoService?.getCharacteristic(HARDWARE_REV_UUID)?.let {
            enqueueWrite { gatt.readCharacteristic(it) }
        }

        // 4. Mark as READY
        enqueueWrite {
            _state.value = BleDeviceState.READY
            Log.d(TAG, "Brilliant Sole BLE setup complete — READY")
            WearableService.appendLog("✅ Brilliant Sole READY")
            isWritePending = false
            processNextWrite()
        }

        sendTxMessage(MSG_GET_MTU)
    }

    // ── TX: Send message to device ──

    /**
     * Envia uma mensagem via TX characteristic.
     * Formato: [messageTypeEnum (1B)] [dataLength (2B uint16 LE)] [data...]
     * (igual ao BaseConnectionManager.ts do SDK oficial)
     */
    private fun sendTxMessage(messageType: Int, data: ByteArray = byteArrayOf()) {
        val gatt = bluetoothGatt ?: run {
            Log.w(TAG, "sendTxMessage: bluetoothGatt é null (msg=$messageType)")
            WearableService.appendLog("⚠️ TX msg=$messageType falhou: não ligado")
            return
        }
        val service = gatt.getService(SERVICE_UUID) ?: run {
            Log.w(TAG, "sendTxMessage: serviço ea6d0000 não encontrado")
            WearableService.appendLog("⚠️ TX msg=$messageType: serviço não encontrado")
            return
        }
        val txChar = service.getCharacteristic(CHAR_TX_UUID) ?: run {
            Log.w(TAG, "sendTxMessage: característica TX não encontrada")
            WearableService.appendLog("⚠️ TX msg=$messageType: característica TX não encontrada")
            return
        }

        // Outer TxRx framing: [msgType (1B)] [dataLength (2B uint16 LE)] [data...]
        // (cf. BaseConnectionManager.ts → sendTxMessages)
        val payload = ByteArray(3 + data.size)
        payload[0] = messageType.toByte()
        payload[1] = (data.size and 0xFF).toByte()          // low byte
        payload[2] = ((data.size shr 8) and 0xFF).toByte()  // high byte
        System.arraycopy(data, 0, payload, 3, data.size)

        enqueueWrite {
            @Suppress("DEPRECATION")
            txChar.value = payload
            txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(txChar)
        }
    }

    // ----------------------------------------------------------------------
    // Configura stream de sensores para UI/ML (IMU; pressão opcional)
    // ----------------------------------------------------------------------
    override fun setSensorsConfig(
        rateMs: Int,
        includeMagnetometer: Boolean,
        includePressure: Boolean,
        includeLinearAcceleration: Boolean,
    ) {
        val sensorTypes = buildList {
            add(SENSOR_TYPE_ACCELERATION)
            if (includeLinearAcceleration) add(SENSOR_TYPE_LINEAR_ACCELERATION)
            add(SENSOR_TYPE_GYROSCOPE)
            if (includeMagnetometer) add(SENSOR_TYPE_MAGNETOMETER)
            if (includePressure) add(SENSOR_TYPE_PRESSURE)
        }

        sensorTypes.forEach { sensorType ->
            val payload = byteArrayOf(
                sensorType.toByte(),
                (rateMs and 0xFF).toByte(),
                ((rateMs shr 8) and 0xFF).toByte()
            )
            sendTxMessage(MSG_SET_SENSOR_CONFIG, payload)
        }

        // Ajuda no parse/escala quando o firmware disponibiliza estes metadados.
        sendTxMessage(MSG_GET_SENSOR_SCALARS)
        sendTxMessage(MSG_GET_PRESSURE_POSITIONS)

        Log.d(
            TAG,
            "Pedido stream sensores: rate=${rateMs}ms linAcc=$includeLinearAcceleration mag=$includeMagnetometer pressure=$includePressure"
        )
    }

    override fun enableTfliteInferencing() {
        pendingTfliteEnable = true
        sendTxMessage(MSG_TFLITE_IS_READY)
        sendTxMessage(MSG_GET_TFLITE_NAME)
        sendTxMessage(MSG_GET_TFLITE_SAMPLE_RATE)
        sendTxMessage(MSG_GET_TFLITE_SENSOR_TYPES)
        setTfliteInferencingEnabled(true)
        sendTxMessage(MSG_GET_TFLITE_INFERENCING_ENABLED)
    }

    suspend fun activateTfliteInferencing(
        sampleRateHz: Int = 100,
        sensorTypes: ByteArray = byteArrayOf(3, 4),
        threshold: Float = 0f,
    ): Boolean {
        setTfliteSampleRate(sampleRateHz)
        setTfliteSensorTypes(sensorTypes)
        setTfliteThreshold(threshold)

        var ready = false
        repeat(8) {
            sendTxMessage(MSG_TFLITE_IS_READY)
            if (waitUntil(300) { tfliteIsReady }) {
                ready = true
                return@repeat
            }
            delay(120)
        }

        if (!ready) {
            WearableService.appendLog("❌ BS ativação: TFLite não ficou ready")
            return false
        }

        pendingTfliteEnable = true
        tfliteInferencingEnabled = false
        repeat(10) {
            setTfliteInferencingEnabled(true)
            sendTxMessage(MSG_GET_TFLITE_INFERENCING_ENABLED)
            if (waitUntil(300) { tfliteInferencingEnabled }) {
                pendingTfliteEnable = false
                WearableService.appendLog("✅ BS inferência ativa confirmada")
                return true
            }
            delay(120)
        }

        pendingTfliteEnable = false
        WearableService.appendLog("❌ BS ativação: inferência não ficou ativa")
        return false
    }

    override fun disableTfliteInferencing() {
        pendingTfliteEnable = false
        setTfliteInferencingEnabled(false)
    }

    override suspend fun uploadTfliteAsset(assetName: String): Boolean {
        sendTxMessage(MSG_GET_TFLITE_NAME)
        waitUntil(800) { currentTfliteName.isNotBlank() }
        if (currentTfliteName.equals(assetName, ignoreCase = true)) {
            WearableService.appendLog("ℹ️ TFLite já instalado na BS: $currentTfliteName (skip upload)")
            return true
        }

        val fileBytes = withContext(Dispatchers.IO) {
            context.assets.open(assetName).use { it.readBytes() }
        }
        WearableService.appendLog("📤 Upload TFLite para BS: $assetName (${fileBytes.size} bytes)")
        return uploadTfliteFile(assetName, fileBytes)
    }

    private suspend fun uploadTfliteFile(fileName: String, fileBytes: ByteArray): Boolean {
        if (_state.value != BleDeviceState.READY && _state.value != BleDeviceState.CONNECTED) {
            WearableService.appendLog("❌ Upload TFLite: pulseira não está pronta")
            return false
        }

        val checksum = crc32(fileBytes)
        currentFileTypeEnum = -1
        currentFileLengthBytes = -1
        currentFileChecksum = -1
        currentFileTransferStatus = FILE_TRANSFER_STATUS_IDLE
        fileBytesTransferredAck = 0L

        sendTxMessage(MSG_SET_FILE_TYPE, byteArrayOf(FILE_TYPE_TFLITE.toByte()))
        if (!waitUntil(2500) { currentFileTypeEnum == FILE_TYPE_TFLITE }) {
            WearableService.appendLog("❌ Upload TFLite: setFileType falhou")
            return false
        }

        sendTxMessage(MSG_SET_FILE_LENGTH, uint32ToLeBytes(fileBytes.size.toLong()))
        if (!waitUntil(2500) { currentFileLengthBytes == fileBytes.size.toLong() }) {
            WearableService.appendLog("❌ Upload TFLite: setFileLength falhou")
            return false
        }

        sendTxMessage(MSG_SET_FILE_CHECKSUM, uint32ToLeBytes(checksum))
        if (!waitUntil(2500) { currentFileChecksum == checksum }) {
            WearableService.appendLog("❌ Upload TFLite: setFileChecksum falhou")
            return false
        }

        sendTxMessage(MSG_SET_FILE_TRANSFER_COMMAND, byteArrayOf(FILE_TRANSFER_CMD_START_SEND.toByte()))
        if (!waitUntil(3000) { currentFileTransferStatus == FILE_TRANSFER_STATUS_SENDING }) {
            WearableService.appendLog("❌ Upload TFLite: startSend não entrou em sending")
            return false
        }

        val effectiveMtu = when {
            protocolMtu > 0 -> minOf(protocolMtu, negotiatedMtu)
            else -> minOf(negotiatedMtu, 128)
        }
        val chunkSize = (effectiveMtu - 6).coerceAtLeast(20)
        WearableService.appendLog("📤 BS upload chunk=$chunkSize (attMtu=$negotiatedMtu, protoMtu=$protocolMtu)")
        var sent = 0
        while (sent < fileBytes.size) {
            val end = minOf(sent + chunkSize, fileBytes.size)
            val chunk = fileBytes.copyOfRange(sent, end)
            sendTxMessage(MSG_SET_FILE_BLOCK, chunk)
            sent = end
            if (!waitUntil(6000) { fileBytesTransferredAck >= sent.toLong() }) {
                WearableService.appendLog("❌ Upload TFLite: timeout no bloco ($sent/${fileBytes.size})")
                return false
            }
        }

        waitUntil(3000) { currentFileTransferStatus == FILE_TRANSFER_STATUS_IDLE }

        setTfliteName(fileName)
        sendTxMessage(MSG_GET_TFLITE_NAME)
        sendTxMessage(MSG_TFLITE_IS_READY)

        WearableService.appendLog("✅ Upload TFLite concluído: $fileName")
        return true
    }

    override fun setTfliteSampleRate(sampleRateHz: Int) {
        val safeRate = sampleRateHz.coerceIn(1, 1000)
        val payload = byteArrayOf(
            (safeRate and 0xFF).toByte(),
            ((safeRate shr 8) and 0xFF).toByte()
        )
        sendTxMessage(MSG_SET_TFLITE_SAMPLE_RATE, payload)
    }

    override fun setTfliteCaptureDelay(captureDelayMs: Int) {
        val safeDelay = captureDelayMs.coerceIn(0, 60_000)
        val payload = byteArrayOf(
            (safeDelay and 0xFF).toByte(),
            ((safeDelay shr 8) and 0xFF).toByte()
        )
        sendTxMessage(MSG_SET_TFLITE_CAPTURE_DELAY, payload)
    }

    override fun setTfliteThreshold(threshold: Float) {
        val safe = threshold.coerceAtLeast(0f)
        val payload = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(safe)
            .array()
        sendTxMessage(MSG_SET_TFLITE_THRESHOLD, payload)
    }

    override fun setTfliteSensorTypes(sensorTypeEnums: ByteArray) {
        if (sensorTypeEnums.isEmpty()) return
        sendTxMessage(MSG_SET_TFLITE_SENSOR_TYPES, sensorTypeEnums)
    }

    fun setTfliteName(name: String) {
        val payload = name.toByteArray(Charsets.UTF_8)
        if (payload.isEmpty()) return
        sendTxMessage(MSG_SET_TFLITE_NAME, payload)
    }

    override fun setTfliteTask(taskIndex: Int) {
        val safeTask = taskIndex.coerceIn(TFLITE_TASK_CLASSIFICATION, TFLITE_TASK_REGRESSION)
        sendTxMessage(MSG_SET_TFLITE_TASK, byteArrayOf(safeTask.toByte()))
    }

    fun setTfliteInferencingEnabled(enabled: Boolean) {
        val payload = byteArrayOf(if (enabled) 1 else 0)
        sendTxMessage(MSG_SET_TFLITE_INFERENCING_ENABLED, payload)
    }

    /**
     * Corrigir o Botão de Vibração
     *
     * @param effectIndex índice no array VibrationWaveformEffects (1=strongClick100, 14=strongBuzz100, etc.)
     * @param locationBitmask onde vibrar: 0x01=front, 0x02=rear, 0x03=ambos
     */
    override fun sendHapticCommand(effectIndex: Int, locationBitmask: Int) {
        // A pulseira espera: [locationBitmask (1B)] [type (1B)] [segmentCount (1B)] [effectIndex (1B)]
        val vibData = byteArrayOf(
            locationBitmask.toByte(),
            VIB_TYPE_WAVEFORM_EFFECT.toByte(), // 0
            1.toByte(), // <- Byte em falta! Número de segmentos no array
            effectIndex.toByte()
        )
        sendTxMessage(MSG_TRIGGER_VIBRATION, vibData)
        Log.d(TAG, "Vibration sent (corrigida): effect=$effectIndex")
    }

    private fun sendSingleWaveformEffect(effectIndex: Int, locationBitmask: Int, segmentLoopCount: Int = 0) {
        val clampedLoopCount = segmentLoopCount.coerceIn(0, 3)
        val includeAllSegments = clampedLoopCount > 0
        val segmentCount = if (includeAllSegments) 8 else 1

        val effects = ByteArray(segmentCount) { EFFECT_NONE.toByte() }
        effects[0] = effectIndex.toByte()

        val loopByteCount = if (includeAllSegments) 2 else 1
        val loopCounts = ByteArray(loopByteCount) { 0 }
        loopCounts[0] = (clampedLoopCount and 0x03).toByte()

        val segmentDataLength = effects.size + loopCounts.size
        val vibData = ByteArray(3 + segmentDataLength)
        vibData[0] = locationBitmask.toByte()
        vibData[1] = VIB_TYPE_WAVEFORM_EFFECT.toByte()
        vibData[2] = segmentDataLength.toByte()
        System.arraycopy(effects, 0, vibData, 3, effects.size)
        System.arraycopy(loopCounts, 0, vibData, 3 + effects.size, loopCounts.size)

        sendTxMessage(MSG_TRIGGER_VIBRATION, vibData)
        Log.d(
            TAG,
            "WaveformEffect sdk-format: effect=$effectIndex loop=$clampedLoopCount loc=$locationBitmask segs=$segmentCount"
        )
    }

    override fun sendStopAlertVibration() {
        sendSingleWaveformEffect(
            effectIndex = EFFECT_PULSING_STRONG_100,
            locationBitmask = 0x03,
            segmentLoopCount = 3
        )
    }

    override fun sendGoAlertVibration() {
        sendSingleWaveformEffect(
            effectIndex = EFFECT_SMOOTH_HUM_50,
            locationBitmask = 0x03,
            segmentLoopCount = 0
        )
    }

    /**
     * Envia comando de vibração waveform (amplitude + duração personalizados).
     *
     * @param amplitude 0.0 a 1.0
     * @param durationMs duração em milissegundos (max 2550)
     * @param locationBitmask onde vibrar: 0x01=front, 0x02=rear, 0x03=ambos
     */
    override fun sendWaveformVibration(amplitude: Float, durationMs: Int, locationBitmask: Int) {
        val ampByte = (amplitude.coerceIn(0f, 1f) * 127).toInt()
        val durByte = (durationMs.coerceIn(10, 2550) / 10)

        // Formato: [locationsBitmask] [vibTypeIndex=1] [dataLength=2] [amplitude] [duration/10]
        val vibData = byteArrayOf(
            locationBitmask.toByte(),
            VIB_TYPE_WAVEFORM.toByte(),
            2, // 2 bytes de dados (amplitude + duration)
            ampByte.toByte(),
            durByte.toByte()
        )
        sendTxMessage(MSG_TRIGGER_VIBRATION, vibData)
        Log.d(TAG, "Waveform vibration: amp=$ampByte dur=${durationMs}ms")
    }

    /**
     * Compat wrapper — chamado pelo WearableService.
     * Envia vibração forte de 500ms.
     */
    @Suppress("UNUSED_PARAMETER")
    fun sendHapticCommand(intensity: Byte, duration: Short) {
        // Usar waveformEffect strongBuzz100 para vibração longa
        sendHapticCommand(EFFECT_STRONG_BUZZ_100, 0x03)
    }

    // ── Write queue ──

    private fun enqueueWrite(op: () -> Unit) {
        writeQueue.add(op)
        if (!isWritePending) processNextWrite()
    }

    private fun processNextWrite() {
        val next = writeQueue.poll() ?: return
        isWritePending = true
        gattWatchdog.arm("gattOp") {
            isWritePending = false
            processNextWrite()
        }
        next()
    }

    private fun readInt16LE(data: ByteArray, offset: Int): Short {
        if (offset + 1 >= data.size) return 0
        val lo = data[offset].toInt() and 0xFF
        val hi = data[offset + 1].toInt() and 0xFF
        return ((hi shl 8) or lo).toShort()
    }

    private fun parseTfliteInference(data: ByteArray) {
        if (data.size < 6) return
        val timestampRaw16 = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val timestampEstimatedMs = estimateTimestampFromLower16(timestampRaw16)

        val valuesCount = (data.size - 2) / 4
        if (valuesCount <= 0) return

        val values = FloatArray(valuesCount)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(2)
        for (index in 0 until valuesCount) {
            values[index] = buffer.float
        }

        var maxIndex = 0
        var maxValue = values[0]
        for (index in 1 until values.size) {
            if (values[index] > maxValue) {
                maxValue = values[index]
                maxIndex = index
            }
        }

        val classList = if (tfliteClasses.isNotEmpty()) tfliteClasses else defaultTfliteClasses.asList()
        val rawLabel = classList.getOrElse(maxIndex) { "class_$maxIndex" }
        val label = rawLabel.substringAfter('_', rawLabel).lowercase()
        onTfliteInference?.invoke(label, maxValue, timestampEstimatedMs, values)
    }

    private fun loadTfliteClassesFromAssets(): List<String> {
        return runCatching {
            context.assets.open("peci_labels.txt").bufferedReader().use { reader ->
                reader.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
        }.onSuccess { labels ->
            if (labels.isNotEmpty()) {
                WearableService.appendLog("🧠 Classes TFLite carregadas: ${labels.joinToString(", ")}")
            } else {
                WearableService.appendLog("⚠️ peci_labels.txt vazio; a usar classes default")
            }
        }.onFailure {
            WearableService.appendLog("⚠️ Sem peci_labels.txt; a usar classes default")
        }.getOrElse {
            defaultTfliteClasses.toList()
        }
    }

    private fun estimateTimestampFromLower16(lower16: Int): Long {
        val raw = lower16 and 0xFFFF
        val now = System.currentTimeMillis()
        val cycleMs = 65_536L

        if (ts16LastRaw < 0) {
            ts16BaseUnixMs = now - raw
            ts16EpochOffset = 0L
            ts16LastRaw = raw
            ts16LastEstimatedMs = ts16BaseUnixMs + raw
            return ts16LastEstimatedMs
        }

        val prevRaw = ts16LastRaw
        val backwardJump = prevRaw - raw
        val forwardJump = raw - prevRaw

        if (backwardJump > 32_768) {
            ts16EpochOffset += cycleMs
        } else if (forwardJump > 32_768) {
            ts16EpochOffset -= cycleMs
        }

        val unwrappedMs = ts16EpochOffset + raw
        var estimatedMs = ts16BaseUnixMs + unwrappedMs

        if (estimatedMs < ts16LastEstimatedMs) {
            val backwardMs = ts16LastEstimatedMs - estimatedMs
            if (backwardMs <= 500L) {
                estimatedMs = ts16LastEstimatedMs
            }
        }

        ts16BaseUnixMs = estimatedMs - unwrappedMs
        ts16LastRaw = raw
        ts16LastEstimatedMs = estimatedMs
        return estimatedMs
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            delay(40)
        }
        return condition()
    }

    private fun readUInt32LE(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return 0L
        return (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun uint32ToLeBytes(value: Long): ByteArray {
        val v = value and 0xFFFFFFFFL
        return byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte()
        )
    }

    private fun crc32(bytes: ByteArray): Long {
        var crc = 0xFFFFFFFF.toInt()
        for (byte in bytes) {
            var c = (crc xor (byte.toInt() and 0xFF)) and 0xFF
            repeat(8) {
                c = if ((c and 1) != 0) {
                    0xEDB88320.toInt() xor (c ushr 1)
                } else {
                    c ushr 1
                }
            }
            crc = (crc ushr 8) xor c
        }
        return crc.inv().toLong() and 0xFFFFFFFFL
    }
}
