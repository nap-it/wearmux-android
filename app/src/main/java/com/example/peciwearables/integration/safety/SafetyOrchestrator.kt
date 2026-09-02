package com.example.peciwearables.integration.safety

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


class SafetyOrchestrator(
    private val scope: CoroutineScope,
    private val feed: SafetyDecisionFeed,
    private val outputs: SafetyOutputs,
    private val gates: SafetyGates,
    private val onDispatched: (CloudSafetyDecision) -> Unit = {},
) {

    private val jobs = mutableListOf<Job>()

    fun start() {
        cancel()
        jobs += scope.launch {
            feed.decisions.collect { decision ->
                if (!gates.allow(decision)) return@collect
                runCatching { dispatch(decision) }
                    .onFailure { Log.w(TAG, "dispatch falhou para ${decision::class.simpleName}: ${it.message}") }
                onDispatched(decision)
            }
        }
    }

    fun cancel() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun dispatch(decision: CloudSafetyDecision) {
        when (decision) {
            is CloudSafetyDecision.PedestrianApproaching ->
                outputs.dispatchApproach(decision.zoneName, decision.distanceMeters)
            is CloudSafetyDecision.PedestrianDanger ->
                outputs.dispatchDanger(decision.zoneName)
            is CloudSafetyDecision.PedestrianSafeToCross ->
                outputs.dispatchSafeToCross(decision.zoneName)
            is CloudSafetyDecision.CrossingClear ->
                outputs.dispatchCrossingOk()
            is CloudSafetyDecision.CrossingBlocked ->
                outputs.dispatchCrossingWait(decision.reason)
            is CloudSafetyDecision.VehicleApproaching ->
                outputs.dispatchVehicleAlert(decision.label, decision.depthOrDistance, decision.intensityPct)
        }
    }

    private companion object { const val TAG = "SafetyOrchestrator" }
}
