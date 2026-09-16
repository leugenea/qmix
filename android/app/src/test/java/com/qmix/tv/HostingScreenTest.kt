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
