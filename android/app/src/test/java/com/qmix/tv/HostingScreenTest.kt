package com.qmix.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setup_supports_keyboard_input_and_primary_action_starts_focused() {
        var backend = "https://old.example"
        var origin = "https://guest.example"
        var creates = 0
        composeRule.setContent {
            HostingScreen(
                state = HostingState.Setup(backend, origin),
                onSettingsChanged = { newBackend, newOrigin ->
                    backend = newBackend
                    origin = newOrigin
                },
                onCreate = { creates++ },
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Create room")
            .assertIsFocused()
            .assertIsEnabled()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.onNodeWithContentDescription("Backend URL")
            .performTextReplacement("https://api.example")
        composeRule.onNodeWithContentDescription("Backend URL")
            .assertTextContains("https://api.example")
        composeRule.runOnIdle {
            assertEquals("https://api.example", backend)
            assertEquals("https://guest.example", origin)
            assertEquals(1, creates)
        }
    }

    @Test
    fun pending_shows_progress_and_disables_duplicate_action() {
        composeRule.setContent {
            HostingScreen(
                state = HostingState.Pending("https://api.example", "https://guest.example"),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Creating room…").assertExists()
        composeRule.onNodeWithText("Create room").assertIsNotEnabled()
    }

    @Test
    fun error_is_human_readable_and_retry_is_focused() {
        composeRule.setContent {
            HostingScreen(
                state = HostingState.Error(
                    "The server timed out. Try again.",
                    "https://api.example",
                    "https://guest.example",
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("The server timed out. Try again.").assertExists()
        composeRule.onNodeWithText("Retry").assertIsFocused()
    }

    @Test
    fun invitation_shows_qr_code_and_link_then_enters_room_with_ok() {
        var entered = false
        composeRule.setContent {
            HostingScreen(
                state = HostingState.Invitation(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = { entered = true },
            )
        }

        composeRule.onNodeWithContentDescription("QR code for https://guest.example/r/ABCD").assertExists()
        composeRule.onNodeWithText("ABCD").assertExists()
        composeRule.onNodeWithText("https://guest.example/r/ABCD").assertExists()
        composeRule.onNodeWithText("Enter room")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }

        composeRule.runOnIdle { assertEquals(true, entered) }
    }
}
