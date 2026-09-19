package com.qmix.tv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun focus_restoration_is_invalidated_by_newer_directional_input() {
        val memory = LiveRoomFocusMemory()
        memory.reconcile(listOf("track-1", "track-2"), primaryEnabled = true)
        memory.record(LiveRoomFocusTarget.Track("track-2"))
        val restoration = memory.reconcile(listOf("track-2", "track-1"), primaryEnabled = true)

        assertTrue(memory.isCurrent(restoration))
        memory.markDirectionalInput()
        assertFalse(memory.isCurrent(restoration))

        val fallbackMemory = LiveRoomFocusMemory()
        fallbackMemory.record(LiveRoomFocusTarget.Track("missing"))
        assertEquals(
            LiveRoomFocusTarget.Primary,
            fallbackMemory.reconcile(emptyList(), primaryEnabled = true).target,
        )
    }

    @Test
    fun playback_control_focus_identity_is_preserved_and_falls_back_deterministically() {
        val memory = LiveRoomFocusMemory()
        val controls = listOf(
            LiveRoomFocusTarget.PlayPause,
            LiveRoomFocusTarget.SeekBack,
            LiveRoomFocusTarget.SeekForward,
        )
        memory.reconcile(emptyList(), primaryEnabled = false, playbackTargets = controls)
        memory.record(LiveRoomFocusTarget.SeekForward)

        assertEquals(
            LiveRoomFocusTarget.SeekForward,
            memory.reconcile(listOf("track-1"), primaryEnabled = true, playbackTargets = controls).target,
        )
        assertEquals(
            LiveRoomFocusTarget.PlayPause,
            memory.reconcile(
                listOf("track-1"),
                primaryEnabled = true,
                playbackTargets = listOf(LiveRoomFocusTarget.PlayPause),
            ).target,
        )
        assertEquals(
            LiveRoomFocusTarget.Retry,
            memory.reconcile(
                listOf("track-1"),
                primaryEnabled = true,
                playbackTargets = listOf(LiveRoomFocusTarget.Retry),
            ).target,
        )
    }

    @Test
    fun playback_control_focus_is_restored_by_identity_when_capabilities_change() {
        val current = CurrentTrack("current-1", 0, "playing", "Current", "Artist")
        val presentation = mutableStateOf(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", current, emptyList()),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
                playback = LocalPlaybackState(
                    trackId = "current-1",
                    status = LocalPlaybackStatus.PAUSED,
                    positionMs = 20_000,
                    durationMs = 60_000,
                    isSeekable = true,
                ),
            ),
        )
        composeRule.setContent {
            HostingScreen(
                state = presentation.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithTag("room-invite").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("playback-play-pause").performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("playback-seek-forward").assertIsFocused()
        composeRule.runOnIdle {
            presentation.value = presentation.value.copy(
                playback = presentation.value.playback.copy(positionMs = 30_000),
            )
        }
        composeRule.onNodeWithTag("playback-seek-forward").assertIsFocused()

        composeRule.runOnIdle {
            presentation.value = presentation.value.copy(
                playback = presentation.value.playback.copy(isSeekable = false),
            )
        }
        composeRule.onNodeWithTag("playback-play-pause").assertIsFocused()

        composeRule.runOnIdle {
            presentation.value = presentation.value.copy(
                playback = LocalPlaybackState(
                    trackId = "current-1",
                    status = LocalPlaybackStatus.ERROR,
                    error = PlaybackError(PlaybackErrorKind.NETWORK, "offline"),
                ),
            )
        }
        composeRule.onNodeWithTag("playback-retry").assertIsFocused()
    }

    @Test
    fun http_warning_explains_exposure_and_offers_focused_explicit_choice() {
        var confirmations = 0
        var cancellations = 0
        composeRule.setContent {
            HostingScreen(
                state = HostingState.HttpWarning("http://192.168.1.20:8180", "http://192.168.1.20:8180"),
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
        composeRule.onNodeWithText("Use HTTP")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(1, confirmations) }
        composeRule.onNodeWithText("Use HTTP").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithText("Cancel")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(1, cancellations) }
    }

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
    fun live_room_play_pause_and_seek_controls_dispatch_only_their_focused_actions() {
        val current = CurrentTrack("current-1", 12, "playing", "Server title", "Server artist")
        val playback = LocalPlaybackState(
            trackId = "current-1",
            status = LocalPlaybackStatus.PLAYING,
            isPlaying = true,
            positionMs = 15_000,
            durationMs = 60_000,
            isSeekable = true,
        )
        var toggles = 0
        val seeks = mutableListOf<Long>()
        val handler = object : LiveRoomHandler {
            override fun onStartOrNext() = Unit
            override fun onPlayPause() { toggles++ }
            override fun onSeekBy(offsetMs: Long) { seeks += offsetMs }
            override fun onRetryCurrent() = Unit
            override fun onInvite() = Unit
            override fun onBack() = LiveRoomBackResult.IGNORED
        }
        composeRule.setContent {
            HostingScreen(
                state = HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    RoomSyncState.Active(
                        "ABCD",
                        RoomState("ABCD", current, emptyList()),
                        Freshness.FRESH,
                        LiveConnection.CONNECTED,
                    ),
                    playback = playback,
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
                liveRoomHandler = handler,
            )
        }

        composeRule.onNodeWithText("Server-selected track").assertExists()
        composeRule.onNodeWithText("Server title").assertExists()
        composeRule.onNodeWithText("Local playback: Playing").assertExists()
        composeRule.onNodeWithTag("room-invite").performKeyInput { pressKey(Key.DirectionDown) }
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
    }

    @Test
    fun live_room_hides_seek_without_a_seekable_known_timeline() {
        val state = mutableStateOf(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Active(
                    "ABCD",
                    RoomState(
                        "ABCD",
                        CurrentTrack("current-1", 0, "playing", "Current", "Artist"),
                        emptyList(),
                    ),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
                playback = LocalPlaybackState(
                    trackId = "current-1",
                    status = LocalPlaybackStatus.PAUSED,
                    durationMs = null,
                    isSeekable = true,
                ),
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

        composeRule.onNodeWithTag("playback-seek-back").assertDoesNotExist()
        composeRule.onNodeWithTag("playback-seek-forward").assertDoesNotExist()
        composeRule.runOnIdle {
            state.value = state.value.copy(
                playback = state.value.playback.copy(durationMs = 60_000, isSeekable = false),
            )
        }
        composeRule.onNodeWithTag("playback-seek-back").assertDoesNotExist()
        composeRule.onNodeWithTag("playback-seek-forward").assertDoesNotExist()
    }

    @Test
    fun every_playback_control_routes_up_to_invite_when_next_is_disabled() {
        composeRule.setContent {
            HostingScreen(
                state = playingLiveRoom(queue = emptyList()),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithTag("room-next").assertIsNotEnabled()
        composeRule.onNodeWithTag("room-invite").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("playback-play-pause").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("room-invite").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("playback-play-pause").performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("playback-seek-back").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("room-invite").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("playback-play-pause").performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("playback-seek-forward").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("room-invite").assertIsFocused()
    }

    @Test
    fun playback_control_up_switches_to_invite_when_primary_capability_is_lost() {
        val queued = QueuedTrack("next-1", "https://example/1", "Next", "Artist", 60, "fixture")
        val presentation = mutableStateOf(playingLiveRoom(queue = listOf(queued)))
        composeRule.setContent {
            HostingScreen(
                state = presentation.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithTag("room-next").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("playback-play-pause").assertIsFocused()
        composeRule.runOnIdle {
            presentation.value = presentation.value.copy(commandPending = true)
        }
        composeRule.onNodeWithTag("room-next").assertIsNotEnabled()
        composeRule.onNodeWithTag("playback-play-pause").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("room-invite").assertIsFocused()
    }

    @Test
    fun error_retry_up_returns_to_invite_when_empty_queue_disables_next() {
        composeRule.setContent {
            HostingScreen(
                state = HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    RoomSyncState.Active(
                        "ABCD",
                        RoomState(
                            "ABCD",
                            CurrentTrack("current-1", 0, "playing", "Current", "Artist"),
                            emptyList(),
                        ),
                        Freshness.FRESH,
                        LiveConnection.CONNECTED,
                    ),
                    playback = LocalPlaybackState(
                        trackId = "current-1",
                        status = LocalPlaybackStatus.ERROR,
                        error = PlaybackError(PlaybackErrorKind.NETWORK, "offline"),
                    ),
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithTag("room-next").assertIsNotEnabled()
        composeRule.onNodeWithTag("room-invite").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("playback-retry").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("room-invite").assertIsFocused()
    }

    @Test
    fun stream_error_renders_and_dispatches_retry_current_and_explicit_next() {
        val queued = QueuedTrack("next-1", "https://example/1", "Next title", "Artist", 65, "fixture")
        var retries = 0
        var nextActions = 0
        val handler = object : LiveRoomHandler {
            override fun onStartOrNext() { nextActions++ }
            override fun onPlayPause() = Unit
            override fun onSeekBy(offsetMs: Long) = Unit
            override fun onRetryCurrent() { retries++ }
            override fun onInvite() = Unit
            override fun onBack() = LiveRoomBackResult.IGNORED
        }
        composeRule.setContent {
            HostingScreen(
                state = HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    RoomSyncState.Active(
                        "ABCD",
                        RoomState(
                            "ABCD",
                            CurrentTrack("current-1", 0, "playing", "Current", "Artist"),
                            listOf(queued),
                        ),
                        Freshness.FRESH,
                        LiveConnection.CONNECTED,
                    ),
                    playback = LocalPlaybackState(
                        trackId = "current-1",
                        status = LocalPlaybackStatus.ERROR,
                        error = PlaybackError(PlaybackErrorKind.HTTP, "private upstream", 502),
                    ),
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
                liveRoomHandler = handler,
            )
        }

        composeRule.onNodeWithText("Playback error: Stream request failed (HTTP 502).").assertExists()
        composeRule.onNodeWithTag("room-next").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
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
    fun raw_playback_error_detail_is_absent_from_merged_and_unmerged_semantics() {
        val rawUpstreamDetail = "QMIX_RAW_UPSTREAM_DETAIL_MUST_NEVER_RENDER_8f13c2"
        composeRule.setContent {
            HostingScreen(
                state = HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    RoomSyncState.Active(
                        "ABCD",
                        RoomState(
                            "ABCD",
                            CurrentTrack("current-1", 0, "playing", "Current", "Artist"),
                            emptyList(),
                        ),
                        Freshness.FRESH,
                        LiveConnection.CONNECTED,
                    ),
                    playback = LocalPlaybackState(
                        trackId = "current-1",
                        status = LocalPlaybackStatus.ERROR,
                        error = PlaybackError(PlaybackErrorKind.HTTP, rawUpstreamDetail, 502),
                    ),
                ),
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText(
            "Playback error: Stream request failed (HTTP 502).",
            substring = true,
        ).assertExists()
        val leakedDetail = hasText(rawUpstreamDetail, substring = true)
        composeRule.onAllNodes(leakedDetail).assertCountEquals(0)
        composeRule.onAllNodes(leakedDetail, useUnmergedTree = true).assertCountEquals(0)
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
    fun live_room_keeps_track_focus_by_id_after_reorder() {
        val first = QueuedTrack("track-1", "https://example/1", "First", "Artist", 65, "fixture")
        val second = QueuedTrack("track-2", "https://example/2", "Second", "Artist", 65, "fixture")
        val presentation = mutableStateOf(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, listOf(first, second)),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            ),
        )
        composeRule.setContent {
            HostingScreen(
                state = presentation.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Start").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        composeRule.onNodeWithTag("queue-track-track-2").assertIsFocused()

        composeRule.runOnIdle {
            presentation.value = presentation.value.copy(
                synchronization = RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, listOf(second, first)),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            )
        }

        composeRule.onNodeWithTag("queue-track-track-2").assertIsFocused()
    }

    @Test
    fun live_room_moves_focus_to_the_removed_tracks_former_index() {
        val first = QueuedTrack("track-1", "https://example/1", "First", "Artist", 65, "fixture")
        val second = QueuedTrack("track-2", "https://example/2", "Second", "Artist", 65, "fixture")
        val third = QueuedTrack("track-3", "https://example/3", "Third", "Artist", 65, "fixture")
        val presentation = mutableStateOf(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, listOf(first, second, third)),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            ),
        )
        composeRule.setContent {
            HostingScreen(
                state = presentation.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Start").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        composeRule.onNodeWithTag("queue-track-track-2").assertIsFocused()

        composeRule.runOnIdle {
            presentation.value = presentation.value.copy(
                synchronization = RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, listOf(first, third)),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            )
        }

        composeRule.onNodeWithTag("queue-track-track-3").assertIsFocused()
    }

    @Test
    fun live_room_moves_focus_to_the_previous_track_when_the_last_track_is_removed() {
        val first = QueuedTrack("track-1", "https://example/1", "First", "Artist", 65, "fixture")
        val second = QueuedTrack("track-2", "https://example/2", "Second", "Artist", 65, "fixture")
        val presentation = mutableStateOf(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, listOf(first, second)),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            ),
        )
        composeRule.setContent {
            HostingScreen(
                state = presentation.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Start").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        composeRule.onNodeWithTag("queue-track-track-2").assertIsFocused()

        composeRule.runOnIdle {
            presentation.value = presentation.value.copy(
                synchronization = RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, listOf(first)),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            )
        }

        composeRule.onNodeWithTag("queue-track-track-1").assertIsFocused()
    }

    @Test
    fun live_room_empty_queue_falls_back_to_an_enabled_room_action() {
        val track = QueuedTrack("track-1", "https://example/1", "First", "Artist", 65, "fixture")
        val presentation = mutableStateOf(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, listOf(track)),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            ),
        )
        composeRule.setContent {
            HostingScreen(
                state = presentation.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Start").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("queue-track-track-1").assertIsFocused()
        composeRule.runOnIdle {
            presentation.value = presentation.value.copy(
                synchronization = RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, emptyList()),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            )
        }
        composeRule.onNodeWithText("Start").assertIsNotEnabled()
        composeRule.onNodeWithText("Invite").assertIsFocused()
    }

    @Test
    fun live_room_dpad_traversal_reaches_actions_and_scrolls_long_queues() {
        val queue = (0 until 30).map { index ->
            QueuedTrack("track-$index", "https://example/$index", "Track $index", "Artist", 65, "fixture")
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

        composeRule.onNodeWithText("Start").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithText("Invite").assertIsFocused()
        composeRule.onNodeWithText("Invite").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("queue-track-track-0").assertIsFocused()
        composeRule.onNodeWithTag("queue-track-track-0").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText("Start").assertIsFocused()

        composeRule.onNodeWithText("Start").performKeyInput {
            repeat(20) { pressKey(Key.DirectionDown) }
        }
        composeRule.onNodeWithTag("queue-track-track-19").assertIsFocused()
    }

    @Test
    fun live_room_repopulated_queue_starts_dpad_traversal_at_the_first_track() {
        val queue = (0 until 30).map { index ->
            QueuedTrack("track-$index", "https://example/$index", "Track $index", "Artist", 65, "fixture")
        }
        val presentation = mutableStateOf(
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
                state = presentation.value,
                onSettingsChanged = { _, _ -> },
                onCreate = {},
                onEnterRoom = {},
            )
        }

        composeRule.onNodeWithText("Start").performKeyInput {
            repeat(20) { pressKey(Key.DirectionDown) }
        }
        composeRule.onNodeWithTag("queue-track-track-19").assertIsFocused()
        composeRule.runOnIdle {
            presentation.value = presentation.value.copy(
                synchronization = RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, emptyList()),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            )
        }
        composeRule.onNodeWithText("Invite").assertIsFocused()
        composeRule.runOnIdle {
            presentation.value = presentation.value.copy(
                synchronization = RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, queue),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            )
        }

        composeRule.onNodeWithText("Invite").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("queue-track-track-0").assertIsFocused()
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

    private fun playingLiveRoom(queue: List<QueuedTrack>) = HostingState.LiveRoom(
        GuestInvite("ABCD", "https://guest.example/r/ABCD"),
        RoomSyncState.Active(
            "ABCD",
            RoomState(
                "ABCD",
                CurrentTrack("current-1", 0, "playing", "Current", "Artist"),
                queue,
            ),
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
    )
}
