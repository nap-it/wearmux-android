package com.wearmux.android.integration.hub

import com.wearmux.android.integration.adapters.WearableAdapter
import com.wearmux.android.integration.adapters.devices.brilliantsole.BrilliantSoleWristbandWearableAdapter
import com.wearmux.android.integration.adapters.devices.esp32.Esp32WearableAdapter
import com.wearmux.android.integration.adapters.devices.omi.OmiGlassesWearableAdapter

/**
 * Registry com os adapters "standard" da app — o mesmo mapa que
 * `WearableService.bootstrapAdaptersAndHub()` constrói.
 *
 * Existe em `src/test` porque só os testes precisam de um registry com
 * adapters concretos (a produção constrói-o com as factories reais).
 */
internal fun standardWearableAdapterRegistry(
    glassesAdapter: OmiGlassesWearableAdapter,
    wristbandAdapter: BrilliantSoleWristbandWearableAdapter,
    esp32Adapter: Esp32WearableAdapter,
): WearableAdapterRegistry = WearableAdapterRegistry(
    mapOf(
        DeviceType.WRISTBAND_BRILLIANT_SOLE to { wristbandAdapter },
        DeviceType.GLASSES_OMI to { glassesAdapter },
        DeviceType.ESP32_CAMERA to { esp32Adapter },
    ),
)

/** Registry só com adapters de teste, cada um associado a um [DeviceType]. */
internal fun testWearableAdapterRegistry(
    vararg entries: Pair<DeviceType, () -> WearableAdapter>,
): WearableAdapterRegistry = WearableAdapterRegistry(mapOf(*entries))
