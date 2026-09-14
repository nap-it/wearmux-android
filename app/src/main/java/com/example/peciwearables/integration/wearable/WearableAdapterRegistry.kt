package com.example.peciwearables.integration.wearable

/**
 * Registro de [WearableAdapter]s. Centraliza a escolha provável (pós-scan) e
 * confirmada (pós-handshake).
 *
 * Em ambiguidade (UUID partilhado entre adapters), [selectProbable] devolve uma
 * lista — a desambiguação acontece em [confirm], que conhece os services/probes
 * realmente descobertos.
 */
class WearableAdapterRegistry(private val adapters: List<WearableAdapter>) {

    val all: List<WearableAdapter> get() = adapters

    fun selectProbable(scan: WearableScanCandidate): List<WearableAdapter> =
        adapters.filter { it.probableMatch(scan) }

    fun confirm(profile: WearableConnectedDeviceProfile): WearableAdapter? =
        adapters.firstOrNull { it.confirmMatch(profile) }
}
