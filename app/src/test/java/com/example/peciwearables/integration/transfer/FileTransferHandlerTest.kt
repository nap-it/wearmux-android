package com.example.peciwearables.integration.transfer

import com.example.peciwearables.integration.protocol.tlv.TlvParser
import com.example.peciwearables.integration.protocol.tlv.TlvWriter
import com.example.peciwearables.integration.protocol.tlv.TxRxMessageType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32


class FileTransferHandlerTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private val receivedFiles = mutableListOf<Pair<Int, ByteArray>>()
    private val sentAcks = mutableListOf<Int>()

    private lateinit var handler: FileTransferHandler

    @Before
    fun setup() {
        receivedFiles.clear()
        sentAcks.clear()
        handler = FileTransferHandler().apply {
            onFileReceived = { fileType, data -> receivedFiles.add(fileType to data) }
            onAckNeeded    = { bytes -> sentAcks.add(bytes) }
        }
    }

    // ── Helpers de bytificação ──────────────────────────────────────────────

    private fun uint8(v: Int) = byteArrayOf(v.toByte())

    private fun uint32LE(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun uint32LE(v: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

    private fun crc32Of(data: ByteArray): Long {
        val crc = CRC32(); crc.update(data); return crc.value
    }

    /** Simula transferência completa de um ficheiro e retorna os dados recebidos. */
    private fun transferFile(fileType: Int, fileData: ByteArray, blockSize: Int = 512): ByteArray? {
        val checksum = crc32Of(fileData)
        handler.onFileType(uint8(fileType))
        handler.onFileLength(uint32LE(fileData.size))
        handler.onFileChecksum(uint32LE(checksum))

        var offset = 0
        while (offset < fileData.size) {
            val end = minOf(offset + blockSize, fileData.size)
            handler.onFileBlock(fileData.copyOfRange(offset, end))
            offset = end
        }
        return receivedFiles.lastOrNull()?.second
    }

    // ── Estado inicial ────────────────────────────────────────────────────────

    @Test
    fun `initial state is clean`() {
        assertEquals(-1, handler.fileType)
        assertEquals(0, handler.expectedLength)
        assertEquals(0L, handler.expectedChecksum)
        assertEquals(0, handler.bytesReceived)
        assertFalse(handler.isTransferring)
    }

    // ── onFileType ────────────────────────────────────────────────────────────

    @Test
    fun `onFileType sets fileType`() {
        handler.onFileType(uint8(4))
        assertEquals(4, handler.fileType)
    }

    @Test
    fun `onFileType FILE_TYPE_CAMERA_IMAGE constant is 4`() {
        assertEquals(4, FileTransferHandler.FILE_TYPE_CAMERA_IMAGE)
    }

    @Test
    fun `onFileType empty payload is a no-op`() {
        handler.onFileType(ByteArray(0))
        assertEquals(-1, handler.fileType)
    }

    @Test
    fun `onFileType all known types parse correctly`() {
        listOf(0, 1, 2, 3, 4).forEach { type ->
            handler.onFileType(uint8(type))
            assertEquals(type, handler.fileType)
        }
    }

    // ── onFileLength ─────────────────────────────────────────────────────────

    @Test
    fun `onFileLength parses uint32 LE correctly`() {
        handler.onFileLength(uint32LE(65536))
        assertEquals(65536, handler.expectedLength)
        assertTrue(handler.isTransferring)
    }

    @Test
    fun `onFileLength small value`() {
        handler.onFileLength(uint32LE(42))
        assertEquals(42, handler.expectedLength)
    }

    @Test
    fun `onFileLength empty payload is no-op`() {
        handler.onFileLength(ByteArray(0))
        assertEquals(0, handler.expectedLength)
    }

    @Test
    fun `onFileLength short payload is no-op`() {
        handler.onFileLength(byteArrayOf(0x01, 0x00))  // only 2 bytes
        assertEquals(0, handler.expectedLength)
    }

    // ── onFileChecksum ────────────────────────────────────────────────────────

    @Test
    fun `onFileChecksum parses uint32 LE as unsigned`() {
        // CRC32 values are unsigned 32-bit — must not be sign-extended
        val checksumBytes = uint32LE(0xFFFFFFFFL)
        handler.onFileChecksum(checksumBytes)
        assertEquals(0xFFFFFFFFL, handler.expectedChecksum)
    }

    @Test
    fun `onFileChecksum typical value`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val expected = crc32Of(data)
        handler.onFileChecksum(uint32LE(expected))
        assertEquals(expected, handler.expectedChecksum)
    }

    // ── onFileBlock — acumulação ──────────────────────────────────────────────

    @Test
    fun `onFileBlock accumulates bytes correctly`() {
        handler.onFileLength(uint32LE(10))  // sem checksum, transfer n ao completa ainda
        handler.onFileBlock(byteArrayOf(1, 2, 3))
        assertEquals(3, handler.bytesReceived)
        handler.onFileBlock(byteArrayOf(4, 5))
        assertEquals(5, handler.bytesReceived)
    }

    @Test
    fun `onFileBlock sends ACK after each block`() {
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(10))
        handler.onFileChecksum(uint32LE(crc32Of(ByteArray(10))))

        handler.onFileBlock(ByteArray(5))
        handler.onFileBlock(ByteArray(5))  // not complete yet — checksum won't match

        assertEquals(listOf(5, 10), sentAcks)
    }

    @Test
    fun `onFileBlock empty payload is no-op`() {
        handler.onFileBlock(ByteArray(0))
        assertEquals(0, handler.bytesReceived)
        assertTrue(sentAcks.isEmpty())
    }

    // ── Conclusão da transferência ────────────────────────────────────────────

    @Test
    fun `single block transfer completes successfully`() {
        val data = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xAA.toByte())
        val result = transferFile(FileTransferHandler.FILE_TYPE_CAMERA_IMAGE, data, blockSize = data.size)

        assertNotNull(result)
        assertArrayEquals(data, result)
        assertEquals(1, receivedFiles.size)
        assertEquals(FileTransferHandler.FILE_TYPE_CAMERA_IMAGE, receivedFiles[0].first)
    }

    @Test
    fun `multi-block transfer completes and reassembles data correctly`() {
        val data = ByteArray(1024) { it.toByte() }
        val result = transferFile(FileTransferHandler.FILE_TYPE_SPRITE_SHEET, data, blockSize = 100)

        assertNotNull(result)
        assertArrayEquals(data, result)
        assertEquals(FileTransferHandler.FILE_TYPE_SPRITE_SHEET, receivedFiles[0].first)
    }

    @Test
    fun `ACK values are cumulative totals of bytes received`() {
        val data = ByteArray(15) { it.toByte() }
        transferFile(4, data, blockSize = 5)

        // 3 blocks of 5 → ACKs: 5, 10, 15
        assertEquals(listOf(5, 10, 15), sentAcks)
    }

    @Test
    fun `state resets to clean after successful transfer`() {
        val data = byteArrayOf(1, 2, 3)
        transferFile(4, data)

        assertEquals(-1, handler.fileType)
        assertEquals(0, handler.expectedLength)
        assertEquals(0, handler.bytesReceived)
        assertFalse(handler.isTransferring)
    }

    @Test
    fun `onFileReceived receives exact data bytes`() {
        val data = "hello JPEG".toByteArray()
        transferFile(4, data)

        assertArrayEquals(data, receivedFiles[0].second)
    }

    // ── Verificação CRC32 ─────────────────────────────────────────────────────

    @Test
    fun `wrong checksum rejects transfer`() {
        val data = byteArrayOf(1, 2, 3, 4)
        val badChecksum = crc32Of(data) xor 0xFFL  // corrupt checksum

        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(data.size))
        handler.onFileChecksum(uint32LE(badChecksum))
        handler.onFileBlock(data)

        assertTrue(receivedFiles.isEmpty())  // NOT fired
    }

    @Test
    fun `wrong checksum resets state`() {
        val data = byteArrayOf(1, 2, 3, 4)
        val badChecksum = crc32Of(data) + 1L

        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(data.size))
        handler.onFileChecksum(uint32LE(badChecksum))
        handler.onFileBlock(data)

        // State should be clean after rejection
        assertEquals(-1, handler.fileType)
        assertEquals(0, handler.bytesReceived)
    }

    @Test
    fun `correct checksum passes validation`() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val checksum = crc32Of(data)

        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(data.size))
        handler.onFileChecksum(uint32LE(checksum))
        handler.onFileBlock(data)

        assertEquals(1, receivedFiles.size)
    }

    // ── FILE_TRANSFER_STATUS ──────────────────────────────────────────────────

    @Test
    fun `STATUS_IDLE resets transfer state`() {
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(100))
        handler.onFileBlock(ByteArray(50))

        handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_IDLE))

        assertEquals(-1, handler.fileType)
        assertEquals(0, handler.expectedLength)
        assertEquals(0, handler.bytesReceived)
    }

    @Test
    fun `STATUS_IDLE does not fire onFileReceived`() {
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(3))
        handler.onFileBlock(byteArrayOf(1, 2, 3))  // would complete but we intercept before

        receivedFiles.clear()
        handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_IDLE))

        // nothing extra triggered
        assertTrue(receivedFiles.isEmpty())
    }

    @Test
    fun `STATUS_SENDING does not reset state`() {
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(100))

        handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_SENDING))

        // state preserved
        assertEquals(4, handler.fileType)
        assertEquals(100, handler.expectedLength)
    }

    @Test
    fun `STATUS_RECEIVING does not reset state`() {
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(200))

        handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_RECEIVING))

        assertEquals(4, handler.fileType)
        assertEquals(200, handler.expectedLength)
    }

    @Test
    fun `FILE_TRANSFER_STATUS empty payload is no-op`() {
        handler.onFileType(uint8(4))
        handler.onFileTransferStatus(ByteArray(0))  // no crash
        assertEquals(4, handler.fileType)
    }

    // ── reset() ──────────────────────────────────────────────────────────────

    @Test
    fun `reset clears all state`() {
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(100))
        handler.onFileBlock(ByteArray(50))

        handler.reset()

        assertEquals(-1, handler.fileType)
        assertEquals(0, handler.expectedLength)
        assertEquals(0L, handler.expectedChecksum)
        assertEquals(0, handler.bytesReceived)
        assertFalse(handler.isTransferring)
    }

    @Test
    fun `second transfer succeeds after first transfer completes`() {
        val data1 = byteArrayOf(1, 2, 3)
        transferFile(4, data1)
        assertEquals(1, receivedFiles.size)

        val data2 = byteArrayOf(10, 20, 30, 40)
        transferFile(4, data2)
        assertEquals(2, receivedFiles.size)
        assertArrayEquals(data2, receivedFiles[1].second)
    }

    // ── Constantes ───────────────────────────────────────────────────────────

    @Test
    fun `file type constants match SDK TypeScript order`() {
        assertEquals(0, FileTransferHandler.FILE_TYPE_TFLITE)
        assertEquals(1, FileTransferHandler.FILE_TYPE_WIFI_SERVER_CERT)
        assertEquals(2, FileTransferHandler.FILE_TYPE_WIFI_SERVER_KEY)
        assertEquals(3, FileTransferHandler.FILE_TYPE_SPRITE_SHEET)
        assertEquals(4, FileTransferHandler.FILE_TYPE_CAMERA_IMAGE)
    }

    @Test
    fun `status constants are correct`() {
        assertEquals(0, FileTransferHandler.STATUS_IDLE)
        assertEquals(1, FileTransferHandler.STATUS_SENDING)
        assertEquals(2, FileTransferHandler.STATUS_RECEIVING)
    }

    // ── Regressões B2 (overshoot truncate) ────────────────────────────────────

    @Test
    fun `last block overshoot is truncated (regressao B2)`() {
        // Cenário dos logs: expectedLength=16539, último bloco trazia 1018B que
        // levaria total a 17290B (104%). Deve truncar e CRC ainda passar.
        val data = ByteArray(16) { (it + 1).toByte() }  // versão pequena do mesmo caso
        val checksum = crc32Of(data)

        handler.onFileType(uint8(FileTransferHandler.FILE_TYPE_CAMERA_IMAGE))
        handler.onFileLength(uint32LE(data.size))
        handler.onFileChecksum(uint32LE(checksum))

        // Primeiro bloco: 10 bytes válidos
        handler.onFileBlock(data.copyOfRange(0, 10))
        // Segundo bloco: traz 10 bytes mas só precisamos de 6 → deve truncar 4
        val oversizedBlock = data.copyOfRange(10, 16) + byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        handler.onFileBlock(oversizedBlock)

        assertEquals("File foi recebido depois do truncate", 1, receivedFiles.size)
        assertArrayEquals("Dados batem certo após truncate", data, receivedFiles[0].second)
        assertEquals("overshootBytesTotal contou os 4 bytes truncados", 4L, handler.overshootBytesTotal)
    }

    @Test
    fun `blocks after completion are silently discarded`() {
        val data = ByteArray(20) { it.toByte() }
        val checksum = crc32Of(data)

        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(data.size))
        handler.onFileChecksum(uint32LE(checksum))
        handler.onFileBlock(data)
        assertEquals(1, receivedFiles.size)

        // Após complete, novo bloco sem novo onFileLength deve ser descartado
        handler.onFileBlock(byteArrayOf(0xFF.toByte()))
        assertEquals("Nenhum efeito", 1, receivedFiles.size)
    }

    @Test
    fun `block without prior length is discarded`() {
        // expectedLength=0 não é "transferência ativa" — bloco isolado deve ser ignorado
        handler.onFileBlock(byteArrayOf(1, 2, 3))
        assertEquals(0, handler.bytesReceived)
        assertTrue(sentAcks.isEmpty())
    }

    @Test
    fun `onTransferAborted fires with OVERSHOOT_TRUNCATED on overshoot`() {
        val aborts = mutableListOf<Triple<FileTransferHandler.AbortReason, Int, Int>>()
        handler.onTransferAborted = { reason, type, bytes -> aborts.add(Triple(reason, type, bytes)) }

        val data = ByteArray(10) { it.toByte() }
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(data.size))
        handler.onFileChecksum(uint32LE(crc32Of(data)))
        handler.onFileBlock(data + byteArrayOf(0x00, 0x00, 0x00))  // 3B overshoot

        assertTrue(aborts.any { it.first == FileTransferHandler.AbortReason.OVERSHOOT_TRUNCATED && it.third == 3 })
    }

    // ── Regressões B10 (reset em qualquer transição de status) ────────────────

    @Test
    fun `STATUS_RECEIVING apos STATUS_SENDING reseta buffer mas preserva metadata (regressao B10+B14)`() {
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(100))
        handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_SENDING))
        // Simular bloco parcial (não realista mas defensivo)
        handler.onFileBlock(ByteArray(30))

        handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_RECEIVING))

        assertEquals("Buffer limpo após transição", 0, handler.bytesReceived)
        // B14 fix: metadata preservada
        assertEquals("FileType preservado", 4, handler.fileType)
        assertEquals("Length preservado", 100, handler.expectedLength)
    }

    @Test
    fun `STATUS_RECEIVING repetido nao reseta`() {
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(100))
        handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_RECEIVING))
        handler.onFileBlock(ByteArray(50))

        // Mesmo status novamente — sem transição → sem reset
        handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_RECEIVING))

        assertEquals(50, handler.bytesReceived)
        assertEquals(4, handler.fileType)
    }

    @Test
    fun `onTransferAborted fires with STATUS_TRANSITION when transition interrupts transfer`() {
        val aborts = mutableListOf<Triple<FileTransferHandler.AbortReason, Int, Int>>()
        handler.onTransferAborted = { reason, type, bytes -> aborts.add(Triple(reason, type, bytes)) }

        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(100))
        handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_RECEIVING))
        handler.onFileBlock(ByteArray(40))

        handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_IDLE))

        assertTrue(aborts.any { it.first == FileTransferHandler.AbortReason.STATUS_TRANSITION && it.third == 40 })
    }

    @Test
    fun `new FILE_TYPE mid-transfer aborts with TYPE_RESET`() {
        val aborts = mutableListOf<Triple<FileTransferHandler.AbortReason, Int, Int>>()
        handler.onTransferAborted = { reason, type, bytes -> aborts.add(Triple(reason, type, bytes)) }

        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(100))
        handler.onFileBlock(ByteArray(30))

        handler.onFileType(uint8(3))  // novo tipo diferente

        assertTrue(aborts.any { it.first == FileTransferHandler.AbortReason.TYPE_RESET && it.second == 4 && it.third == 30 })
        assertEquals(3, handler.fileType)
        assertEquals(0, handler.expectedLength)
    }

    @Test
    fun `same FILE_TYPE mid-transfer does NOT reset`() {
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(100))
        handler.onFileBlock(ByteArray(30))

        handler.onFileType(uint8(4))  // mesmo tipo

        assertEquals(30, handler.bytesReceived)
    }

    // ── Regressões B8 (timeout) ──────────────────────────────────────────────

    @Test
    fun `tickTimeout sem transferencia ativa retorna false`() {
        assertFalse(handler.tickTimeout(System.currentTimeMillis()))
    }

    @Test
    fun `tickTimeout dentro do limite retorna false`() {
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(100))
        handler.onFileBlock(ByteArray(10), nowMs = 1000L)

        assertFalse("Dentro de 3s não dispara", handler.tickTimeout(nowMs = 1500L, limitMs = 3000L))
        assertEquals("Estado preservado", 10, handler.bytesReceived)
    }

    @Test
    fun `tickTimeout depois do limite aborta com TIMEOUT (regressao B8)`() {
        val aborts = mutableListOf<Triple<FileTransferHandler.AbortReason, Int, Int>>()
        handler.onTransferAborted = { reason, type, bytes -> aborts.add(Triple(reason, type, bytes)) }

        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(100))
        handler.onFileBlock(ByteArray(10), nowMs = 1000L)

        val expired = handler.tickTimeout(nowMs = 5000L, limitMs = 3000L)

        assertTrue("Timeout deve disparar", expired)
        assertEquals("Buffer limpo", 0, handler.bytesReceived)
        assertEquals("Length limpo", 0, handler.expectedLength)
        assertEquals("timeoutsTotal incrementou", 1L, handler.timeoutsTotal)
        assertTrue(aborts.any { it.first == FileTransferHandler.AbortReason.TIMEOUT && it.third == 10 })
    }

    @Test
    fun `tickTimeout nao dispara para transferencia completada`() {
        val data = byteArrayOf(1, 2, 3)
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(data.size))
        handler.onFileChecksum(uint32LE(crc32Of(data)))
        handler.onFileBlock(data, nowMs = 1000L)
        assertEquals(1, receivedFiles.size)

        // Após completion, estado está clean → tickTimeout retorna false
        assertFalse(handler.tickTimeout(nowMs = 10_000L, limitMs = 100L))
    }

    // ── Regressões CRC ────────────────────────────────────────────────────────

    // ── Regressão B14 (Fase 2c — STATUS preserva metadata) ──────────────────

    @Test
    fun `sequencia real do protocolo Omi TYPE-LENGTH-CHECKSUM-STATUS-BLOCK funciona (regressao B14)`() {

        val received = mutableListOf<ByteArray>()
        handler.onFileReceived = { _, data -> received.add(data) }

        val aborts = mutableListOf<FileTransferHandler.AbortReason>()
        handler.onTransferAborted = { reason, _, _ -> aborts.add(reason) }

        repeat(5) { frameIdx ->
            val size = 7000 + frameIdx * 1000
            val data = ByteArray(size) { ((frameIdx * 17 + it) and 0xFF).toByte() }
            handler.onFileType(uint8(FileTransferHandler.FILE_TYPE_CAMERA_IMAGE))
            handler.onFileLength(uint32LE(size))
            handler.onFileChecksum(uint32LE(crc32Of(data)))
            handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_RECEIVING))
            var off = 0
            while (off < size) {
                val end = minOf(off + 1018, size)
                handler.onFileBlock(data.copyOfRange(off, end))
                off = end
            }
            handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_IDLE))
        }

        assertEquals("Todos os 5 frames devem completar com sucesso (B14 fix)", 5, received.size)
        assertTrue("Nenhum aborto devia ter ocorrido: $aborts", aborts.isEmpty())

        // Confirmar conteúdo do último para garantir que metadata foi preservada
        val expectedLast = ByteArray(7000 + 4 * 1000) { ((4 * 17 + it) and 0xFF).toByte() }
        assertArrayEquals(expectedLast, received.last())
    }

    @Test
    fun `STATUS preserva metadata mas limpa buffer (B14 unit)`() {
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(500))
        handler.onFileChecksum(uint32LE(123456L))
        handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_RECEIVING)) // primeiro status
        handler.onFileBlock(ByteArray(100))
        assertEquals("Buffer começa a acumular", 100, handler.bytesReceived)
        handler.onFileTransferStatus(uint8(FileTransferHandler.STATUS_IDLE))
        assertEquals("Buffer limpo após transição", 0, handler.bytesReceived)
        assertEquals("Type preservado", 4, handler.fileType)
        assertEquals("Length preservado", 500, handler.expectedLength)
        assertEquals("Checksum preservado", 123456L, handler.expectedChecksum)
    }

    // ── Regressão B13 (Fase 2b — UDP streaming) ──────────────────────────────

    @Test
    fun `10 transferencias consecutivas sequenciais completam todas sem perder metadata (regressao B13)`() {
        val received = mutableListOf<ByteArray>()
        handler.onFileReceived = { _, data -> received.add(data) }

        val abortReasons = mutableListOf<FileTransferHandler.AbortReason>()
        handler.onTransferAborted = { reason, _, _ -> abortReasons.add(reason) }

        repeat(10) { frameIndex ->
            // Tamanhos variáveis (simula JPEGs realistas: 11-17 KB).
            val size = 11_000 + frameIndex * 600
            val data = ByteArray(size) { ((frameIndex * 31 + it) and 0xFF).toByte() }

            // Sequência típica do firmware Omi: TYPE → LENGTH → CHECKSUM → N×BLOCK
            handler.onFileType(uint8(FileTransferHandler.FILE_TYPE_CAMERA_IMAGE))
            handler.onFileLength(uint32LE(data.size))
            handler.onFileChecksum(uint32LE(crc32Of(data)))

            // Blocos de ~1018B como nos logs reais
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + 1018, data.size)
                handler.onFileBlock(data.copyOfRange(offset, end))
                offset = end
            }
        }

        assertEquals(
            "Devíamos ter recebido todos os 10 ficheiros completos",
            10, received.size,
        )
        assertTrue(
            "Não deveria ter havido nenhum aborto (CRC/overshoot/etc): $abortReasons",
            abortReasons.isEmpty(),
        )
        // Conferir conteúdo do último para garantir que não há mistura entre frames.
        val expectedLast = ByteArray(11_000 + 9 * 600) { ((9 * 31 + it) and 0xFF).toByte() }
        assertArrayEquals(expectedLast, received.last())
    }

    @Test
    fun `onTransferAborted fires with CRC_MISMATCH on bad checksum`() {
        val aborts = mutableListOf<Triple<FileTransferHandler.AbortReason, Int, Int>>()
        handler.onTransferAborted = { reason, type, bytes -> aborts.add(Triple(reason, type, bytes)) }

        val data = byteArrayOf(1, 2, 3, 4)
        handler.onFileType(uint8(4))
        handler.onFileLength(uint32LE(data.size))
        handler.onFileChecksum(uint32LE(crc32Of(data) xor 0xFFL))
        handler.onFileBlock(data)

        assertTrue(aborts.any { it.first == FileTransferHandler.AbortReason.CRC_MISMATCH && it.third == data.size })
        assertEquals(1L, handler.crcFailuresTotal)
    }
}
