package com.example.peciwearables.integration.transfer

import com.example.peciwearables.integration.image.camera.CameraMetrics
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32


class FileTransferHandler(
    initialMetrics: CameraMetrics? = null,
    
    var verboseLogs: Boolean = true,
) {

    
    var metrics: CameraMetrics? = initialMetrics

    companion object {
        const val FILE_TYPE_TFLITE           = 0
        const val FILE_TYPE_WIFI_SERVER_CERT = 1
        const val FILE_TYPE_WIFI_SERVER_KEY  = 2
        const val FILE_TYPE_SPRITE_SHEET     = 3
        const val FILE_TYPE_CAMERA_IMAGE     = 4

        /** fileTransferStatus values (SDK FileTransferManager.ts) */
        const val STATUS_IDLE      = 0
        const val STATUS_SENDING   = 1
        const val STATUS_RECEIVING = 2

        private const val TAG = "FileTransferHandler"
    }

    private fun log(msg: String) {
        println("D/$TAG: $msg")
        onTrace?.invoke("D", msg)
    }

    private fun warn(msg: String) {
        println("W/$TAG: $msg")
        onTrace?.invoke("W", msg)
    }

    private fun err(msg: String) {
        println("E/$TAG: $msg")
        onTrace?.invoke("E", msg)
    }

    // ── Callbacks públicos ────────────────────────────────────────────────────

    /**
     * Invocado quando um ficheiro completo é recebido e o CRC32 é válido.
     * @param fileType  índice do tipo (ver constantes FILE_TYPE_*)
     * @param data      bytes do ficheiro completo
     */
    var onFileReceived: ((fileType: Int, data: ByteArray) -> Unit)? = null

    /**
     * Invocado após cada bloco recebido com o total de bytes até agora.
     * Em produção: o chamador encoda FILE_BYTES_TRANSFERRED TLV e envia via BLE/UDP.
     * @param bytesReceived  total de bytes recebidos (para ACK ao device)
     */
    var onAckNeeded: ((bytesReceived: Int) -> Unit)? = null

    /**
     * Callback opcional para observabilidade (debug/telemetria em runtime).
     * Não altera a lógica de transferência.
     */
    var onTrace: ((level: String, message: String) -> Unit)? = null

    /**
     * Invocado quando uma transferência é descartada (CRC errado, overshoot grande,
     * timeout, reset por nova transição de status). Permite ao controller incrementar
     * métricas (`crcFailures`, `transportTimeouts`, etc.) sem inspecionar logs.
     */
    var onTransferAborted: ((reason: AbortReason, fileType: Int, partialBytes: Int) -> Unit)? = null

    enum class AbortReason {
        CRC_MISMATCH,
        OVERSHOOT_TRUNCATED,    // truncámos mas continuámos (não fatal)
        STATUS_TRANSITION,      // novo status interrompeu transferência em curso
        TIMEOUT,                // sem blocos há > limit ms
        TYPE_RESET,             // novo FILE_TYPE chegou no meio
    }

    // ── Estado interno ────────────────────────────────────────────────────────

    /** Tipo do ficheiro em transferência (-1 = desconhecido). */
    var fileType: Int = -1
        private set

    /** Tamanho total esperado em bytes (0 = ainda não recebido). */
    var expectedLength: Int = 0
        private set

    /** CRC32 esperado (0 = ainda não recebido). */
    var expectedChecksum: Long = 0L
        private set

    private val buffer = ByteArrayOutputStream()

    /** Quantos bytes foram recebidos até agora. */
    val bytesReceived: Int get() = buffer.size()

    /** True se há uma transferência em curso (expectedLength > 0). */
    val isTransferring: Boolean get() = expectedLength > 0

    /** Último status recebido (para detetar transições). null = ainda nenhum. */
    private var lastStatus: Int? = null

    /** Timestamp do último bloco recebido, para timeout. -1 = sem transferência ativa. */
    private var lastBlockTimestampMs: Long = -1L

    /** Bytes descartados por overshoot acumulados. Útil para métricas/debug. */
    var overshootBytesTotal: Long = 0L
        private set

    /** CRC mismatches acumulados. Útil para métricas. */
    var crcFailuresTotal: Long = 0L
        private set

    /** Transferências abortadas por timeout acumuladas. */
    var timeoutsTotal: Long = 0L
        private set

    // ── API pública ───────────────────────────────────────────────────────────

    
    fun onFileType(payload: ByteArray) {
        if (payload.isEmpty()) { warn("GET_FILE_TYPE: payload vazio"); return }
        val newType = payload[0].toInt() and 0xFF
        if (isTransferring && bytesReceived > 0 && newType != fileType) {
            val abandoned = bytesReceived
            val previousType = fileType
            warn("FILE_TYPE mudou no meio de uma transferência: $previousType → $newType, descartando ${abandoned}B")
            onTransferAborted?.invoke(AbortReason.TYPE_RESET, previousType, abandoned)
            resetInternal()
        }
        fileType = newType
        log("FILE_TYPE = $fileType")
    }

    /**
     * Processa payload de GET_FILE_LENGTH.
     * 4 bytes uint32 little-endian.
     */
    fun onFileLength(payload: ByteArray) {
        if (payload.size < 4) { warn("GET_FILE_LENGTH: payload curto (${payload.size}B)"); return }
        expectedLength = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int
        log("FILE_LENGTH = $expectedLength bytes")
    }

    /**
     * Processa payload de GET_FILE_CHECKSUM.
     * 4 bytes uint32 little-endian (CRC32).
     */
    fun onFileChecksum(payload: ByteArray) {
        if (payload.size < 4) { warn("GET_FILE_CHECKSUM: payload curto (${payload.size}B)"); return }
        // Lemos como int com sinal e mascaramos para Long sem sinal (CRC32 está em [0, 2^32-1])
        expectedChecksum = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        log("FILE_CHECKSUM = $expectedChecksum (0x${expectedChecksum.toString(16).uppercase()})")
    }

    
    fun onFileBlock(payload: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        if (payload.isEmpty()) { warn("GET_FILE_BLOCK: payload vazio"); return }

        if (expectedLength <= 0) {
            warn("GET_FILE_BLOCK recebido sem FILE_LENGTH conhecido — descartando ${payload.size}B")
            return
        }

        val remaining = expectedLength - buffer.size()
        if (remaining <= 0) {
            // Já tínhamos chegado a 100% mas não completámos (ex.: aguardando STATUS).
            // Descartar silenciosamente; não há para onde escrever.
            overshootBytesTotal += payload.size
            return
        }

        val bytesToWrite = minOf(payload.size, remaining)
        if (bytesToWrite < payload.size) {
            val overshoot = payload.size - bytesToWrite
            overshootBytesTotal += overshoot
            metrics?.recordOvershootTruncation(overshoot)
            warn("FILE_BLOCK overshoot: bloco=${payload.size}B, espaço=${remaining}B → truncar ${overshoot}B")
            onTransferAborted?.invoke(AbortReason.OVERSHOOT_TRUNCATED, fileType, overshoot)
            buffer.write(payload, 0, bytesToWrite)
        } else {
            buffer.write(payload)
        }

        lastBlockTimestampMs = nowMs

        val received = buffer.size()
        val pct = received * 100 / expectedLength
        // Por chunk: silencioso em produção (CameraMetrics agrega), mas mantém um log
        // no último bloco (transferência completa) para visibilidade.
        if (verboseLogs || received >= expectedLength) {
            log("FILE_BLOCK: +${bytesToWrite}B → $received/${expectedLength}B ($pct%)")
        }

        // ACK: informa o device quantos bytes recebemos até agora
        onAckNeeded?.invoke(received)

        // Verificar conclusão (com truncate, received nunca passa expectedLength)
        if (received >= expectedLength) {
            completeTransfer()
        }
    }

   
    fun onFileTransferStatus(payload: ByteArray) {
        if (payload.isEmpty()) { warn("FILE_TRANSFER_STATUS: payload vazio"); return }
        val status = payload[0].toInt() and 0xFF
        log("FILE_TRANSFER_STATUS = $status")

        val previous = lastStatus
        lastStatus = status

        if (previous != null && previous != status) {
            // Transição de status: limpar dados parciais MAS preservar metadata
            // (TYPE/LENGTH/CHECKSUM) — espelha SDK JS.
            val abandoned = bytesReceived
            if (abandoned > 0 && expectedLength > 0 && abandoned < expectedLength) {
                warn("Transição STATUS $previous → $status com transferência incompleta " +
                    "($abandoned/${expectedLength}B) — descartar bytes acumulados")
                metrics?.recordTransferStatusAbort()
                onTransferAborted?.invoke(AbortReason.STATUS_TRANSITION, fileType, abandoned)
            }
            resetTransferData()
        } else if (status == STATUS_IDLE && previous == null) {
            // Caso degenerado: primeiro status que vemos é IDLE → tudo limpo.
            resetInternal()
        }
    }

   
    fun tickTimeout(nowMs: Long = System.currentTimeMillis(), limitMs: Long = 3000L): Boolean {
        if (!isTransferring || lastBlockTimestampMs < 0L) return false
        val age = nowMs - lastBlockTimestampMs
        if (age <= limitMs) return false
        val abandoned = bytesReceived
        warn("Transfer timeout: ${age}ms sem blocos novos, descartar $abandoned/${expectedLength}B")
        timeoutsTotal++
        metrics?.recordTransferTimeout()
        onTransferAborted?.invoke(AbortReason.TIMEOUT, fileType, abandoned)
        resetInternal()
        return true
    }

    
    fun reset() {
        resetInternal()
        log("Reset (external)")
    }

    
    private fun resetInternal() {
        fileType = -1
        expectedLength = 0
        expectedChecksum = 0L
        resetTransferData()
    }

    
    private fun resetTransferData() {
        buffer.reset()
        lastBlockTimestampMs = -1L
    }

    // ── Lógica interna ────────────────────────────────────────────────────────

    private fun completeTransfer() {
        val data = buffer.toByteArray()
        log("Transferência completa: ${data.size}B — a verificar CRC32…")

        val crc = CRC32()
        crc.update(data)
        val actualChecksum = crc.value

        if (actualChecksum != expectedChecksum) {
            err("CRC32 errado: esperado=$expectedChecksum (0x${expectedChecksum.toString(16).uppercase()}) " +
                "obtido=$actualChecksum (0x${actualChecksum.toString(16).uppercase()})")
            crcFailuresTotal++
            metrics?.recordCrcFailure("expected=$expectedChecksum got=$actualChecksum")
            val type = fileType
            val size = data.size
            resetInternal()
            onTransferAborted?.invoke(AbortReason.CRC_MISMATCH, type, size)
            return
        }

        log("CRC32 OK ✓ — fileType=$fileType size=${data.size}B")

        // Capturar estado antes do reset
        val type = fileType
        val snapshot = data.copyOf()

        resetInternal()
        onFileReceived?.invoke(type, snapshot)
    }
}
