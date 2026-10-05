package com.wearmux.android.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wearmux.android.AppViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsGiantConnectButtonWhenNothingConnected() {
        val viewModel = AppViewModel(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
        composeTestRule.setContent {
            HomeScreen(viewModel = viewModel, onOpenDevice = {}, onOpenSettings = {})
        }

        composeTestRule.onNodeWithText("Connect Device").assertExists()
    }

    @Test
    fun phoneIsAlwaysListedAsConnected() {
        val viewModel = AppViewModel(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
        composeTestRule.setContent {
            HomeScreen(viewModel = viewModel, onOpenDevice = {}, onOpenSettings = {})
        }

        composeTestRule.onNodeWithText("Phone").assertExists()
    }
}
