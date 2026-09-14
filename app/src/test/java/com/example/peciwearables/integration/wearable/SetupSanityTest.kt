package com.example.peciwearables.integration.wearable

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupSanityTest {

    @Test
    fun runTest_executes() = runTest {
        val flow = MutableStateFlow(0)
        flow.value = 1
        assertEquals(1, flow.value)
    }

    @Test
    fun turbine_capturesEmissions() = runTest {
        val flow = MutableStateFlow(0)
        flow.test {
            assertEquals(0, awaitItem())
            flow.value = 1
            assertEquals(1, awaitItem())
            flow.value = 2
            assertEquals(2, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
