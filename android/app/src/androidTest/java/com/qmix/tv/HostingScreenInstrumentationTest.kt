package com.qmix.tv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
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
    fun live_room_remote_ok_and_back_route_through_the_handler() {
        val queued = QueuedTrack("queued-1", "https://example/1", "Queued", "Artist", 65, "fixture")
        val state = mutableStateOf(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, listOf(queued)),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            ),
        )
        var primaryActions = 0
        var exits = 0
        val handler = object : LiveRoomHandler {
            override fun onStartOrNext() {
                primaryActions++
                state.value = state.value.copy(commandPending = true)
            }
            override fun onInvite() {
                state.value = state.value.copy(invitationVisible = true)
            }
            override fun onBack(): LiveRoomBackResult = if (state.value.invitationVisible) {
                state.value = state.value.copy(invitationVisible = false)
                LiveRoomBackResult.HANDLED
            } else {
                LiveRoomBackResult.EXIT_ACTIVITY
            }
        }
        composeRule.setContent {
            HostingScreen(
                state.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
                liveRoomHandler = handler,
                onExitLiveRoom = { exits++ },
            )
        }

        composeRule.onNodeWithText("Start").assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.onNodeWithText("Command pending…").assertExists()
        composeRule.runOnIdle { assertEquals(1, primaryActions) }

        composeRule.runOnIdle { state.value = state.value.copy(commandPending = false) }
        composeRule.onNodeWithText("Start").performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.Enter)
        }
        composeRule.onNodeWithContentDescription("QR code for https://guest.example/r/ABCD").assertExists()

        Espresso.pressBack()
        composeRule.onNodeWithText("Room ABCD").assertExists()
        composeRule.runOnIdle { assertEquals(0, exits) }

        Espresso.pressBack()
        composeRule.runOnIdle { assertEquals(1, exits) }
    }

    @Test
    fun live_room_renders_content_duration_and_every_synchronization_state() {
        val room = RoomState(
            "ABCD",
            CurrentTrack("current-1", 12, "playing", "Current title", "Current artist"),
            listOf(
                QueuedTrack("queued-0", "https://example/0", "Unknown", "Artist", 0, "fixture"),
                QueuedTrack("queued-1", "https://example/1", "Short", "Artist", 65, "fixture"),
                QueuedTrack("queued-2", "https://example/2", "Long", "Artist", 3661, "fixture"),
            ),
        )
        val synchronization = mutableStateOf<RoomSyncState>(
            RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.RECONNECTING),
        )
        composeRule.setContent {
            HostingScreen(
                HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    synchronization.value,
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Room ABCD").assertExists()
        composeRule.onNodeWithText("Reconnecting… Showing last known room.").assertExists()
        composeRule.onNodeWithText("Current title").assertExists()
        composeRule.onNodeWithTag("queue-track-queued-0").assertTextContains("Duration unknown")
        composeRule.onNodeWithTag("queue-track-queued-1").assertTextContains("1:05")
        composeRule.onNodeWithTag("queue-list").performScrollToIndex(2)
        composeRule.onNodeWithTag("queue-track-queued-2").assertTextContains("1:01:01")

        composeRule.runOnIdle {
            synchronization.value = RoomSyncState.Active(
                "ABCD",
                RoomState("ABCD", null, emptyList()),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            )
        }
        composeRule.onNodeWithText("No track playing").assertExists()
        composeRule.onNodeWithText("Queue is empty").assertExists()

        composeRule.runOnIdle {
            synchronization.value = RoomSyncState.Active(
                "ABCD",
                null,
                Freshness.LOADING,
                LiveConnection.CONNECTING,
            )
        }
        composeRule.onNodeWithText("Connecting to room…").assertExists()

        composeRule.runOnIdle {
            synchronization.value = RoomSyncState.Active(
                "ABCD",
                null,
                Freshness.LOADING,
                LiveConnection.RECONNECTING,
            )
        }
        composeRule.onNodeWithText("Reconnecting…").assertExists()

        composeRule.runOnIdle {
            synchronization.value = RoomSyncState.Active(
                "ABCD",
                null,
                Freshness.LOADING,
                LiveConnection.CONNECTED,
            )
        }
        composeRule.onNodeWithText("Waiting for room data…").assertExists()

        composeRule.runOnIdle {
            synchronization.value = RoomSyncState.Active(
                "ABCD",
                room,
                Freshness.STALE,
                LiveConnection.CONNECTED,
            )
        }
        composeRule.onNodeWithText("Updates are stale. Showing last known room.").assertExists()
        composeRule.onNodeWithText("Current title").assertExists()

        composeRule.runOnIdle {
            synchronization.value = RoomSyncState.Active(
                "ABCD",
                null,
                Freshness.STALE,
                LiveConnection.CONNECTED,
            )
        }
        composeRule.onNodeWithText("Could not refresh the room.").assertExists()

        composeRule.runOnIdle { synchronization.value = RoomSyncState.Missing("ABCD") }
        composeRule.onNodeWithText("Room not found.").assertExists()
    }
}
