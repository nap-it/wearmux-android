package com.example.peciwearables.integration.safety

/** Resultado da avaliação UC1.1 (pedestre distraído). */
sealed interface PedestrianSafetyDecision {
    /** Tudo ok — utilizador parado, fora de zona, ou olhou. */
    data object Safe : PedestrianSafetyDecision

    /**
     * Está a aproximar-se de uma passadeira (dist ≤ `approachMeters`)
     * mas ainda não entrou — pre-alerta suave: beep no relógio, sem vibração.
     */
    data class Approaching(
        val zoneName: String,
        val distanceMeters: Double,
    ) : PedestrianSafetyDecision

    /** Em zona, parado ou a abrandar — não é alerta mas vale a pena registar. */
    data class Watching(val reason: String) : PedestrianSafetyDecision

    /** TODAS as condições UC1.1 cumpridas → vibrar wristband. */
    data class Danger(
        val zoneName: String,
        val moving: Boolean,
        val headRotated: Boolean,
        val decelerating: Boolean,
    ) : PedestrianSafetyDecision
}

/** Resultado de UC1.3 (crossing vocal). */
sealed interface CrossingValidationDecision {
    data object NotRequested : CrossingValidationDecision
    data class Ok(val zoneName: String) : CrossingValidationDecision
    data class Wait(val reason: String) : CrossingValidationDecision
}
