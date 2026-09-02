package com.example.peciwearables.integration.wearable

sealed interface CommandResult {
    data object Accepted : CommandResult
    data class Unsupported(val capability: WearableCapability) : CommandResult
    data class Rejected(val reason: String) : CommandResult
    data class Failed(val cause: Throwable) : CommandResult
}
