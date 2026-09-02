package com.example.peciwearables.integration.watch

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.peciwearables.integration.protocol.ImuSample
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder


class WatchClient(private val context: Context) {

    companion object {
        private const val TAG = "WatchClient"
    }

    
    enum class State { DISCONNECTED, REMOTE, AVAILABLE, STREAMING, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val channelClient: ChannelClient = Wearable.getChannelClient(context)

    // ---- Estado observável ----
    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _watchNodeId = MutableStateFlow<String?>(null)
    val watchNodeId: StateFlow<String?> = _watchNodeId

    private val _watchNodeName = MutableStateFlow<String?>(null)
    val watchNodeName: StateFlow<String?> = _watchNodeName

    private val _battery = MutableStateFlow(-1)
    val battery: StateFlow<Int> = _battery

    private val _sampleRateHz = MutableStateFlow(100)
    val sampleRateHz: StateFlow<Int> = _sampleRateHz

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _samplesPerSec = MutableStateFlow(0)
    val samplesPerSec: StateFlow<Int> = _samplesPerSec

    /** Batimentos cardíacos em tempo real, em BPM. -1 quando indisponível. */
    private val _heartRateBpm = MutableStateFlow(-1)
    val heartRateBpm: StateFlow<Int> = _heartRateBpm

    /** Stream contínuo de amostras IMU. Mesmo formato que `BleClientBrilliantSole`. */
    private val _imuStream = MutableSharedFlow<ImuSample>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    val imuStream: SharedFlow<ImuSample> = _imuStream

    /** Callback opcional para timestamps brutos (úteis para o TimeSyncManager). */
    var onSampleTimestampUs: ((deviceUs: Long) -> Unit)? = null

    // ---- Latência de alertas Watch ----
    private val alertCounter = java.util.concurrent.atomic.AtomicInteger(0)
    /** Guarda (sendMs, alertType) por alertId para calcular RTT quando o ACK chegar. */
    private val pendingAlerts = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Int>>()

    /** Setter opcional — injectado pelo WearableService após instanciar. */
    var csvWriter: com.example.peciwearables.integration.latency.LatencyCsvWriter? = null

    private var capabilityListener: CapabilityClient.OnCapabilityChangedListener? = null
    private var messageListener: MessageClient.OnMessageReceivedListener? = null
    private var channelCallback: ChannelClient.ChannelCallback? = null
    private var ingestJob: Job? = null
    private var rateJob: Job? = null
    private var pollJob: Job? = null

    fun start() {
        if (capabilityListener != null) return
        Log.i(TAG, "start()")

        capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
            handleCapability(info)
        }
        capabilityClient.addListener(capabilityListener!!, WatchProtocol.CAPABILITY_WATCH)

        // Poll periódico (3 s) — apanha pareamentos que aconteçam DEPOIS da
        // app abrir, e contorna casos em que o capability listener não
        // dispara (transições de Bluetooth host).
        pollJob = scope.launch {
            while (true) {
                try {
                    val info = capabilityClient
                        .getCapability(WatchProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
                        .awaitOrNull()
                    if (info != null) handleCapability(info)
                } catch (e: Exception) {
                    Log.w(TAG, "getCapability falhou: ${e.message}")
                }
                kotlinx.coroutines.delay(3_000)
            }
        }

        messageListener = MessageClient.OnMessageReceivedListener { event ->
            handleMessage(event)
        }
        messageClient.addListener(messageListener!!)

        channelCallback = object : ChannelClient.ChannelCallback() {
            override fun onChannelOpened(channel: ChannelClient.Channel) {
                if (channel.path == WatchProtocol.CHANNEL_IMU) {
                    Log.i(TAG, "canal IMU aberto pelo watch (${channel.nodeId})")
                    startIngest(channel)
                }
            }
            override fun onChannelClosed(channel: ChannelClient.Channel, closeReason: Int, appErrorCode: Int) {
                if (channel.path == WatchProtocol.CHANNEL_IMU) {
                    Log.i(TAG, "canal IMU fechado (reason=$closeReason)")
                    stopIngest()
                }
            }
        }
        channelClient.registerChannelCallback(channelCallback!!)
    }

    fun stop() {
        Log.i(TAG, "stop()")
        capabilityListener?.let { capabilityClient.removeListener(it, WatchProtocol.CAPABILITY_WATCH) }
        capabilityListener = null
        messageListener?.let { messageClient.removeListener(it) }
        messageListener = null
        channelCallback?.let { channelClient.unregisterChannelCallback(it) }
        channelCallback = null
        pollJob?.cancel(); pollJob = null
        stopIngest()
        scope.cancel()
        _state.value = State.DISCONNECTED
        _watchNodeId.value = null
        _watchNodeName.value = null
    }

    /** Telemóvel pede ao watch para começar a streamar IMU. */
    fun requestStartImu() {
        sendMessage(WatchProtocol.PATH_START_IMU, ByteArray(0))
    }

    fun requestStopImu() {
        sendMessage(WatchProtocol.PATH_STOP_IMU, ByteArray(0))
    }

