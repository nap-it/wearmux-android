package com.wearmux.android.integration.adapters

/**
 * Retrato de **uma** sessão de wearable activa, pronto para consumo pela UI.
 *
 * Existe um snapshot por sessão: com duas pulseiras Brilliant Sole ligadas ao
 * mesmo tempo há dois snapshots, cada um com a sua identidade ([id] — o MAC
 * address do dispositivo BLE).
 *
 * A identidade vem de [WearableSession.id], que é a fonte autoritativa do MAC.
 * O `adapterId` é apenas o *tipo* de dispositivo e **não** identifica a
 * instância — agrupar por `adapterId` esconderia as pulseiras umas das outras.
 */
data class WearableDeviceSnapshot(
    /** MAC address / identificador único da sessão ([WearableSession.id]). */
    val id: String,
    val state: BleDeviceState,
    /** Percentagem de bateria; `-1` quando desconhecida. */
    val battery: Int,
    val firmware: String,
    /**
     * Actividade ML detectada **nesta** sessão (ex.: "walking").
     *
     * Com várias pulseiras, a actividade é por pulseira — a global seria a
     * da última inferência e não descreveria bem nenhum dos cards.
     */
    val activity: String = "",
)
