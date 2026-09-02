package com.example.peciwearables.integration.ble

import com.example.peciwearables.integration.protocol.tlv.TlvParser
import com.example.peciwearables.integration.protocol.tlv.TlvWriter
import com.example.peciwearables.integration.protocol.tlv.TxRxMessageType
import com.example.peciwearables.integration.transfer.FileTransferHandler
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Processa bytes RX do BLE dos óculos: TlvParser → handleMessage → onTxNeeded/callbacks.
 * Classe pura (sem Android) para ser testável. Não é thread-safe: assume um único
 * caller de cada vez (GATT main looper em produção; dispatcher serial no UDP).
 */
class OmiRxHandler(
    /**
     * Chamada sempre que o handler precisa de enviar bytes TX ao device.
     * Em produção: `sendTxRaw(bytes)` no OmiGlassesBleClient.
     * Nos testes: `capturedTx.add(bytes)`.
     */
    val onTxNeeded: (ByteArray) -> Unit = {},

    /**
     * Disparada quando todas as 12 respostas obrigatórias foram recebidas.
     * Em produção: `_state.value = BleDeviceState.CONNECTED`.
     */
    val onConnected: () -> Unit = {},

    /**
     * Disparada quando o IP dos glasses chega via IP_ADDRESS.
     * Trigger para iniciar a transição BLE→UDP (Etapa 3).
     */
    val onGlassesIpReceived: (String) -> Unit = {},
) {

    private var microphoneDataLogCounter = 0
    private var cameraDataLogCounter = 0
    private val cameraDataPendingBuffer = SlidingByteQueue(initialCapacity = 4096)

    // ── Callbacks de dados (câmara / microfone) ───────────────────────────────

    /**
     * Disparada quando chega CAMERA_DATA via UDP/BLE.
     * @param subType  Sub-tipo: 0=headerSize 1=header 2=imageSize 3=image 4=footerSize 5=footer
     * @param data     Bytes do payload da sub-mensagem.
     */
    var onCameraData: ((subType: Int, data: ByteArray) -> Unit)? = null

    /**
     * Disparada quando chega CAMERA_STATUS.
     * @param status  Código de estado (0=idle, 1=streaming, …)
     */
    var onCameraStatus: ((status: Int) -> Unit)? = null

    /**
     * Disparada quando chega MICROPHONE_DATA.
     * @param data  Payload raw do microfone (formato depende da configuração).
     */
    var onMicrophoneData: ((data: ByteArray) -> Unit)? = null

    /**
     * Disparada quando chega MICROPHONE_STATUS.
     * @param status  Código de estado.
     */
    var onMicrophoneStatus: ((status: Int) -> Unit)? = null

    /**
     * Disparada quando chega GET/SET_MICROPHONE_CONFIGURATION.
     * @param sampleRateHz  8000 ou 16000
     * @param bitDepth      8 ou 16
     */
    var onMicrophoneConfiguration: ((sampleRateHz: Int, bitDepth: Int) -> Unit)? = null

    /**
     * Disparada quando chega GET_MTU.
     */
    var onMtu: ((mtu: Int) -> Unit)? = null

    /**
     * Disparada quando chega GET/SET_CAMERA_CONFIGURATION.
     */
    var onCameraConfiguration: ((ParsedCameraConfiguration) -> Unit)? = null

    /**
     * Disparada quando chega GET/SET_SENSOR_CONFIGURATION.
     */
    var onSensorConfiguration: ((ParsedSensorConfiguration) -> Unit)? = null

    /**
     * Disparada quando chega SENSOR_DATA para sensores IMU da API Brilliant Sole.
     */
    var onSdkSensorSample: ((SdkSensorSample) -> Unit)? = null

    /**
     * Disparada quando chega GET/SET_WIFI_CONNECTION_ENABLED.
     */
    var onWifiConnectionEnabledChanged: ((enabled: Boolean) -> Unit)? = null

    /**
     * Disparada quando chega IS_WIFI_CONNECTED.
     */
    var onWifiConnectedChanged: ((connected: Boolean) -> Unit)? = null

    /**
     * Disparada quando chega IS_WIFI_SECURE.
     */
    var onWifiSecureChanged: ((secure: Boolean) -> Unit)? = null

    /**
     * Disparada quando um ficheiro completo é recebido via File Transfer protocol.
     * @param fileType  índice do FileType (4 = cameraImage)
     * @param data      bytes do ficheiro completo
     */
    var onFileReceived: ((fileType: Int, data: ByteArray) -> Unit)? = null

    /**
     * Callback opcional para tracing de RX (diagnóstico em runtime).
     */
    var onDebug: ((String) -> Unit)? = null

    // ── File Transfer Handler ─────────────────────────────────────────────────

    /**
     * Gere o protocolo de transferência de ficheiros (espelha FileTransferManager.ts).
     * Acumula GET_FILE_BLOCK chunks e valida CRC32 no fim.
     */
    val fileTransferHandler = FileTransferHandler()

    companion object {
        private const val TAG = "OmiRxHandler"
        private const val CAMERA_CONFIGURATION_RESOLUTION = 0
        private const val CAMERA_CONFIGURATION_QUALITY_FACTOR = 1
        private const val SENSOR_TYPE_CAMERA = 15
        private const val SENSOR_TYPE_MICROPHONE = 16
        private const val SENSOR_TYPE_ACCELERATION = 1
        private const val SENSOR_TYPE_GYROSCOPE = 4
        private const val SENSOR_TYPE_MAGNETOMETER = 5
        private const val SENSOR_TYPE_GAME_ROTATION = 6
        private const val CAMERA_SUBTYPE_MIN = 0
        private const val CAMERA_SUBTYPE_MAX = 5
        private const val CAMERA_PAYLOAD_PREVIEW_BYTES = 12
        private const val CAMERA_SUBMSG_HEADER_SIZE = 3
        private const val CAMERA_SUBMSG_MAX_LENGTH_BYTES = 32 * 1024
        private const val CAMERA_SUBMSG_MAX_PENDING_BYTES = 256 * 1024

        private fun log(msg: String) = println("D/$TAG: $msg")
        private fun warn(msg: String) = println("W/$TAG: $msg")
        private fun err(msg: String)  = println("E/$TAG: $msg")

        /**
         * Tipos de resposta que o device deve enviar antes de [isConnected]=true.
         * Espelha RequiredInformationConnectionMessages do SDK TypeScript.
         */
        val REQUIRED_RESPONSE_TYPES: Set<TxRxMessageType> = linkedSetOf(
            TxRxMessageType.IS_CHARGING,
            TxRxMessageType.GET_BATTERY_CURRENT,
            TxRxMessageType.GET_ID,
            TxRxMessageType.GET_MTU,
            TxRxMessageType.GET_NAME,
            TxRxMessageType.GET_TYPE,
            TxRxMessageType.GET_CURRENT_TIME,
            TxRxMessageType.GET_SENSOR_CONFIGURATION,
            TxRxMessageType.GET_SENSOR_SCALARS,
            TxRxMessageType.GET_VIBRATION_LOCATIONS,
            TxRxMessageType.GET_FILE_TYPES,
            TxRxMessageType.IS_WIFI_AVAILABLE,
        )

        /**
         * Mensagens GET pedidas quando IS_WIFI_AVAILABLE=true.
         * SDK: wifiInformationMessages em BaseConnectionManager.
         */
        val WIFI_INFO_REQUEST_TYPES: List<TxRxMessageType> = listOf(
            TxRxMessageType.GET_WIFI_SSID,
            TxRxMessageType.GET_WIFI_PASSWORD,
            TxRxMessageType.GET_WIFI_CONNECTION_ENABLED,
            TxRxMessageType.IS_WIFI_CONNECTED,
            TxRxMessageType.IP_ADDRESS,
            TxRxMessageType.IS_WIFI_SECURE,
        )

        val CAMERA_INFO_REQUEST_TYPES: List<TxRxMessageType> = listOf(
            TxRxMessageType.GET_CAMERA_CONFIGURATION,
            TxRxMessageType.CAMERA_STATUS,
        )

        val MICROPHONE_INFO_REQUEST_TYPES: List<TxRxMessageType> = listOf(
            TxRxMessageType.GET_MICROPHONE_CONFIGURATION,
            TxRxMessageType.MICROPHONE_STATUS,
        )

        /**
         * Constrói os bytes TLV para o pedido de informação WiFi.
         * Exposto no companion para ser testável sem instância.
         */
        fun buildWifiInfoRequestBytes(): ByteArray =
            TlvWriter.encodeAll(WIFI_INFO_REQUEST_TYPES.map { TlvWriter.Message(it) })

        fun buildCameraInfoRequestBytes(): ByteArray =
            TlvWriter.encodeAll(CAMERA_INFO_REQUEST_TYPES.map { TlvWriter.Message(it) })

        fun buildMicrophoneInfoRequestBytes(): ByteArray =
            TlvWriter.encodeAll(MICROPHONE_INFO_REQUEST_TYPES.map { TlvWriter.Message(it) })

        /**
         * Parseia payload IP_ADDRESS.
         * - 4 bytes: IPv4 binário (a.b.c.d) — formato mais comum
         * - N bytes: string UTF-8
         * - outro: null (malformed)
         */
        fun parseIpAddress(payload: ByteArray): String? {
            if (payload.isEmpty()) return null
            if (payload.size == 4) {
                return payload.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            val str = String(payload, Charsets.UTF_8).trim()
            return if (str.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))) str else null
        }

        fun parseMicrophoneConfiguration(payload: ByteArray): Pair<Int, Int>? {
            val profile = GlassesMicrophoneProfile.parseFromConfigurationPayload(payload) ?: return null
            return profile.sampleRateHz to profile.bitDepth
        }

        fun parseMtu(payload: ByteArray): Int? {
            if (payload.size < 2) return null
            return (payload[0].toInt() and 0xFF) or
                ((payload[1].toInt() and 0xFF) shl 8)
        }

        fun parseCameraConfiguration(payload: ByteArray): ParsedCameraConfiguration? {
            if (payload.size < 3) return null

            var resolution: Int? = null
            var qualityFactor: Int? = null
            var offset = 0
            while (offset + 2 < payload.size) {
                val type = payload[offset].toInt() and 0xFF
                val value = (payload[offset + 1].toInt() and 0xFF) or
                    ((payload[offset + 2].toInt() and 0xFF) shl 8)
                when (type) {
                    CAMERA_CONFIGURATION_RESOLUTION -> resolution = value
                    CAMERA_CONFIGURATION_QUALITY_FACTOR -> qualityFactor = value
                }
                offset += 3
            }

            return if (resolution != null || qualityFactor != null) {
                ParsedCameraConfiguration(
                    resolution = resolution,
                    qualityFactor = qualityFactor,
                )
            } else {
                null
            }
        }

        fun parseSensorConfiguration(payload: ByteArray): ParsedSensorConfiguration? {
            if (payload.size < 3) return null

            var cameraRateMs: Int? = null
            var microphoneRateMs: Int? = null
            var offset = 0
            while (offset + 2 < payload.size) {
                val sensorType = payload[offset].toInt() and 0xFF
                val rateMs = (payload[offset + 1].toInt() and 0xFF) or
                    ((payload[offset + 2].toInt() and 0xFF) shl 8)
                when (sensorType) {
                    SENSOR_TYPE_CAMERA -> cameraRateMs = rateMs
                    SENSOR_TYPE_MICROPHONE -> microphoneRateMs = rateMs
                }
                offset += 3
            }

            return if (cameraRateMs != null || microphoneRateMs != null) {
                ParsedSensorConfiguration(
                    cameraRateMs = cameraRateMs,
                    microphoneRateMs = microphoneRateMs,
                )
            } else {
                null
            }
        }
    }

    // ── Inicialização ─────────────────────────────────────────────────────────

    init {
        // Quando o FileTransferHandler precisa de enviar ACK FILE_BYTES_TRANSFERRED
        fileTransferHandler.onAckNeeded = { bytesReceived ->
            val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytesReceived).array()
            val ackBytes = TlvWriter.encode(TxRxMessageType.FILE_BYTES_TRANSFERRED, payload)
            onTxNeeded(ackBytes)
        }

        // Quando um ficheiro completo é recebido → propagar callback público
        fileTransferHandler.onFileReceived = { fileType, data ->
            onFileReceived?.invoke(fileType, data)
        }
    }

    // ── Estado interno ────────────────────────────────────────────────────────

    /** Respostas ainda em falta. Vazio = handshake BLE completo. */
    private val pendingResponses: MutableSet<TxRxMessageType> =
        REQUIRED_RESPONSE_TYPES.toMutableSet()

    /** true depois de todas as respostas obrigatórias terem sido recebidas. */
    var isConnected: Boolean = false
        private set

    /** IP dos glasses recebido via IP_ADDRESS (null enquanto não chegar). */
    var glassesIp: String? = null
        private set

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Ponto de entrada: bytes raw do BLE RX.
     * Podem conter múltiplas mensagens TLV concatenadas num só pacote BLE.
     */
    fun onRxBytes(data: ByteArray) {
        try {
            TlvParser.parseTxRx(data, 0, data.size) { type, payload, _ ->
                handleMessage(type, payload)
            }
        } catch (e: Exception) {
            err("RX parse error: ${e.message}")
            onDebug?.invoke("RX parse error (${data.size}B): ${e.message}")
        }
    }

    /**
     * Respostas ainda em falta (para testes e debugging).
     */
    fun pendingResponseTypes(): Set<TxRxMessageType> = pendingResponses.toSet()

    /**
     * Força conclusão do handshake quando o firmware não devolve todas as respostas.
     */
    fun forceHandshakeComplete() {
        if (isConnected) return
        pendingResponses.clear()
        isConnected = true
        log("BLE handshake forçado (faltavam respostas) → isConnected = true")
        onConnected()
    }

    /**
     * Reinicia o estado para uma nova ligação.
     */
    fun reset() {
        pendingResponses.clear()
        pendingResponses.addAll(REQUIRED_RESPONSE_TYPES)
        isConnected = false
        glassesIp = null
        cameraDataPendingBuffer.reset()
        fileTransferHandler.reset()
    }

    fun resetCameraDataReassembly(reason: String) {
        val pendingSize = cameraDataPendingBuffer.size()
        cameraDataPendingBuffer.reset()
        if (pendingSize > 0) {
            onDebug?.invoke("CAMERA_DATA reassembly reset ($reason), pending=$pendingSize B")
        }
    }

    private fun emitCameraData(subType: Int, data: ByteArray) {
        cameraDataLogCounter++
        if (cameraDataLogCounter % 40 == 0) {
            log("CAMERA_DATA stream packets=$cameraDataLogCounter lastSubType=$subType lastSize=${data.size}B")
            onDebug?.invoke("CAMERA_DATA stream packets=$cameraDataLogCounter lastSubType=$subType lastSize=${data.size}B")
        } else if (cameraDataLogCounter <= 3) {
            onDebug?.invoke("CAMERA_DATA chunk subType=$subType size=${data.size}B")
        }
        onCameraData?.invoke(subType, data)
    }

    private fun isValidCameraSubType(subType: Int): Boolean =
        subType in CAMERA_SUBTYPE_MIN..CAMERA_SUBTYPE_MAX

    private fun cameraPayloadPreview(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        if (length <= 0 || offset < 0 || offset >= buffer.size) return ""
        val endExclusive = (offset + length).coerceAtMost(buffer.size)
        val previewEnd = (offset + CAMERA_PAYLOAD_PREVIEW_BYTES).coerceAtMost(endExclusive)
        return (offset until previewEnd).joinToString(" ") { index ->
            "0x%02X".format(buffer[index])
        }
    }

    private fun tryEmitLegacyCameraData(buffer: ByteArray, start: Int, endExclusive: Int): Boolean {
        if (start >= endExclusive || start < 0 || endExclusive > buffer.size) return false
        val subType = buffer[start].toInt() and 0xFF
        if (!isValidCameraSubType(subType)) return false
        val dataStart = start + 1
        val data = if (dataStart < endExclusive) buffer.copyOfRange(dataStart, endExclusive) else ByteArray(0)
        emitCameraData(subType, data)
        return true
    }

    private fun appendCameraDataPayload(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return true
        val combinedSize = cameraDataPendingBuffer.size() + payload.size
        if (combinedSize > CAMERA_SUBMSG_MAX_PENDING_BYTES) {
            warn(
                "CAMERA_DATA reassembly overflow pending=${cameraDataPendingBuffer.size()}B " +
                    "new=${payload.size}B max=${CAMERA_SUBMSG_MAX_PENDING_BYTES}B — resetting"
            )
            onDebug?.invoke(
                "CAMERA_DATA overflow pending=${cameraDataPendingBuffer.size()}B " +
                    "new=${payload.size}B max=${CAMERA_SUBMSG_MAX_PENDING_BYTES}B"
            )
            cameraDataPendingBuffer.reset()
            if (payload.size > CAMERA_SUBMSG_MAX_PENDING_BYTES) {
                warn("CAMERA_DATA payload too large (${payload.size}B) — dropped")
                onDebug?.invoke("CAMERA_DATA payload demasiado grande (${payload.size}B)")
                return false
            }
        }
        cameraDataPendingBuffer.append(payload)
        return true
    }

    private fun parseCameraDataPending(hadPendingBeforeAppend: Boolean) {
        val buffer = cameraDataPendingBuffer.rawBuffer()
        val start = cameraDataPendingBuffer.startIndex()
        val endExclusive = cameraDataPendingBuffer.endIndex()
        val bufferSize = endExclusive - start
        if (bufferSize == 0) return

        var cursor = start
        var parsedAny = false

        while (true) {
            val remaining = endExclusive - cursor
            if (remaining < CAMERA_SUBMSG_HEADER_SIZE) {
                break
            }

            val chunkOffset = cursor - start
            val subType = buffer[cursor].toInt() and 0xFF
            val length = (buffer[cursor + 1].toInt() and 0xFF) or
                ((buffer[cursor + 2].toInt() and 0xFF) shl 8)

            if (!isValidCameraSubType(subType)) {
                if (
                    !hadPendingBeforeAppend &&
                    !parsedAny &&
                    chunkOffset == 0 &&
                    tryEmitLegacyCameraData(buffer, cursor, endExclusive)
                ) {
                    cursor = endExclusive
                    parsedAny = true
                    break
                }
                warn(
                    "CAMERA_DATA invalid subType=$subType offset=$chunkOffset " +
                        "payloadSize=${bufferSize}B preview=${cameraPayloadPreview(buffer, cursor, remaining)}"
                )
                onDebug?.invoke(
                    "CAMERA_DATA invalid subType=$subType offset=$chunkOffset size=${bufferSize}B"
                )
                cursor += 1
                continue
            }

            if (length > CAMERA_SUBMSG_MAX_LENGTH_BYTES) {
                warn(
                    "CAMERA_DATA invalid length=$length subType=$subType offset=$chunkOffset " +
                        "payloadSize=${bufferSize}B preview=${cameraPayloadPreview(buffer, cursor, remaining)}"
                )
                onDebug?.invoke(
                    "CAMERA_DATA invalid length=$length subType=$subType offset=$chunkOffset size=${bufferSize}B"
                )
                cursor += 1
                continue
            }

            val totalSubMsgBytes = CAMERA_SUBMSG_HEADER_SIZE + length
            if (remaining < totalSubMsgBytes) {
                if (
                    !hadPendingBeforeAppend &&
                    !parsedAny &&
                    chunkOffset == 0 &&
                    tryEmitLegacyCameraData(buffer, cursor, endExclusive)
                ) {
                    cursor = endExclusive
                    parsedAny = true
                } else {
                    onDebug?.invoke(
                        "CAMERA_DATA parcial subType=$subType length=$length " +
                            "offset=$chunkOffset available=${remaining}B"
                    )
                }
                break
            }

            val dataStart = cursor + CAMERA_SUBMSG_HEADER_SIZE
            val dataEnd = dataStart + length
            val data = buffer.copyOfRange(dataStart, dataEnd)
            emitCameraData(subType, data)
            parsedAny = true
            cursor = dataEnd
        }

        val consumed = (cursor - start).coerceAtLeast(0)
        cameraDataPendingBuffer.discardPrefix(consumed)
        if (cameraDataPendingBuffer.size() > 0) {
            val trailingSize = cameraDataPendingBuffer.size()
            if (parsedAny) {
                onDebug?.invoke(
                    "CAMERA_DATA trailing bytes=$trailingSize payloadSize=${bufferSize}B"
                )
            }
        }
    }

    private class SlidingByteQueue(initialCapacity: Int) {
        private var buf: ByteArray = ByteArray(initialCapacity.coerceAtLeast(1))
        private var start: Int = 0
        private var endExclusive: Int = 0

        fun size(): Int = endExclusive - start

        fun reset() {
            start = 0
            endExclusive = 0
        }

        fun append(data: ByteArray) {
            if (data.isEmpty()) return
            ensureCapacity(size() + data.size)
            System.arraycopy(data, 0, buf, endExclusive, data.size)
            endExclusive += data.size
        }

        fun rawBuffer(): ByteArray = buf

        fun startIndex(): Int = start

        fun endIndex(): Int = endExclusive

        fun discardPrefix(bytes: Int) {
            if (bytes <= 0) return
            if (bytes >= size()) {
                reset()
                return
            }
            start += bytes
            compactIfNeeded()
        }

        private fun ensureCapacity(requiredSize: Int) {
            compactIfNeeded()
            if (requiredSize <= buf.size - start) {
                return
            }

            var newSize = buf.size
            while (newSize < requiredSize) {
                newSize = (newSize shl 1).coerceAtLeast(requiredSize)
            }
            val newBuf = ByteArray(newSize)
            val currentSize = size()
            if (currentSize > 0) {
                System.arraycopy(buf, start, newBuf, 0, currentSize)
            }
            buf = newBuf
            start = 0
            endExclusive = currentSize
        }

        private fun compactIfNeeded() {
            if (start == 0) return
            val currentSize = size()
            if (currentSize == 0) {
                reset()
                return
            }
            if (start < buf.size / 2) return
            System.arraycopy(buf, start, buf, 0, currentSize)
            start = 0
            endExclusive = currentSize
        }
    }

    // ── Lógica interna ────────────────────────────────────────────────────────

    private fun handleMessage(type: TxRxMessageType, payload: ByteArray) {
        val isHighRateData = type == TxRxMessageType.MICROPHONE_DATA || type == TxRxMessageType.CAMERA_DATA
        if (!isHighRateData) {
            log("RX ${type.name} (${payload.size}B)")
        }
        val wasConnected = isConnected

        // 1. Marcar resposta como recebida
        pendingResponses.remove(type)

        // 2. Handshake completo?
        if (!isConnected && pendingResponses.isEmpty()) {
            isConnected = true
            log("BLE handshake complete → isConnected = true")
            onConnected()
        }

        // 3. Reacções específicas por tipo
        when (type) {
            TxRxMessageType.IS_WIFI_AVAILABLE -> {
                val available = payload.isNotEmpty() && payload[0] != 0.toByte()
                log("IS_WIFI_AVAILABLE = $available")
                if (available) {
                    log("Requesting WiFi info (6 messages)")
                    onTxNeeded(buildWifiInfoRequestBytes())
                }
            }

            TxRxMessageType.IP_ADDRESS -> {
                val ip = parseIpAddress(payload)
                if (ip != null) {
                    glassesIp = ip
                    log("Glasses IP = $ip → triggering UDP transition")
                    onGlassesIpReceived(ip)
                } else {
                    warn("IP_ADDRESS payload malformed (${payload.size}B)")
                }
            }

            TxRxMessageType.GET_WIFI_CONNECTION_ENABLED,
            TxRxMessageType.SET_WIFI_CONNECTION_ENABLED -> {
                val enabled = payload.isNotEmpty() && payload[0] != 0.toByte()
                log("WIFI_CONNECTION_ENABLED = $enabled")
                onWifiConnectionEnabledChanged?.invoke(enabled)
            }

            TxRxMessageType.GET_MTU -> {
                val mtu = parseMtu(payload)
                if (mtu != null) {
                    log("MTU = $mtu")
                    onMtu?.invoke(mtu)
                } else {
                    warn("GET_MTU malformed (${payload.size}B)")
                }
            }

            TxRxMessageType.GET_SENSOR_CONFIGURATION,
            TxRxMessageType.SET_SENSOR_CONFIGURATION -> {
                val parsed = parseSensorConfiguration(payload)
                if (parsed != null) {
                    log(
                        "SENSOR_CONFIG camera=${parsed.cameraRateMs ?: "N/A"}ms " +
                            "microphone=${parsed.microphoneRateMs ?: "N/A"}ms"
                    )
                    onDebug?.invoke(
                        "SENSOR_CONFIG camera=${parsed.cameraRateMs ?: "N/A"}ms microphone=${parsed.microphoneRateMs ?: "N/A"}ms"
                    )
                    onSensorConfiguration?.invoke(parsed)
                    if (!wasConnected && parsed.cameraRateMs != null) {
                        log("Requesting camera info (2 messages)")
                        onDebug?.invoke("SENSOR_CONFIG -> pedir info camera (2 mensagens)")
                        onTxNeeded(buildCameraInfoRequestBytes())
                    }
                    if (!wasConnected && parsed.microphoneRateMs != null) {
                        log("Requesting microphone info (2 messages)")
                        onDebug?.invoke("SENSOR_CONFIG -> pedir info microfone (2 mensagens)")
                        onTxNeeded(buildMicrophoneInfoRequestBytes())
                    }
                } else {
                    warn("SENSOR_CONFIGURATION malformed (${payload.size}B)")
                    onDebug?.invoke("SENSOR_CONFIGURATION malformed (${payload.size}B)")
                }
            }

            TxRxMessageType.SENSOR_DATA -> {
                if (payload.size >= 4) {
                    val timestampRaw16 = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
                    var offset = 2
                    while (offset + 2 <= payload.size) {
                        val sensorType = payload[offset].toInt() and 0xFF
                        val sensorDataLength = payload[offset + 1].toInt() and 0xFF
                        offset += 2
                        if (offset + sensorDataLength > payload.size) break

                        if (sensorDataLength >= 6) {
                            val x = ((payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)).toShort()
                            val y = ((payload[offset + 2].toInt() and 0xFF) or ((payload[offset + 3].toInt() and 0xFF) shl 8)).toShort()
                            val z = ((payload[offset + 4].toInt() and 0xFF) or ((payload[offset + 5].toInt() and 0xFF) shl 8)).toShort()
                            val w: Short = if (sensorDataLength >= 8) {
                                ((payload[offset + 6].toInt() and 0xFF) or ((payload[offset + 7].toInt() and 0xFF) shl 8)).toShort()
                            } else 0

                            val mappedType = when (sensorType) {
                                SENSOR_TYPE_ACCELERATION -> SdkSensorType.ACCELEROMETER
                                SENSOR_TYPE_GYROSCOPE -> SdkSensorType.GYROSCOPE
                                SENSOR_TYPE_MAGNETOMETER -> SdkSensorType.MAGNETOMETER
                                SENSOR_TYPE_GAME_ROTATION -> SdkSensorType.GAME_ROTATION
                                else -> null
                            }
                            if (mappedType != null) {
                                onSdkSensorSample?.invoke(
                                    SdkSensorSample(
                                        sensorType = mappedType,
                                        x = x,
                                        y = y,
                                        z = z,
                                        w = w,
                                        timestampRaw16 = timestampRaw16,
                                    )
                                )
                            }
                        }
                        offset += sensorDataLength
                    }
                }
            }

            TxRxMessageType.IS_WIFI_CONNECTED -> {
                val connected = payload.isNotEmpty() && payload[0] != 0.toByte()
                log("IS_WIFI_CONNECTED = $connected")
                onWifiConnectedChanged?.invoke(connected)
            }

            TxRxMessageType.IS_WIFI_SECURE -> {
                val secure = payload.isNotEmpty() && payload[0] != 0.toByte()
                log("IS_WIFI_SECURE = $secure")
                onWifiSecureChanged?.invoke(secure)
            }

            TxRxMessageType.CAMERA_DATA -> {
                if (payload.isNotEmpty()) {
                    val hadPendingBeforeAppend = cameraDataPendingBuffer.size() > 0
                    if (appendCameraDataPayload(payload)) {
                        parseCameraDataPending(hadPendingBeforeAppend)
                    }
                } else {
                    warn("CAMERA_DATA empty payload")
                    onDebug?.invoke("CAMERA_DATA empty payload")
                }
            }

            TxRxMessageType.CAMERA_STATUS -> {
                if (payload.isNotEmpty()) {
                    val status = payload[0].toInt() and 0xFF
                    log("CAMERA_STATUS = $status")
                    onDebug?.invoke("CAMERA_STATUS=$status")
                    onCameraStatus?.invoke(status)
                } else {
                    warn("CAMERA_STATUS empty payload")
                }
            }

            TxRxMessageType.GET_CAMERA_CONFIGURATION,
            TxRxMessageType.SET_CAMERA_CONFIGURATION -> {
                val parsed = parseCameraConfiguration(payload)
                if (parsed != null) {
                    log(
                        "CAMERA_CONFIG resolution=${parsed.resolution ?: "N/A"} " +
                            "qualityFactor=${parsed.qualityFactor ?: "N/A"}"
                    )
                    onCameraConfiguration?.invoke(parsed)
                } else {
                    warn("CAMERA_CONFIGURATION malformed (${payload.size}B)")
                }
            }

            TxRxMessageType.MICROPHONE_DATA -> {
                if (payload.isNotEmpty()) {
                    microphoneDataLogCounter++
                    if (microphoneDataLogCounter % 60 == 0) {
                        log("MICROPHONE_DATA stream packets=$microphoneDataLogCounter lastSize=${payload.size}B")
                    }
                    onMicrophoneData?.invoke(payload)
                } else {
                    warn("MICROPHONE_DATA empty payload")
                }
            }

            TxRxMessageType.MICROPHONE_STATUS -> {
                if (payload.isNotEmpty()) {
                    val status = payload[0].toInt() and 0xFF
                    log("MICROPHONE_STATUS = $status")
                    onMicrophoneStatus?.invoke(status)
                } else {
                    warn("MICROPHONE_STATUS empty payload")
                }
            }

            TxRxMessageType.GET_MICROPHONE_CONFIGURATION,
            TxRxMessageType.SET_MICROPHONE_CONFIGURATION -> {
                val parsed = parseMicrophoneConfiguration(payload)
                if (parsed != null) {
                    val (sampleRateHz, bitDepth) = parsed
                    log("MICROPHONE_CONFIG ${sampleRateHz}Hz/${bitDepth}-bit")
                    onMicrophoneConfiguration?.invoke(sampleRateHz, bitDepth)
                } else {
                    warn("MICROPHONE_CONFIGURATION malformed (${payload.size}B)")
                }
            }

            // ── File Transfer protocol ─────────────────────────────────────────
            // A imagem da câmara NÃO chega via CAMERA_DATA direto.
            // O device envia-a como ficheiro (fileType=4/cameraImage) via este protocolo.
            // Espelha FileTransferManager.ts do SDK TypeScript.

            TxRxMessageType.GET_FILE_TYPE,
            TxRxMessageType.SET_FILE_TYPE -> {
                onDebug?.invoke("${type.name} (${payload.size}B)")
                fileTransferHandler.onFileType(payload)
            }

            TxRxMessageType.GET_FILE_LENGTH,
            TxRxMessageType.SET_FILE_LENGTH -> {
                onDebug?.invoke("${type.name} (${payload.size}B)")
                fileTransferHandler.onFileLength(payload)
            }

            TxRxMessageType.GET_FILE_CHECKSUM,
            TxRxMessageType.SET_FILE_CHECKSUM -> {
                onDebug?.invoke("${type.name} (${payload.size}B)")
                fileTransferHandler.onFileChecksum(payload)
            }

            TxRxMessageType.GET_FILE_BLOCK -> {
                // Cada notificação BLE contém um chunk do ficheiro.
                // FileTransferHandler acumula, envia ACK e deteta conclusão.
                onDebug?.invoke("GET_FILE_BLOCK (${payload.size}B)")
                fileTransferHandler.onFileBlock(payload)
            }

            TxRxMessageType.FILE_TRANSFER_STATUS -> {
                onDebug?.invoke("FILE_TRANSFER_STATUS (${payload.size}B)")
                fileTransferHandler.onFileTransferStatus(payload)
            }

            else -> { /* outros tipos: sensores, display, tflite, etc. */ }
        }
    }
}
