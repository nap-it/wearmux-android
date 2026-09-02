package com.example.peciwearables.integration.wearable

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted

/**
 * Helpers para o caso "óculos" sobre a abstração genérica do [WearableHub].
 * Nada específico de firmware: o que conta como óculos é o conjunto de
 * [WearableCapability] declarado. Para adicionar uns novos óculos, criar o client
 * BLE/UDP, uma WearableSession com [GLASSES_REQUIRED_CAPABILITIES] e um
 * WearableAdapter, e registá-lo no WearableConnectionCoordinator.
 * Referência: OmiGlassesWearableAdapter.
 */

/** Conjunto mínimo de capabilities para uma sessão ser considerada "óculos". */
val GLASSES_REQUIRED_CAPABILITIES: Set<WearableCapability> = setOf(
    WearableCapability.IMAGE_CAPTURE,
    WearableCapability.AUDIO_STREAM,
    WearableCapability.IMU_STREAM,
)

/** True se a sessão declara as capabilities típicas de uns óculos. */
fun WearableSession.isGlasses(): Boolean =
    GLASSES_REQUIRED_CAPABILITIES.all { it in capabilities }

/**
 * Sessões activas que declaram capabilities de óculos. Vazia se não há óculos
 * ligados. Pode conter mais que uma se o utilizador ligar dois pares (ex.: para
 * testar comparativamente).
 */
fun WearableHub.glassesSessions(): List<WearableSession> =
    sessions.value.values.filter { it.isGlasses() }

/**
 * StateFlow com a primeira sessão de óculos activa, ou `null` se nenhuma.
 *
 * Usar isto na UI evita ler `glassesState`/`glassesBattery` directamente. Quando
 * aparecer outro adapter de óculos, a UI passa a funcionar sem precisar de
 * mudanças.
 */
fun WearableHub.activeGlassesSession(scope: CoroutineScope): StateFlow<WearableSession?> =
    sessions
        .map { byId -> byId.values.firstOrNull { it.isGlasses() } }
        .stateIn(scope, SharingStarted.Eagerly, null)
