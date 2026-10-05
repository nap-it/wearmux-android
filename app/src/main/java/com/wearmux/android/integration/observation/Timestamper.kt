package com.wearmux.android.integration.observation

/**
 * Carimba as observações à saída da camada de aquisição, dando uma referência
 * temporal comum a óculos, pulseira, watch e telemóvel antes de seguirem para
 * os clientes de API.
 *
 * Não é sincronização de relógios entre dispositivos: é o relógio do hub
 * aplicado num único ponto. É este valor que viaja como `hub_timestamp` e que o
 * fusion engine usa para correlacionar eventos de fontes diferentes. Carimbar
 * no momento do POST incluiria o tempo de fila e de processamento.
 */
class Timestamper(private val clock: () -> Long = System::currentTimeMillis) {

    fun now(): Long = clock()

    fun <T> stamp(value: T): Observation<T> = Observation(value, now())

    /** Preserva o carimbo da aquisição; só gera um novo quando não há nenhum. */
    fun hubTimestampFor(observedAtMs: Long?): Long =
        observedAtMs?.takeIf { it > 0L } ?: now()

    companion object {
        val SYSTEM: Timestamper = Timestamper()
    }
}

data class Observation<out T>(
    val value: T,
    val hubTimestampMs: Long,
)
