package com.wearmux.android.integration

import com.wearmux.android.integration.adapters.BleDeviceState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * O estado global da pulseira é o **agregado** das pulseiras ligadas.
 *
 * Com duas pulseiras, o valor global tem de descrever "há uma pulseira
 * saudável" — e não saltar entre elas, como acontecia quando ambas
 * escreviam no mesmo flow.
 */
class WristbandStateAggregationTest {

    @Test
    fun noWristbands_isDisconnected() {
        assertEquals(BleDeviceState.DISCONNECTED, aggregateWristbandState(emptyList()))
    }

    @Test
    fun oneConnectedWristband_isConnected() {
        assertEquals(
            BleDeviceState.CONNECTED,
            aggregateWristbandState(listOf(BleDeviceState.CONNECTED)),
        )
    }

    @Test
    fun connectedWristbandWinsOverFailingOne() {
        assertEquals(
            BleDeviceState.CONNECTED,
            aggregateWristbandState(listOf(BleDeviceState.ERROR, BleDeviceState.CONNECTED)),
        )
    }

    @Test
    fun connectingWristbandWinsOverFailingOne() {
        assertEquals(
            BleDeviceState.CONNECTING,
            aggregateWristbandState(listOf(BleDeviceState.ERROR, BleDeviceState.CONNECTING)),
        )
    }

    @Test
    fun onlyFailingWristbands_isError() {
        assertEquals(
            BleDeviceState.ERROR,
            aggregateWristbandState(listOf(BleDeviceState.ERROR)),
        )
    }

    @Test
    fun onlyDisconnectedWristbands_isDisconnected() {
        assertEquals(
            BleDeviceState.DISCONNECTED,
            aggregateWristbandState(listOf(BleDeviceState.DISCONNECTED, BleDeviceState.DISCONNECTED)),
        )
    }
}
