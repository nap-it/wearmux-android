package com.example.peciwearables.integration.safety

/** Decide se uma [CloudSafetyDecision] passa pelos toggles activos. */
class SafetyGates(private val toggles: SafetyToggles) {

    fun allow(decision: CloudSafetyDecision): Boolean = when (decision) {
        is CloudSafetyDecision.PedestrianApproaching,
        is CloudSafetyDecision.PedestrianDanger,
        is CloudSafetyDecision.PedestrianSafeToCross -> toggles.uc1.value
        is CloudSafetyDecision.CrossingClear,
        is CloudSafetyDecision.CrossingBlocked -> toggles.uc1.value || toggles.uc3.value
        is CloudSafetyDecision.VehicleApproaching -> when (decision.source) {
            CloudSafetyDecision.Source.ATCLL -> toggles.uc3Incoming.value
            else -> toggles.uc1_4.value || toggles.uc1.value
        }
    }
}