    /** Envia uma nova taxa de amostragem (Hz) ao watch. */
    fun requestSetSampleRate(hz: Int) {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(hz.coerceIn(20, 200).toShort())
            .array()
        sendMessage(WatchProtocol.PATH_SET_SAMPLE_RATE, payload)
    }

    /** Pede ao watch para tocar um beep no speaker (ou auscultadores BT do watch). */
    fun requestBeep(freqHz: Int = 880, durationMs: Int = 250, volumePct: Int = 80) {
        val payload = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(freqHz.coerceIn(40, 8000).toShort())
            .putShort(durationMs.coerceIn(10, 5000).toShort())
            .put(volumePct.coerceIn(0, 100).toByte())
            .array()
        sendMessage(WatchProtocol.PATH_BEEP, payload)
    }

    /** Pede ao watch para vibrar com um pattern (0..5, ver WatchProtocol.VibratePattern). */
    fun requestVibrate(patternId: Int, amplitude: Int = 0) {
        val payload = byteArrayOf(
            patternId.coerceIn(0, 5).toByte(),
            amplitude.coerceIn(0, 255).toByte(),
        )
        sendMessage(WatchProtocol.PATH_VIBRATE, payload)
    }

    /** Mostra notificação especial no relógio.
     *  type: 0 = AVISO, 1 = PERIGO, 2 = SAFE.
     *  O payload inclui agora um ID de 4 bytes (LE) para medir RTT do alerta. */
    fun requestNotify(type: Int, title: String, body: String) {
        val alertId = alertCounter.incrementAndGet()
        pendingAlerts[alertId] = Pair(System.currentTimeMillis(), type.coerceIn(0, 2))
        val titleBytes = title.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        // Formato: [alertId:4B LE][type:1B][titleLen:1B][title][body]
        val out = ByteBuffer.allocate(4 + 2 + titleBytes.size + bodyBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(alertId)
        out.put(type.coerceIn(0, 2).toByte())
        out.put(titleBytes.size.toByte())
        out.put(titleBytes)
        out.put(bodyBytes)
        sendMessage(WatchProtocol.PATH_NOTIFY, out.array())
    }

    /** Envia ao watch um snapshot do estado da app do telemóvel para a UI
     *  do relógio se redesenhar. Chamado periodicamente pelo WearableService. */
    fun sendPhoneState(
        glassesStateOrdinal: Int,
        wristbandStateOrdinal: Int,
        phoneSensorsActive: Boolean,
        audioTestActive: Boolean,
        deviceCount: Int,
    ) {
        val payload = byteArrayOf(
            glassesStateOrdinal.coerceIn(0, 6).toByte(),
            wristbandStateOrdinal.coerceIn(0, 6).toByte(),
            (if (phoneSensorsActive) 1 else 0).toByte(),
            (if (audioTestActive) 1 else 0).toByte(),
            deviceCount.coerceIn(0, 255).toByte(),
        )
        sendMessage(WatchProtocol.PATH_PHONE_STATE, payload)
    }

    /** Stream de acções pedidas pelo watch (botões pressionados no relógio). */
    val watchActions = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 16)

    // -------- internas --------

    private fun sendMessage(path: String, data: ByteArray) {
        val node = _watchNodeId.value
        if (node == null) {
            Log.w(TAG, "sendMessage($path) — sem watch ligado")
            return
        }
        scope.launch {
            try {
                messageClient.sendMessage(node, path, data).awaitOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "sendMessage($path) falhou: ${e.message}")
            }
        }
    }

    private fun handleCapability(info: CapabilityInfo) {
        // Preferimos sempre um nó nearby (Bluetooth) — só esse permite
        // MessageClient/ChannelClient. Quando só há nó remoto (cloud,
        // mesma conta Google mas sem proximidade) marcamos REMOTE para o
        // utilizador perceber que o watch está fora de alcance.
        val nearbyNode = info.nodes.firstOrNull { it.isNearby }
        val anyNode = info.nodes.firstOrNull()
        val node = nearbyNode ?: anyNode

        if (node != null) {
            _watchNodeId.value = node.id
            _watchNodeName.value = node.displayName
            val isNearby = node.isNearby
            // Não sobrepor STREAMING (canal já aberto).
            if (_state.value != State.STREAMING) {
                _state.value = if (isNearby) State.AVAILABLE else State.REMOTE
            }
            Log.i(TAG, "watch detectado: ${node.displayName} (${node.id}) nearby=$isNearby")
        } else {
            _watchNodeId.value = null
            _watchNodeName.value = null
            if (_state.value != State.DISCONNECTED) _state.value = State.DISCONNECTED
            Log.i(TAG, "nenhum watch alcançável")
        }
    }

