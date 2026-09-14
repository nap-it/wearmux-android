package com.example.peciwearables.integration.safety

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Stream único de decisões de safety, independente de origem (cloud, ATCLL, local). */
interface SafetyDecisionFeed {
    val decisions: SharedFlow<CloudSafetyDecision>
}

/** Implementação default: aceita publicações externas via [publish]/[onCloudDecisions]. */
class DefaultSafetyDecisionFeed(
    bufferCapacity: Int = 32,
) : SafetyDecisionFeed {

    private val _decisions = MutableSharedFlow<CloudSafetyDecision>(
        replay = 0,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val decisions: SharedFlow<CloudSafetyDecision> = _decisions.asSharedFlow()

    fun publish(decision: CloudSafetyDecision) {
        _decisions.tryEmit(decision)
    }

    /** Adapter para `TelemetryReporter.onDecisions`. */
    fun onCloudDecisions(raw: List<Map<String, Any?>>) {
        CloudSafetyDecisionParser.parseAll(raw).forEach { _decisions.tryEmit(it) }
    }
}
