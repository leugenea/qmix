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
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostingScreenInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setup_edits_both_urls_and_invokes_create() {
        var settings = ""
        var creates = 0
        composeRule.setContent {
            HostingScreen(
                HostingState.Setup("https://old.example", "https://guest.example"),
                onSettingsChanged = { backend, origin -> settings = "$backend|$origin" },
                onCreate = { creates++ },
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Create room").assertIsFocused().assertIsEnabled()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.onNodeWithContentDescription("Backend URL")
            .performTextReplacement("https://api.example")
        composeRule.onNodeWithContentDescription("Backend URL")
            .assertTextContains("https://api.example")
        composeRule.onNodeWithContentDescription("Guest origin")
            .performTextReplacement("https://join.example")
        composeRule.onNodeWithContentDescription("Guest origin")
            .assertTextContains("https://join.example")

        composeRule.runOnIdle {
            assertEquals("https://api.example|https://join.example", settings)
            assertEquals(1, creates)
        }
    }

    @Test
    fun pending_disables_actions_and_url_inputs() {
        composeRule.setContent {
            HostingScreen(
                HostingState.Pending("https://api.example", "https://guest.example"),
                onSettingsChanged = { _, _ -> error("disabled input changed") },
                onCreate = { error("disabled button clicked") },
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Creating room…").assertExists()
        composeRule.onNodeWithText("Create room").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Backend URL").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Guest origin").assertIsNotEnabled()
    }

    @Test
    fun error_shows_message_and_retry_invokes_create() {
        var retries = 0
        composeRule.setContent {
            HostingScreen(
                HostingState.Error(
                    "The server timed out. Try again.",
                    "https://api.example",
                    "https://guest.example",
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = { retries++ },
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("The server timed out. Try again.").assertExists()
        composeRule.onNodeWithText("Retry").assertIsFocused().assertIsEnabled()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun invitation_renders_real_qr_code_code_link_and_enter_action() {
        var entered = 0
        composeRule.setContent {
            HostingScreen(
                HostingState.Invitation(GuestInvite("ABCD", "https://guest.example/r/ABCD")),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = { entered++ },
            )
        }

        composeRule.onNodeWithContentDescription("QR code for https://guest.example/r/ABCD").assertExists()
        composeRule.onNodeWithText("Join this room").assertExists()
        composeRule.onNodeWithText("ABCD").assertExists()
        composeRule.onNodeWithText("https://guest.example/r/ABCD").assertExists()
        composeRule.onNodeWithText("Enter room").assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(1, entered) }
    }

    @Test
    fun initial_live_room_shows_room_and_next_step() {
        composeRule.setContent {
            HostingScreen(
                HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    RoomSyncState.Active("ABCD", null, Freshness.LOADING, LiveConnection.CONNECTING),
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Room ABCD").assertExists()
        composeRule.onNodeWithText("Playback and live updates are coming next.").assertExists()
    }
}
