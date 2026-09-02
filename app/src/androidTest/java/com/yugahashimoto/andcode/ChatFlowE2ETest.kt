package com.yugahashimoto.andcode

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class ChatFlowE2ETest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndDisplaysContent() {
        composeTestRule.onNodeWithTag("chat-message-input")
            .assertIsDisplayed()
    }

    @Test
    fun chatInputFieldExists() {
        composeTestRule.onNodeWithTag("chat-message-input")
            .assertExists()
    }

    @Test
    fun canTypeTextInChatInput() {
        composeTestRule.onNodeWithTag("chat-message-input")
            .performTextInput("Hello OpenCode")

        composeTestRule.onNodeWithText("Hello OpenCode")
            .assertIsDisplayed()
    }
}
