package com.example.peciwearables.integration.wearable

import com.example.peciwearables.integration.wearable.devices.brilliantsole.BrilliantSoleWristbandWearableAdapter
import com.example.peciwearables.integration.wearable.devices.esp32.Esp32WearableAdapter
import com.example.peciwearables.integration.wearable.devices.galaxywatch.GalaxyWatchWearableAdapter
import com.example.peciwearables.integration.wearable.devices.omi.OmiGlassesWearableAdapter

/**
 * Registry pré-configurado com os adapters dos wearables suportados.
 *
 * **Ordem de registo** importa em [WearableAdapterRegistry.confirm] quando
 * nenhum probe desambigua o adapter:
 *  1. `glassesAdapter` — específico (firmware legado ou SDK com hint GLASSES).
 *  2. `wristbandAdapter` — apanha o resto do SDK Brilliant Sole.
 *  3. `esp32Adapter` — específico para Wi-Fi AP da câmara ESP32.
 *  4. `galaxyWatchAdapter` — só responde ao candidate sintético criado pela
 *     bridge do `WatchClient`, nunca colide com BLE genérico.
 */
fun standardWearableAdapterRegistry(
    glassesAdapter: OmiGlassesWearableAdapter,
    wristbandAdapter: BrilliantSoleWristbandWearableAdapter,
    esp32Adapter: Esp32WearableAdapter? = Esp32WearableAdapter(),
    galaxyWatchAdapter: GalaxyWatchWearableAdapter? = null,
): WearableAdapterRegistry {
    val list = mutableListOf<WearableAdapter>(glassesAdapter, wristbandAdapter)
    if (esp32Adapter != null) list += esp32Adapter
    if (galaxyWatchAdapter != null) list += galaxyWatchAdapter
    return WearableAdapterRegistry(list)
}
