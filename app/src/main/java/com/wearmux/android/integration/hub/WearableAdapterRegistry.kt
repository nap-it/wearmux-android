package com.wearmux.android.integration.hub

import com.wearmux.android.integration.hub.DeviceType
import com.wearmux.android.integration.adapters.WearableAdapter
import com.wearmux.android.integration.adapters.WearableConnectedDeviceProfile
import com.wearmux.android.integration.adapters.WearableScanCandidate

/**
 * Registro de [WearableAdapter]s. Centraliza a escolha provável (pós-scan) e
 * confirmada (pós-handshake).
 *
 * Em ambiguidade (UUID partilhado entre adapters), [selectProbable] devolve uma
 * lista — a desambiguação acontece em [confirm], que conhece os services/probes
 * realmente descobertos.
 */
class WearableAdapterRegistry(private val adapterFactories: Map<DeviceType, () -> WearableAdapter>) {


    fun getAllFactories(): List<() -> WearableAdapter> = adapterFactories.values.toList()

    fun selectProbable(scan: WearableScanCandidate): List<WearableAdapter> {
        return adapterFactories.values.map { factory -> factory.invoke() }
            .filter { adapter -> adapter.probableMatch(scan) }
    }

    fun confirm(profile: WearableConnectedDeviceProfile): WearableAdapter? {
        val deviceType = DeviceType.fromScan(profile.scan)

        val factory = adapterFactories[deviceType] ?: return null

        return factory.invoke()
    }
}
