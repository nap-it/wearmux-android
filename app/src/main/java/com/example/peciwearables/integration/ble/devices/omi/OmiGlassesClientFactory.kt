package com.example.peciwearables.integration.ble.devices.omi

import android.content.Context
import com.example.peciwearables.integration.wearable.WearableId

/**
 * Produz um [OmiGlassesBleClientApi] para o [WearableId] indicado.
 * O context (se necessário) deve ser capturado no lambda de produção — não é
 * passado como parâmetro para que o adapter não precise de Android em testes.
 */
fun interface OmiGlassesClientFactory {
    fun create(id: WearableId): OmiGlassesBleClientApi
}

/** Default: cria um [OmiGlassesBleClient] real com o context fornecido. */
fun defaultOmiGlassesClientFactory(context: Context): OmiGlassesClientFactory =
    OmiGlassesClientFactory { OmiGlassesBleClient(context) }
