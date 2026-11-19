package com.wearmux.android.integration

import com.wearmux.android.integration.adapters.WearableEvent
import com.wearmux.android.integration.adapters.WearableId

/**
 *
 * O id é o identificador da sessão que o emitiu (o MAC address do
 * dispositivo) 
 */
data class DeviceEvent(
    val id: WearableId,
    val event: WearableEvent,
)
