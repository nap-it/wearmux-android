package com.example.peciwearables.integration.wearable

import kotlinx.coroutines.CoroutineScope

class DefaultWearableConnectionCoordinator(
    private val registry: WearableAdapterRegistry,
) : WearableConnectionCoordinator {

    override suspend fun coordinate(
        scan: WearableScanCandidate,
        scope: CoroutineScope,
    ): Result<WearableSession> {
        val probableAdapters = registry.selectProbable(scan)
        if (probableAdapters.isEmpty()) {
            return Result.failure(IllegalStateException("No adapter matches scan candidate ${scan.id.raw}"))
        }

        val failures = mutableListOf<Throwable>()
        probableAdapters.forEach { adapter ->
            val result = adapter.connectAndCreateSession(scan, scope)
            result.getOrNull()?.let { return Result.success(it) }
            result.exceptionOrNull()?.let { failures += it }
        }

        val message = failures.joinToString("; ") { it.message ?: it::class.java.simpleName }
        return Result.failure(IllegalStateException("No adapter confirmed ${scan.id.raw}: $message"))
    }
}

