package com.qmix.tv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostingScreenInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun http_warning_is_remote_navigable_and_states_lan_risk() {
        var confirmations = 0
        var cancellations = 0
        composeRule.setContent {
            HostingScreen(
                HostingState.HttpWarning("http://192.168.1.20:8180", "http://192.168.1.20:8180"),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
                onConfirmHttpWarning = { confirmations++ },
                onCancelHttpWarning = { cancellations++ },
            )
        }

        composeRule.onNodeWithText("HTTP is not private").assertExists()
        composeRule.onNodeWithText(
            "HTTP exposes room traffic and host credentials to devices on the local network. " +
                "Use it only on a trusted LAN. Use HTTPS for public or remote deployments.",
        ).assertExists()
        composeRule.onNodeWithText("Use HTTP").assertIsFocused()
            .performKeyInput {
                pressKey(Key.Enter)
                pressKey(Key.DirectionRight)
            }
        composeRule.onNodeWithText("Cancel").assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle {
            assertEquals(1, confirmations)
            assertEquals(1, cancellations)
        }
    }

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
        var inviteActions = 0
        var exits = 0
        val handler = object : LiveRoomHandler {
            override fun onStartOrNext() {
                primaryActions++
                state.value = state.value.copy(commandPending = true)
            }
            override fun onInvite() {
                inviteActions++
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
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue("D-pad center was not accepted", device.pressDPadCenter())
        device.waitForIdle()
        composeRule.onNodeWithText("Command pending…").assertExists()
        composeRule.runOnIdle { assertEquals(1, primaryActions) }

        composeRule.runOnIdle { state.value = state.value.copy(commandPending = false) }
        composeRule.onNodeWithText("Invite").assertIsFocused()
        assertTrue("D-pad center was not accepted", device.pressDPadCenter())
        device.waitForIdle()
        composeRule.onNodeWithContentDescription("QR code for https://guest.example/r/ABCD").assertExists()
        composeRule.runOnIdle { assertEquals(1, inviteActions) }

        Espresso.pressBack()
        composeRule.onNodeWithText("Room ABCD").assertExists()
        composeRule.runOnIdle { assertEquals(0, exits) }

        Espresso.pressBack()
        composeRule.runOnIdle { assertEquals(1, exits) }
    }

    @Test
    fun live_room_restores_track_focus_and_scrolls_with_dpad_only() {
        val queue = (0 until 30).map { index ->
            QueuedTrack("track-$index", "https://example/$index", "Track $index", "Artist", 65, "fixture")
        }
        val state = mutableStateOf(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, queue),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            ),
        )
        composeRule.setContent {
            HostingScreen(
                state.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.waitForIdle()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(20) {
            assertTrue("D-pad down was not accepted", device.pressDPadDown())
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("queue-track-track-19")
            .assertIsFocused()
            .assertIsDisplayed()

        composeRule.runOnIdle {
            val reordered = listOf(queue[19]) + queue.filterNot { it.id == "track-19" }
            state.value = state.value.copy(
                synchronization = RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, reordered),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            )
        }
        composeRule.onNodeWithTag("queue-track-track-19")
            .assertIsFocused()
            .assertIsDisplayed()

        composeRule.runOnIdle {
            val withoutFocused = queue.filterNot { it.id == "track-19" }
            state.value = state.value.copy(
                synchronization = RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, withoutFocused),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            )
        }
        composeRule.onNodeWithTag("queue-track-track-0")
            .assertIsFocused()
            .assertIsDisplayed()

        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val primary = composeRule.onNodeWithText("Start").fetchSemanticsNode().boundsInRoot
        val invite = composeRule.onNodeWithText("Invite").fetchSemanticsNode().boundsInRoot
        val queueList = composeRule.onNodeWithTag("queue-list").fetchSemanticsNode().boundsInRoot
        val focusedRow = composeRule.onNodeWithTag("queue-track-track-0").fetchSemanticsNode().boundsInRoot
        listOf(primary, invite, queueList, focusedRow).forEach { bounds ->
            assertTrue("element is clipped on the left", bounds.left >= root.left)
            assertTrue("element is clipped on the top", bounds.top >= root.top)
            assertTrue("element is clipped on the right", bounds.right <= root.right)
            assertTrue("element is clipped on the bottom", bounds.bottom <= root.bottom)
        }
        assertTrue("room actions overlap", primary.right <= invite.left)
        assertTrue("room actions overlap the queue", maxOf(primary.bottom, invite.bottom) <= queueList.top)

        val focusedPixels = composeRule.onNodeWithTag("queue-track-track-0").captureToImage().toPixelMap()
        val middleY = focusedPixels.height / 2
        val visibleWhiteBorder = (0 until minOf(12, focusedPixels.width)).any { x ->
            val color = focusedPixels[x, middleY]
            color.red > 0.9f && color.green > 0.9f && color.blue > 0.9f && color.alpha > 0.9f
        }
        assertTrue("focused queue row has no visible white border", visibleWhiteBorder)
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

    @Test
    fun live_room_playback_controls_activate_the_focused_action_and_restore_focus() {
        val current = CurrentTrack("current-1", 12, "playing", "Current title", "Current artist")
        val queued = QueuedTrack("next-1", "https://example/next", "Next title", "Artist", 65, "fixture")
        val state = mutableStateOf(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", current, listOf(queued)),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
                playback = LocalPlaybackState(
                    trackId = "current-1",
                    status = LocalPlaybackStatus.PLAYING,
                    isPlaying = true,
                    positionMs = 20_000,
                    durationMs = 60_000,
                    isSeekable = true,
                ),
            ),
        )
        var toggles = 0
        var retries = 0
        var nextActions = 0
        val seeks = mutableListOf<Long>()
        val handler = object : LiveRoomHandler {
            override fun onStartOrNext() { nextActions++ }
            override fun onPlayPause() { toggles++ }
            override fun onSeekBy(offsetMs: Long) { seeks += offsetMs }
            override fun onRetryCurrent() { retries++ }
            override fun onInvite() = Unit
            override fun onBack() = LiveRoomBackResult.IGNORED
        }
        composeRule.setContent {
            HostingScreen(
                state.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
                liveRoomHandler = handler,
            )
        }

        composeRule.onNodeWithText("Local playback: Playing").assertExists()
        composeRule.onNodeWithTag("room-next").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("playback-play-pause").assertIsFocused().assertTextContains("Pause")
            .performKeyInput {
                pressKey(Key.Enter)
                pressKey(Key.DirectionRight)
            }
        composeRule.onNodeWithTag("playback-seek-back").assertIsFocused().performKeyInput {
            pressKey(Key.Enter)
            pressKey(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("playback-seek-forward").assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle {
            assertEquals(1, toggles)
            assertEquals(listOf(-10_000L, 10_000L), seeks)
        }

        composeRule.runOnIdle {
            state.value = state.value.copy(
                playback = state.value.playback.copy(
                    status = LocalPlaybackStatus.PAUSED,
                    isPlaying = false,
                ),
            )
        }
        composeRule.onNodeWithText("Local playback: Paused").assertExists()
        composeRule.onNodeWithTag("playback-seek-forward").assertIsFocused()
        composeRule.runOnIdle {
            state.value = state.value.copy(
                playback = state.value.playback.copy(durationMs = null),
            )
        }
        composeRule.onNodeWithTag("playback-play-pause").assertIsFocused().assertTextContains("Play")
        composeRule.onNodeWithTag("playback-seek-back").assertDoesNotExist()
        composeRule.onNodeWithTag("playback-seek-forward").assertDoesNotExist()

        composeRule.runOnIdle {
            state.value = state.value.copy(
                playback = LocalPlaybackState(
                    trackId = "current-1",
                    status = LocalPlaybackStatus.ERROR,
                    error = PlaybackError(PlaybackErrorKind.HTTP, "private upstream", 502),
                ),
            )
        }
        composeRule.onNodeWithText("Playback error: Stream request failed (HTTP 502).").assertExists()
        composeRule.onNodeWithTag("playback-retry").assertIsFocused().performKeyInput {
            pressKey(Key.Enter)
            pressKey(Key.DirectionUp)
        }
        composeRule.onNodeWithTag("room-next").assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, nextActions)
        }
    }

    @Test
    fun live_room_renders_every_local_playback_status_and_sanitized_error_kind() {
        val current = CurrentTrack("current-1", 0, "playing", "Current", "Artist")
        val playback = mutableStateOf(LocalPlaybackState())
        composeRule.setContent {
            HostingScreen(
                HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    RoomSyncState.Active(
                        "ABCD",
                        RoomState("ABCD", current, emptyList()),
                        Freshness.FRESH,
                        LiveConnection.CONNECTED,
                    ),
                    playback = playback.value,
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Local playback: Idle").assertExists()
        composeRule.onNodeWithTag("playback-play-pause").assertDoesNotExist()
        val rendered = listOf(
            LocalPlaybackState("current-1", LocalPlaybackStatus.BUFFERING) to "Local playback: Buffering",
            LocalPlaybackState("current-1", LocalPlaybackStatus.PLAYING, isPlaying = true) to
                "Local playback: Playing",
            LocalPlaybackState("current-1", LocalPlaybackStatus.PAUSED) to "Local playback: Paused",
            LocalPlaybackState("current-1", LocalPlaybackStatus.COMPLETED) to "Local playback: Completed",
        )
        rendered.forEach { (next, label) ->
            composeRule.runOnIdle { playback.value = next }
            composeRule.onNodeWithText(label).assertExists()
        }
        composeRule.onNodeWithTag("playback-play-pause").assertDoesNotExist()

        val rawUpstreamDetail = "QMIX_RAW_UPSTREAM_DETAIL_MUST_NEVER_RENDER_8f13c2"
        val errors = listOf(
            PlaybackError(PlaybackErrorKind.HTTP, rawUpstreamDetail) to "Stream request failed.",
            PlaybackError(PlaybackErrorKind.RANGE, rawUpstreamDetail, 416) to "Stream seek failed (HTTP 416).",
            PlaybackError(PlaybackErrorKind.RANGE, rawUpstreamDetail) to "Stream seek failed.",
            PlaybackError(PlaybackErrorKind.DECODE, rawUpstreamDetail) to "Audio format could not be played.",
            PlaybackError(PlaybackErrorKind.NETWORK, rawUpstreamDetail) to "Network connection interrupted.",
            PlaybackError(PlaybackErrorKind.UNKNOWN, rawUpstreamDetail) to "Playback failed unexpectedly.",
        )
        errors.forEach { (error, message) ->
            composeRule.runOnIdle {
                playback.value = LocalPlaybackState(
                    trackId = "current-1",
                    status = LocalPlaybackStatus.ERROR,
                    error = error,
                )
            }
            composeRule.onNodeWithText("Local playback: Error").assertExists()
            composeRule.onNodeWithText("Playback error: $message").assertExists()
            composeRule.onNodeWithTag("playback-retry").assertExists()
            val leakedDetail = hasText(rawUpstreamDetail, substring = true)
            composeRule.onAllNodes(leakedDetail).assertCountEquals(0)
            composeRule.onAllNodes(leakedDetail, useUnmergedTree = true).assertCountEquals(0)
        }
    }
}
