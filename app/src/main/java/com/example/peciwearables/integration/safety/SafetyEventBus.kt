package com.example.peciwearables.integration.safety

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow


class SafetyEventBus {

    sealed interface Event {
        val timestampMs: Long
        val kind: String

        data class Approach(
            val zoneName: String,
            val distanceMeters: Double,
            override val timestampMs: Long = System.currentTimeMillis(),
        ) : Event { override val kind: String = "approach" }

        data class Danger(
            val zoneName: String,
            override val timestampMs: Long = System.currentTimeMillis(),
        ) : Event { override val kind: String = "danger" }

        data class CrossingOk(
            val zoneName: String,
            override val timestampMs: Long = System.currentTimeMillis(),
        ) : Event { override val kind: String = "crossing_ok" }

        data class CrossingWait(
            val reason: String,
            override val timestampMs: Long = System.currentTimeMillis(),
        ) : Event { override val kind: String = "crossing_wait" }

        data class VehicleAlert(
            val label: String,
            val depth: Float,
            override val timestampMs: Long = System.currentTimeMillis(),
        ) : Event { override val kind: String = "vehicle_alert" }

        data class CyclistVehicle(
            val side: String,        // LEFT | CENTER | RIGHT
            val intensity: Int,      // 0..255
            override val timestampMs: Long = System.currentTimeMillis(),
        ) : Event { override val kind: String = "cyclist_vehicle" }

        data class DeviceConnected(
            val device: String,      // omi | sole | watch
            override val timestampMs: Long = System.currentTimeMillis(),
        ) : Event { override val kind: String = "device_connected" }

        data class DeviceDisconnected(
            val device: String,
            override val timestampMs: Long = System.currentTimeMillis(),
        ) : Event { override val kind: String = "device_disconnected" }
    }

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun publish(event: Event) {
        _events.tryEmit(event)
    }
}
