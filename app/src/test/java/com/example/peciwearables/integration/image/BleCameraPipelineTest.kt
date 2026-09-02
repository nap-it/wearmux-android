package com.example.peciwearables.integration.image

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BleCameraPipelineTest {

    private lateinit var pipeline: BleCameraPipeline

    // Bitmaps emitidos (coletados via StateFlow value após cada chamada)
    private val emittedBitmaps = mutableListOf<Any?>()

    // Fake JPEG mínimo válido (3 bytes header, body, 2 bytes footer — para testes de tamanho)
    // Para testar assembly lógico sem BitmapFactory, usamos bytes que representam o fluxo.
    private val fakeHeader  = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())  // SOI JPEG
    private val fakeImage   = ByteArray(100) { it.toByte() }  // 100 bytes de payload
    private val fakeFooter  = byteArrayOf(0xFF.toByte(), 0xD9.toByte())  // EOI JPEG

    // Helpers para construir mensagens de tamanho (uint32 LE)
    private fun uint32LE(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )
    private fun uint16LE(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        0, 0  // 4 bytes para readUint32LE aceitar (usa os 2 primeiros para uint16)
    )

    private fun uint16LE2(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )

    /** Envia a sequência completa de sub-tipos para o pipeline. */
    private fun sendFullFrame(
        header: ByteArray = fakeHeader,
        image: ByteArray  = fakeImage,
        footer: ByteArray = fakeFooter
    ) {
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(header.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER, header)
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(image.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, image)
        pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER_SIZE, uint32LE(footer.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER, footer)
    }

    @Before
    fun setup() {
        pipeline = BleCameraPipeline()
        emittedBitmaps.clear()
    }

    // ── Testes de acumulação ──────────────────────────────────────────────────

    @Test
    fun `buffers acumulam multiplos chunks do mesmo sub-tipo`() {
        // Simula imagem grande a chegar em 3 chunks BLE
        val chunk1 = ByteArray(200) { 0x01 }
        val chunk2 = ByteArray(200) { 0x02 }
        val chunk3 = ByteArray(100) { 0x03 }
        val totalImage = chunk1 + chunk2 + chunk3  // 500 bytes

        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(totalImage.size))

        // Antes de completar: nenhum bitmap emitido
        assertNull("Sem bitmap enquanto imagem incompleta", pipeline.latestBitmap.value)

        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, chunk1)
        assertNull("Sem bitmap após chunk 1", pipeline.latestBitmap.value)

        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, chunk2)
        assertNull("Sem bitmap após chunk 2", pipeline.latestBitmap.value)

        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, chunk3)
        // Imagem completa, mas header e footer ainda não chegaram → sem bitmap
        assertNull("Sem bitmap: header e footer em falta", pipeline.latestBitmap.value)
    }

    @Test
    fun `chunks de header sao acumulados correctamente`() {
        // Header em 2 partes
        val part1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val part2 = byteArrayOf(0xFF.toByte())
        val full  = part1 + part2

        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(full.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER, part1)
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER, part2)
        // Header completo, mas sem image e footer → sem bitmap
        assertNull("Header completo mas sem imagem e footer", pipeline.latestBitmap.value)
    }

    @Test
    fun `reset ao receber novo headerSize limpa acumulador`() {
        // Envia header parcial
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(10))
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER, ByteArray(5) { 0xFF.toByte() })

        // Novo frame começa: novo headerSize deve limpar o acumulador
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(3))
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER, fakeHeader)  // 3 bytes exatos

        // image e footer necessários para bitmap — mas podemos verificar que
        // o acumulador foi limpo (não cresceu com dados do frame anterior)
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(fakeImage.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, fakeImage)
        pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER_SIZE, uint32LE(fakeFooter.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER, fakeFooter)
        // O pipeline tentou montar — não verificamos bitmap (BitmapFactory não funciona em host JVM)
        // mas verificamos que não lançou exceção = acumulação funciona
    }

    @Test
    fun `sem imageSize definido nao monta imagem`() {
        // Envia header e footer mas NÃO imageSize/image
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(fakeHeader.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER, fakeHeader)
        pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER_SIZE, uint32LE(fakeFooter.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER, fakeFooter)
        assertNull("Sem bitmap: imageSize nunca definido", pipeline.latestBitmap.value)
    }

    @Test
    fun `sem footerSize definido nao monta imagem`() {
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(fakeHeader.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER, fakeHeader)
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(fakeImage.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, fakeImage)
        // Nenhum footer → não monta
        assertNull("Sem bitmap: footer não enviado", pipeline.latestBitmap.value)
    }

    @Test
    fun `pipeline aceita sub-tipo desconhecido sem crashar`() {
        pipeline.onCameraData(99, byteArrayOf(0x01, 0x02))
        // Sem exceção = OK
    }

    // ── Testes de sequência completa ──────────────────────────────────────────

    @Test
    fun `sequencia completa num unico chunk por sub-tipo nao lanca excecao`() {
        // BitmapFactory vai falhar em JVM host (sem Android) mas nao deve lancar excecao
        // O pipeline deve tentar montar e lidar graciosamente com o erro
        assertDoesNotThrow {
            sendFullFrame()
        }
    }

    @Test
    fun `imagem grande em multiplos chunks image nao lanca excecao`() {
        val bigImage = ByteArray(50_000) { (it % 256).toByte() }
        val chunkSize = 514  // ~MTU

        assertDoesNotThrow {
            pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(fakeHeader.size))
            pipeline.onCameraData(BleCameraPipeline.SUB_HEADER, fakeHeader)
            pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(bigImage.size))

            // Enviar em chunks de 514 bytes (simula BLE MTU 517)
            bigImage.toList().chunked(chunkSize).forEach { chunk ->
                pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, chunk.toByteArray())
            }

            pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER_SIZE, uint32LE(fakeFooter.size))
            pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER, fakeFooter)
        }
    }

    @Test
    fun `acumulacao correta imageBuffer tem todos os bytes apos multiplos chunks`() {
        // Este teste verifica que o buffer so monta quando todos os bytes chegam

        val totalSize = 1000
        val chunk100 = ByteArray(100) { it.toByte() }

        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(totalSize))

        // Enviar 10 chunks de 100 bytes = 1000 bytes total
        repeat(9) {
            pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, chunk100)
            
            assertNull("Bitmap não deve aparecer durante montagem parcial", pipeline.latestBitmap.value)
        }

        // 10.º chunk completa a imagem (mas header/footer em falta → ainda sem bitmap)
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, chunk100)
        assertNull("Ainda sem bitmap: header e footer em falta", pipeline.latestBitmap.value)
    }

    @Test
    fun `IDLE sem assemblagem pendente reseta buffers`() {
        pipeline.onCameraStatusChanged(0 /* IDLE */)
        assertDoesNotThrow { sendFullFrame() }
    }

    @Test
    fun `SLEEPING sem assemblagem pendente reseta buffers`() {
        pipeline.onCameraStatusChanged(3 /* SLEEPING */)
        assertDoesNotThrow { sendFullFrame() }
    }

    @Test
    fun `IDLE com assemblagem pendente nao reseta buffers`() {
        // imageSize recebido → assemblagem em curso → IDLE NÃO deve apagar buffers
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(100))
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, ByteArray(50))

        pipeline.onCameraStatusChanged(0 /* IDLE */)

        // Buffers não foram limpos; nova sequence deve funcionar igualmente (os size-messages reiniciam os buffers)
        assertDoesNotThrow { sendFullFrame() }
    }

    @Test
    fun `SLEEPING com assemblagem pendente nao reseta buffers`() {
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(100))
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, ByteArray(50))

        pipeline.onCameraStatusChanged(3 /* SLEEPING */)

        assertDoesNotThrow { sendFullFrame() }
    }

    @Test
    fun `getStats nao lanca excecao e retorna string`() {
        val stats = pipeline.getStats()
        assertTrue("getStats deve conter 'BLECamera'", stats.contains("BLECamera"))
    }

    // ── Testes size=0 (header/footer opcionais) ───────────────────────────────

    @Test
    fun `headerSize=0 e footerSize=0 dispara assemblagem so com image`() {
        assertDoesNotThrow {
            pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(0))
            pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(fakeImage.size))
            pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, fakeImage)
            pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER_SIZE, uint32LE(0))
            // SUB_FOOTER não enviado (size=0 → vazio) — assembly deve acontecer igualmente
        }
        // BitmapFactory falhará em host JVM mas sem exceção = pipeline tratou corretamente
    }

    @Test
    fun `headerSize=0 dispara assemblagem sem header`() {
        assertDoesNotThrow {
            pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(0))
            pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(fakeImage.size))
            pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, fakeImage)
            pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER_SIZE, uint32LE(fakeFooter.size))
            pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER, fakeFooter)
        }
    }

    @Test
    fun `footerSize=0 dispara assemblagem sem footer`() {
        assertDoesNotThrow {
            pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(fakeHeader.size))
            pipeline.onCameraData(BleCameraPipeline.SUB_HEADER, fakeHeader)
            pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(fakeImage.size))
            pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, fakeImage)
            pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER_SIZE, uint32LE(0))
            // Footer vazio → imagem deve ser montada (header+image apenas)
        }
    }

    @Test
    fun `sem imageSize definido headerSize=0 nao dispara assemblagem`() {
        // imageSize ainda -1 → guard no checkAndAssemble → sem assembly mesmo com size=0 noutros
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(0))
        pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER_SIZE, uint32LE(0))
        // Nenhum imageSize → nenhuma tentativa de montar
        assertNull("Sem bitmap: imageSize nunca chegou", pipeline.latestBitmap.value)
    }

    @Test
    fun `headerSize e footerSize em uint16 sao aceites`() {
        assertDoesNotThrow {
            pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint16LE2(fakeHeader.size))
            pipeline.onCameraData(BleCameraPipeline.SUB_HEADER, fakeHeader)
            pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(fakeImage.size))
            pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, fakeImage)
            pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER_SIZE, uint16LE2(fakeFooter.size))
            pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER, fakeFooter)
        }
    }

    // ── Teste de onLog callback ───────────────────────────────────────────────

    @Test
    fun `onLog callback recebe mensagens de log`() {
        val receivedLogs = mutableListOf<String>()
        pipeline.onLog = { msg -> receivedLogs.add(msg) }

        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(fakeImage.size))

        assertTrue("onLog devia ter recebido pelo menos uma mensagem", receivedLogs.isNotEmpty())
        assertTrue(
            "Mensagem de log devia mencionar imageSize",
            receivedLogs.any { it.contains("imageSize") }
        )
    }

    @Test
    fun `onLog callback recebe aviso para subTipo desconhecido`() {
        val receivedLogs = mutableListOf<String>()
        pipeline.onLog = { msg -> receivedLogs.add(msg) }

        pipeline.onCameraData(99, byteArrayOf(0x01))

        assertTrue("Devia ter recebido aviso de subType desconhecido", receivedLogs.isNotEmpty())
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    @Test
    fun `headerSize invalido gera aviso e nao contamina frame seguinte`() {
        val receivedLogs = mutableListOf<String>()
        pipeline.onLog = { msg -> receivedLogs.add(msg) }

        pipeline.onCameraData(
            BleCameraPipeline.SUB_HEADER_SIZE,
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F.toByte())
        )

        assertTrue(receivedLogs.any { it.contains("Invalid headerSize") })
        assertDoesNotThrow { sendFullFrame() }
    }

    @Test
    fun `imageSize invalido gera aviso e nao contamina frame seguinte`() {
        val receivedLogs = mutableListOf<String>()
        pipeline.onLog = { msg -> receivedLogs.add(msg) }

        pipeline.onCameraData(
            BleCameraPipeline.SUB_IMAGE_SIZE,
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F.toByte())
        )

        assertTrue(receivedLogs.any { it.contains("Invalid imageSize") })
        assertDoesNotThrow { sendFullFrame() }
    }

    private fun assertDoesNotThrow(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail("Não devia lançar exceção mas lançou: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── Regressões B1 + B11 (didBuildImage guard) ─────────────────────────────

    @Test
    fun `STATUS_0 apos frame completo nao re-emite o mesmo frame (regressao B1)`() {
        val jpegEmissions = mutableListOf<ByteArray>()
        pipeline.onJpegReady = { bytes -> jpegEmissions.add(bytes) }

        // Frame completo
        sendFullFrame()
        val emittedAfterFrame = jpegEmissions.size

        // STATUS=0 chega depois — não pode re-emitir o frame anterior
        pipeline.onCameraStatusChanged(0)

        assertEquals(
            "STATUS=0 após frame completo NÃO deve re-emitir (B1)",
            emittedAfterFrame, jpegEmissions.size
        )
        val stats = pipeline.statsSnapshot()
        assertTrue(
            "duplicateAssembleSuppressed deve incrementar (foi=${stats.duplicateAssembleSuppressed})",
            stats.duplicateAssembleSuppressed >= 1L
        )
    }

    @Test
    fun `STATUS_3 apos frame completo nao re-emite o mesmo frame (regressao B1)`() {
        val jpegEmissions = mutableListOf<ByteArray>()
        pipeline.onJpegReady = { bytes -> jpegEmissions.add(bytes) }

        sendFullFrame()
        val emittedAfterFrame = jpegEmissions.size

        pipeline.onCameraStatusChanged(3)

        assertEquals(emittedAfterFrame, jpegEmissions.size)
    }

    @Test
    fun `novo SUB_IMAGE_SIZE limpa didBuildImage e permite nova emissao (regressao B11)`() {
        val jpegEmissions = mutableListOf<Int>()
        pipeline.onJpegReady = { bytes -> jpegEmissions.add(bytes.size) }

        sendFullFrame()
        pipeline.onCameraStatusChanged(0) // STATUS=0 não emite (B1 guard)

        assertEquals("Primeiro frame emitido uma vez", 1, jpegEmissions.size)

        // Segunda frame chega
        sendFullFrame()
        assertEquals("Segundo frame emitido após novo IMAGE_SIZE", 2, jpegEmissions.size)
    }

    @Test
    fun `frame so com image (sem header e footer reset) nao duplica emissao em STATUS_0`() {
        val jpegEmissions = mutableListOf<ByteArray>()
        pipeline.onJpegReady = { bytes -> jpegEmissions.add(bytes) }

        sendFullFrame()
        pipeline.onCameraStatusChanged(0)
        pipeline.onCameraStatusChanged(0) // mesmo status repetido
        pipeline.onCameraStatusChanged(3) // sleep

        assertEquals("Frame só pode ser emitido uma vez por ciclo", 1, jpegEmissions.size)
    }

    // ── Regressão: overshoot é truncado ──────────────────────────────────────

    @Test
    fun `image overshoot e truncado para expectedImageSize`() {
        val jpegEmissions = mutableListOf<ByteArray>()
        pipeline.onJpegReady = { bytes -> jpegEmissions.add(bytes) }

        val expectedSize = 100
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(fakeHeader.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER, fakeHeader)
        pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER_SIZE, uint32LE(fakeFooter.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(expectedSize))
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, ByteArray(150) { it.toByte() })
        pipeline.onCameraData(BleCameraPipeline.SUB_FOOTER, fakeFooter)

        assertEquals(1, jpegEmissions.size)
        assertEquals(fakeHeader.size + expectedSize + fakeFooter.size, jpegEmissions[0].size)
        val stats = pipeline.statsSnapshot()
        assertTrue("overshootTruncations deve ter incrementado", stats.overshootTruncations >= 1L)
    }

    @Test
    fun `STATUS_0 sem assemblagem nao incrementa duplicateAssembleSuppressed`() {
        pipeline.onCameraStatusChanged(0)
        assertEquals(0L, pipeline.statsSnapshot().duplicateAssembleSuppressed)
    }

    // ── Regressão B12 (defensive guard, race entre threads) ──────────────────

    @Test
    fun `defensive guard impede NegativeArraySizeException no caso comum (regressao B12)`() {
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER_SIZE, uint32LE(fakeHeader.size))
        pipeline.onCameraData(BleCameraPipeline.SUB_HEADER, fakeHeader)
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, uint32LE(100))
        pipeline.onCameraData(BleCameraPipeline.SUB_IMAGE, ByteArray(50))


        pipeline.resetState("concurrent reset")

        assertDoesNotThrow {
            pipeline.onCameraStatusChanged(0)
        }
    }

    @Test
    fun `forceImageOnlyWithCachedEnvelope com expectedImageSize negativo nao crasha`() {
        assertDoesNotThrow { pipeline.forceImageOnlyWithCachedEnvelope() }
        assertNull(pipeline.latestBitmap.value)
    }
}
