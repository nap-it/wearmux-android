package com.example.peciwearables.integration.wearable

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeWearableSession(
    override val id: WearableId,
    override val adapterId: String = "fake",
    override val capabilities: Set<WearableCapability> = emptySet(),
    initialState: WearableConnectionState = WearableConnectionState.CONNECTED,
) : WearableSession {

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<WearableConnectionState> = _state

    private val _events = MutableSharedFlow<WearableEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val events: SharedFlow<WearableEvent> = _events.asSharedFlow()

    val sentCommands = mutableListOf<WearableCommand>()
    var nextResult: CommandResult = CommandResult.Accepted
    var closeCalled = false

    suspend fun emit(event: WearableEvent) = _events.emit(event)

    fun setState(state: WearableConnectionState) {
        _state.value = state
    }

    override suspend fun send(command: WearableCommand): CommandResult {
        sentCommands += command
        return nextResult
    }

    override suspend fun close() {
        closeCalled = true
        _state.value = WearableConnectionState.DISCONNECTED
    }
}
