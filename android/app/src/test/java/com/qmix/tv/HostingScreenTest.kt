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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
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
    fun live_room_renders_current_and_empty_queue_independently() {
        composeRule.setContent {
            HostingScreen(
                state = HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    RoomSyncState.Active(
                        "ABCD",
                        RoomState(
                            "ABCD",
                            CurrentTrack("current-1", 12, "playing", "Current title", "Current artist"),
                            emptyList(),
                        ),
                        Freshness.FRESH,
                        LiveConnection.CONNECTED,
                    ),
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Current title").assertExists()
        composeRule.onNodeWithText("Current artist").assertExists()
        composeRule.onNodeWithText("Queue is empty").assertExists()
        composeRule.onNodeWithText("No track playing").assertDoesNotExist()
    }

    @Test
    fun live_room_shows_start_and_invite_actions_when_no_track_is_current() {
        val queued = QueuedTrack("track-1", "https://example/1", "Title", "Artist", 65, "fixture")
        composeRule.setContent {
            HostingScreen(
                state = HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    RoomSyncState.Active(
                        "ABCD",
                        RoomState("ABCD", null, listOf(queued)),
                        Freshness.FRESH,
                        LiveConnection.CONNECTED,
                    ),
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Start").assertIsEnabled()
        composeRule.onNodeWithText("Invite").assertIsEnabled()
        composeRule.onNodeWithText("Next").assertDoesNotExist()
    }

    @Test
    fun unreliable_live_room_states_keep_invite_available_and_disable_primary_action() {
        val state = mutableStateOf<HostingState.LiveRoom>(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Missing("ABCD"),
            ),
        )
        composeRule.setContent {
            HostingScreen(
                state = state.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Start").assertIsNotEnabled()
        composeRule.onNodeWithText("Invite").assertIsEnabled().assertIsFocused()

        val queued = QueuedTrack("track-1", "https://example/1", "Title", "Artist", 65, "fixture")
        composeRule.runOnIdle {
            state.value = state.value.copy(
                synchronization = RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, listOf(queued)),
                    Freshness.FRESH,
                    LiveConnection.RECONNECTING,
                ),
            )
        }
        composeRule.onNodeWithText("Start").assertIsNotEnabled()

        composeRule.runOnIdle {
            state.value = state.value.copy(
                synchronization = RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, listOf(queued)),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
                commandPending = true,
            )
        }
        composeRule.onNodeWithText("Start").assertIsNotEnabled()
        composeRule.onNodeWithText("Command pending…").assertExists()
    }

    @Test
    fun live_room_dispatches_each_enabled_activation_once_and_blocks_pending_actions() {
        val queued = QueuedTrack("track-1", "https://example/1", "Title", "Artist", 65, "fixture")
        val presentation = mutableStateOf(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Active(
                    "ABCD",
                    RoomState(
                        "ABCD",
                        CurrentTrack("current", 0, "playing", "Current", "Artist"),
                        listOf(queued),
                    ),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            ),
        )
        var activations = 0
        val handler = object : LiveRoomHandler {
            override fun onStartOrNext() { activations++ }
            override fun onInvite() = Unit
            override fun onBack() = LiveRoomBackResult.IGNORED
        }
        composeRule.setContent {
            HostingScreen(
                state = presentation.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
                liveRoomHandler = handler,
            )
        }

        composeRule.onNodeWithText("Next")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(1, activations) }

        composeRule.runOnIdle {
            presentation.value = presentation.value.copy(commandPending = true)
        }
        composeRule.onNodeWithText("Command pending…").assertExists()
        composeRule.onNodeWithText("Next").assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, activations) }
    }

    @Test
    fun live_room_invite_reopens_real_qr_and_back_returns_before_exit() {
        val invite = GuestInvite("ABCD", "https://guest.example/r/ABCD")
        val room = RoomState(
            "ABCD",
            CurrentTrack("current", 0, "playing", "Unchanged title", "Artist"),
            listOf(QueuedTrack("track-1", "https://example/1", "Queued", "Artist", 65, "fixture")),
        )
        val presentation = mutableStateOf(
            HostingState.LiveRoom(
                invite,
                RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED),
            ),
        )
        var exits = 0
        val handler = object : LiveRoomHandler {
            override fun onStartOrNext() = Unit
            override fun onInvite() {
                presentation.value = presentation.value.copy(invitationVisible = true)
            }
            override fun onBack(): LiveRoomBackResult = if (presentation.value.invitationVisible) {
                presentation.value = presentation.value.copy(invitationVisible = false)
                LiveRoomBackResult.HANDLED
            } else {
                LiveRoomBackResult.EXIT_ACTIVITY
            }
        }
        composeRule.setContent {
            HostingScreen(
                state = presentation.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
                liveRoomHandler = handler,
                onExitLiveRoom = { exits++ },
            )
        }

        repeat(2) {
            composeRule.onNodeWithText("Next").performKeyInput {
                pressKey(Key.DirectionRight)
                pressKey(Key.Enter)
            }
            composeRule.onNodeWithContentDescription("QR code for https://guest.example/r/ABCD").assertExists()
            composeRule.onNodeWithText("ABCD").assertExists()
            composeRule.onNodeWithText("https://guest.example/r/ABCD").assertExists()
            composeRule.onNodeWithText("Back to room")
                .assertIsFocused()
                .performKeyInput { pressKey(Key.Enter) }
            composeRule.onNodeWithText("Unchanged title").assertExists()
            composeRule.runOnIdle { assertEquals(0, exits) }
        }

        composeRule.runOnIdle { handleLiveRoomBack(handler) { exits++ } }
        composeRule.runOnIdle { assertEquals(1, exits) }
        composeRule.onNodeWithText("host-secret").assertDoesNotExist()
    }

    @Test
    fun live_room_distinguishes_synchronization_and_terminal_states() {
        val room = RoomState(
            "ABCD",
            CurrentTrack("current-1", 12, "playing", "Retained title", "Artist"),
            emptyList(),
        )
        val synchronization = mutableStateOf<RoomSyncState>(
            RoomSyncState.Active("ABCD", null, Freshness.LOADING, LiveConnection.CONNECTING),
        )
        composeRule.setContent {
            HostingScreen(
                state = HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    synchronization.value,
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
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
                Freshness.FRESH,
                LiveConnection.RECONNECTING,
            )
        }
        composeRule.onNodeWithText("Reconnecting… Showing last known room.").assertExists()
        composeRule.onNodeWithText("Retained title").assertExists()

        composeRule.runOnIdle {
            synchronization.value = RoomSyncState.Active(
                "ABCD",
                room,
                Freshness.STALE,
                LiveConnection.CONNECTED,
            )
        }
        composeRule.onNodeWithText("Updates are stale. Showing last known room.").assertExists()

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

    @Test
    fun live_room_renders_unknown_duration_unicode_and_backend_limit_queue_lazily() {
        val longTitle = "Very long title " + "x".repeat(240)
        val queue = (0 until 100).map { index ->
            QueuedTrack(
                id = "track-$index",
                url = "https://example/$index",
                title = if (index == 0) longTitle else "Track $index — 音楽 🎵",
                artist = if (index == 0) "Artiste — アーティスト" else "Artist $index",
                durationSeconds = when (index) {
                    0 -> 0
                    1 -> -1
                    99 -> 3661
                    else -> 65
                },
                resolvedBy = "fixture",
            )
        }
        composeRule.setContent {
            HostingScreen(
                state = HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    RoomSyncState.Active(
                        "ABCD",
                        RoomState("ABCD", null, queue),
                        Freshness.FRESH,
                        LiveConnection.CONNECTED,
                    ),
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("No track playing").assertExists()
        composeRule.onNodeWithTag("queue-track-track-0").assertTextContains(longTitle)
        composeRule.onNodeWithTag("queue-track-track-0").assertTextContains("Duration unknown")
        composeRule.onNodeWithTag("queue-list").performScrollToIndex(1)
        composeRule.onNodeWithTag("queue-track-track-1").assertTextContains("Duration unknown")
        composeRule.onNodeWithText("00:00").assertDoesNotExist()
        composeRule.onNodeWithTag("queue-list").performScrollToIndex(99)
        composeRule.onNodeWithTag("queue-track-track-99").assertExists()
        composeRule.onNodeWithText("Track 99 — 音楽 🎵").assertExists()
        composeRule.onNodeWithTag("queue-track-track-99").assertTextContains("1:01:01")
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