    private fun handleMessage(event: MessageEvent) {
        when (event.path) {
            WatchProtocol.PATH_HEARTBEAT -> {
                if (event.data.size >= 8) {
                    val bb = ByteBuffer.wrap(event.data).order(ByteOrder.LITTLE_ENDIAN)
                    _sampleRateHz.value = bb.short.toInt() and 0xFFFF
                    _battery.value = bb.short.toInt() and 0xFFFF
                    // Próximos 4B = contagem total — guardamos para diagnóstico.
                    /* val count = */ bb.int
                }
            }
            WatchProtocol.PATH_IMU_STATE -> {
                val running = event.data.firstOrNull()?.toInt()?.and(0xFF) == 1
                _state.value = if (running) State.STREAMING else State.AVAILABLE
            }
            WatchProtocol.PATH_ERROR -> {
                _lastError.value = String(event.data, Charsets.UTF_8)
                _state.value = State.ERROR
            }
            WatchProtocol.PATH_HEART_RATE -> {
                if (event.data.size >= 2) {
                    val bpm = ByteBuffer.wrap(event.data)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short.toInt() and 0xFFFF
                    if (bpm in 30..220) _heartRateBpm.value = bpm
                }
            }
            WatchProtocol.PATH_WATCH_ACTION -> {
                event.data.firstOrNull()?.toInt()?.and(0xFF)?.let { action ->
                    watchActions.tryEmit(action)
                }
            }
            WatchProtocol.PATH_NOTIFY_ACK -> {
                if (event.data.size >= 4) {
                    val alertId = ByteBuffer.wrap(event.data).order(ByteOrder.LITTLE_ENDIAN).int
                    val pending = pendingAlerts.remove(alertId)
                    if (pending != null) {
                        val (sentMs, alertType) = pending
                        val rttMs = System.currentTimeMillis() - sentMs
                        val hubTs = System.currentTimeMillis()
                        Log.d(TAG, "BENCH|WATCH_ALERT|ACK|id=ALERT-$alertId|type=$alertType|rtt_ms=$rttMs")
                        csvWriter?.append(
                            filename = "watch_alert_latency.csv",
                            header   = "trace_id,alert_type,rtt_ms,hub_timestamp",
                            row      = "ALERT-$alertId,$alertType,$rttMs,$hubTs"
                        )
                    }
                }
            }
        }
    }

    private fun startIngest(channel: ChannelClient.Channel) {
        ingestJob?.cancel()
        rateJob?.cancel()
        ingestJob = scope.launch {
            val input = try {
                channelClient.getInputStream(channel).awaitOrNull()
            } catch (_: Exception) { null } ?: return@launch
            input.use { stream -> readLoop(stream) }
        }
        rateJob = scope.launch {
            var lastCount = 0L
            var totalCount = 0L
            while (true) {
                kotlinx.coroutines.delay(1000)
                val now = totalCount
                val delta = (now - lastCount).coerceAtLeast(0L)
                _samplesPerSec.value = delta.toInt()
                lastCount = now
            }
        }
    }

    private fun stopIngest() {
        ingestJob?.cancel()
        ingestJob = null
        rateJob?.cancel()
        rateJob = null
        _samplesPerSec.value = 0
        if (_state.value == State.STREAMING) _state.value = State.AVAILABLE
    }

    private suspend fun readLoop(input: InputStream) {
        val headerBuf = ByteArray(WatchProtocol.IMU_HEADER_SIZE)
        if (!readFully(input, headerBuf)) return
        val header = WatchProtocol.parseHeader(ByteBuffer.wrap(headerBuf)) ?: run {
            Log.w(TAG, "cabeçalho IMU inválido")
            return
        }
        _sampleRateHz.value = header.sampleRateHz
        Log.i(TAG, "stream IMU iniciado (mask=${header.sensorMask}, ${header.sampleRateHz}Hz)")
        _state.value = State.STREAMING

        val sampleBuf = ByteArray(WatchProtocol.IMU_SAMPLE_SIZE)
        while (true) {
            if (!readFully(input, sampleBuf)) break
            val s = WatchProtocol.parseSample(ByteBuffer.wrap(sampleBuf)) ?: continue
            onSampleTimestampUs?.invoke(s.timestampUs)
            // Emite no formato ImuSample do app (mg/mdps/µT×10, sem pressão).
            val sample = ImuSample(
                ax = s.axMg, ay = s.ayMg, az = s.azMg,
                gx = s.gxMdps, gy = s.gyMdps, gz = s.gzMdps,
                mx = s.mxUtX10, my = s.myUtX10, mz = s.mzUtX10,
                pressure = ShortArray(8),
            )
            _imuStream.tryEmit(sample)
        }
        Log.i(TAG, "stream IMU terminou")
    }

    private fun readFully(input: InputStream, dst: ByteArray): Boolean {
        var off = 0
        while (off < dst.size) {
            val r = try { input.read(dst, off, dst.size - off) } catch (_: Exception) { -1 }
            if (r <= 0) return false
            off += r
        }
        return true
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitOrNull(timeoutMs: Long = 5_000): T? {
    return withTimeoutOrNull(timeoutMs) {
        try { Tasks.await(this@awaitOrNull) } catch (_: Exception) { null }
    }
}
