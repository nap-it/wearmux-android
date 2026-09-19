package com.example.peciwearables.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.peciwearables.AppViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun normalModeShowsOnlyBatteryStatusAndActions() {
        val viewModel = AppViewModel(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
        composeTestRule.setContent {
            DeviceDetailScreen(viewModel = viewModel, deviceId = DeviceId.PHONE, onBack = {}, onOpenStatus = {})
        }

        composeTestRule.onNodeWithText("Battery").assertExists()
        composeTestRule.onNodeWithText("Status").assertExists()
        composeTestRule.onNodeWithTag("technical_details_block").assertDoesNotExist()
    }

    @Test
    fun devModeRevealsTechnicalDetailsBlock() {
        val viewModel = AppViewModel(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
        viewModel.setDeveloperMode(true)
        composeTestRule.setContent {
            DeviceDetailScreen(viewModel = viewModel, deviceId = DeviceId.GLASSES, onBack = {}, onOpenStatus = {})
        }

        composeTestRule.onNodeWithTag("technical_details_block").assertExists()
    }
}
