package com.example.peciwearables.integration.wearable

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted

/**
 * Helpers para o caso "relógio / wearable de pulso" em cima do [WearableHub].
 *
 * Tal como em [Glasses.kt], aqui não há marca. O que faz uma sessão contar
 * como relógio é o conjunto de [WearableCapability]: precisa de IMU para
 * detectar movimento e de háptica para alertar o utilizador via vibração.
 * Câmara é proibida (com câmara é óculos). O resto (battery, device_info,
 * on-device ML) é opcional.
 *
 * Com esta regra o GalaxyWatchWearableSession entra sempre. A BrilliantSole
 * (pulseira ou palmilha) entra quando o handshake reporta `hapticSupported=true`.
 *
 * ## Como adicionar um novo relógio
 *
 * 1. Criar `XxxWatchClient` com o transporte certo (Wear Messaging, BLE custom,
 *    ou outro).
 * 2. Criar `XxxWatchWearableSession : WearableSession`. As `capabilities`
 *    precisam de incluir [WATCH_REQUIRED_CAPABILITIES].
 * 3. Criar `XxxWatchWearableAdapter : WearableAdapter`.
 * 4. Registar o adapter no `WearableConnectionCoordinator`.
 *
 * O `GalaxyWatchWearableAdapter` é a implementação de referência.
 */

/** Capabilities que distinguem um relógio: IMU para movimento + háptica para alertar. */
val WATCH_REQUIRED_CAPABILITIES: Set<WearableCapability> = setOf(
    WearableCapability.IMU_STREAM,
    WearableCapability.HAPTIC,
)

/**
 * Capabilities que **excluem** um wearable da categoria de relógio.
 * Câmara é sinónimo de "óculos" neste sistema (ver [Glasses.kt]).
 */
val WATCH_EXCLUDED_CAPABILITIES: Set<WearableCapability> = setOf(
    WearableCapability.IMAGE_CAPTURE,
)

/** True se a sessão declara o perfil típico de um relógio (IMU + háptica, sem câmara). */
fun WearableSession.isWatch(): Boolean =
    WATCH_REQUIRED_CAPABILITIES.all { it in capabilities } &&
        WATCH_EXCLUDED_CAPABILITIES.none { it in capabilities }

/**
 * Sessões activas no perfil de relógio. Pode conter mais que uma se o
 * utilizador tiver simultaneamente um Galaxy Watch e uma pulseira com háptica.
 */
fun WearableHub.watchSessions(): List<WearableSession> =
    sessions.value.values.filter { it.isWatch() }

/**
 * StateFlow com a primeira sessão de relógio activa, ou `null` se nenhuma.
 *
 * Usar isto na UI evita ler StateFlows acoplados a uma marca específica. Quando
 * aparecer outro relógio (Wear OS standalone, Apple Watch via bridge), a UI
 * passa a funcionar sem precisar de mudanças.
 */
fun WearableHub.activeWatchSession(scope: CoroutineScope): StateFlow<WearableSession?> =
    sessions
        .map { byId -> byId.values.firstOrNull { it.isWatch() } }
        .stateIn(scope, SharingStarted.Eagerly, null)
