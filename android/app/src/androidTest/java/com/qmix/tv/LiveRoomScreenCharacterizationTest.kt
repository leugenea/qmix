package com.qmix.tv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** qmix#269: capture the existing screen's externally observable contracts before restructuring it. */
@RunWith(AndroidJUnit4::class)
class LiveRoomScreenCharacterizationTest {
    @get:Rule val composeRule = createComposeRule()

    private val first = QueuedTrack("first", "https://example/first", "First title", "First artist", 65, "fixture")
    private val second = QueuedTrack("second", "https://example/second", "Second title", "Second artist", 0, "fixture")
    private val current = CurrentTrack("now", 0, "playing", "Now title", "Now artist")
    private val invite = GuestInvite("ABCD", "https://guest.example/r/ABCD")
    private val focusTags = listOf(
        "room-next", "room-start", "room-invite", "playback-play-pause",
        "playback-seek-back", "playback-seek-forward", "playback-retry",
        "queue-track-first", "queue-track-second",
    )

    private fun room(queue: List<QueuedTrack> = listOf(first, second), selected: CurrentTrack? = current) =
        RoomSyncState.Active("ABCD", RoomState("ABCD", selected, queue), Freshness.FRESH, LiveConnection.CONNECTED)

    private fun state(
        playback: LocalPlaybackState = LocalPlaybackState(),
        queue: List<QueuedTrack> = listOf(first, second),
        selected: CurrentTrack? = current,
    ) = HostingState.LiveRoom(invite, room(queue, selected), playback = playback)

    private fun waitForFocus(step: String, tag: String) {
        var lastFocused = emptyList<String>()
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                lastFocused = focusTags.filter { candidate ->
                    composeRule.onAllNodesWithTag(candidate).fetchSemanticsNodes().any { node ->
                        node.config.contains(SemanticsProperties.Focused) &&
                            node.config[SemanticsProperties.Focused]
                    }
                }
                tag in lastFocused
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$step: expected $tag; last focused=$lastFocused", timeout)
        }
        composeRule.onNodeWithTag(tag).assertIsFocused()
    }

    private fun press(from: String, key: Key, step: String, to: String) {
        composeRule.onNodeWithTag(from).performKeyInput { pressKey(key) }
        waitForFocus(step, to)
    }

    @Test
    fun controls_depend_on_track_status_seekability_and_duration_not_only_server_selection() {
        val model = mutableStateOf(state())
        composeRule.setContent { HostingScreen(model.value, { _, _ -> }, {}, {}) }
        waitForFocus("initial action", "room-next")
        composeRule.onNodeWithTag("playback-play-pause").assertDoesNotExist()
        composeRule.onNodeWithTag("playback-retry").assertDoesNotExist()

        composeRule.runOnIdle {
            model.value = model.value.copy(playback = LocalPlaybackState("now", LocalPlaybackStatus.BUFFERING, isSeekable = true))
        }
        composeRule.onNodeWithTag("playback-play-pause").assertTextContains("Pause")
        composeRule.onNodeWithTag("playback-seek-back").assertDoesNotExist()
        composeRule.onNodeWithTag("playback-seek-forward").assertDoesNotExist()

        composeRule.runOnIdle {
            model.value = model.value.copy(playback = model.value.playback.copy(durationMs = 60_000))
        }
        composeRule.onNodeWithTag("playback-seek-back").assertIsDisplayed()
        composeRule.onNodeWithTag("playback-seek-forward").assertIsDisplayed()
        composeRule.runOnIdle {
            model.value = model.value.copy(playback = model.value.playback.copy(status = LocalPlaybackStatus.PAUSED))
        }
        composeRule.onNodeWithTag("playback-play-pause").assertTextContains("Play")
        composeRule.runOnIdle {
            model.value = model.value.copy(playback = model.value.playback.copy(status = LocalPlaybackStatus.ERROR))
        }
        composeRule.onNodeWithTag("playback-play-pause").assertDoesNotExist()
        composeRule.onNodeWithTag("playback-seek-back").assertDoesNotExist()
        composeRule.onNodeWithTag("playback-retry").assertIsDisplayed()
        composeRule.runOnIdle {
            model.value = model.value.copy(playback = model.value.playback.copy(status = LocalPlaybackStatus.COMPLETED))
        }
        composeRule.onNodeWithTag("playback-retry").assertDoesNotExist()
        composeRule.runOnIdle {
            model.value = model.value.copy(playback = LocalPlaybackState(null, LocalPlaybackStatus.ERROR))
        }
        composeRule.onNodeWithTag("playback-retry").assertDoesNotExist()
    }

    @Test
    fun remote_navigation_and_queue_updates_keep_focus_on_all_seven_target_kinds() {
        val playing = LocalPlaybackState("now", LocalPlaybackStatus.PLAYING, isPlaying = true,
            isSeekable = true, durationMs = 60_000)
        val model = mutableStateOf(state(playback = playing))
        composeRule.setContent { HostingScreen(model.value, { _, _ -> }, {}, {}) }
        waitForFocus("initial primary", "room-next")

        fun reorderWhileFocused(step: String, tag: String) {
            composeRule.runOnIdle {
                val old = (model.value.synchronization as RoomSyncState.Active).room!!
                model.value = model.value.copy(synchronization = room(old.queue.reversed()))
            }
            waitForFocus("$step after queue reorder", tag)
        }

        reorderWhileFocused("primary", "room-next")
        press("room-next", Key.DirectionRight, "primary to invite", "room-invite")
        reorderWhileFocused("invite", "room-invite")
        press("room-invite", Key.DirectionDown, "invite to playback", "playback-play-pause")
        reorderWhileFocused("play/pause", "playback-play-pause")
        press("playback-play-pause", Key.DirectionRight, "playback to seek back", "playback-seek-back")
        reorderWhileFocused("seek back", "playback-seek-back")
        press("playback-seek-back", Key.DirectionRight, "seek back to forward", "playback-seek-forward")
        reorderWhileFocused("seek forward", "playback-seek-forward")
        press("playback-seek-forward", Key.DirectionDown, "seek to first queued track", "queue-track-second")
        reorderWhileFocused("queued track", "queue-track-second")

        composeRule.runOnIdle {
            model.value = model.value.copy(playback = LocalPlaybackState("now", LocalPlaybackStatus.ERROR))
        }
        waitForFocus("track survives playback control replacement", "queue-track-second")
        press("queue-track-second", Key.DirectionUp, "second track to first track", "queue-track-first")
        press("queue-track-first", Key.DirectionUp, "first track to retry", "playback-retry")
        reorderWhileFocused("retry", "playback-retry")
        press("playback-retry", Key.DirectionUp, "retry to primary", "room-next")
    }

    @Test
    fun room_layout_renders_server_content_and_disables_primary_during_pending_command() {
        val model = mutableStateOf(state(playback = LocalPlaybackState("now", LocalPlaybackStatus.PLAYING)))
        composeRule.setContent { HostingScreen(model.value, { _, _ -> }, {}, {}) }
        waitForFocus("initial room content", "room-next")
        composeRule.onNodeWithText("Room ABCD").assertExists()
        composeRule.onNodeWithText("Local playback: Playing").assertExists()
        composeRule.onNodeWithText("Now title").assertExists()
        composeRule.onNodeWithText("Now artist").assertExists()
        composeRule.onNodeWithTag("queue-track-first").assertTextContains("First title")
        composeRule.onNodeWithTag("queue-track-first").assertTextContains("1:05")
        composeRule.onNodeWithTag("queue-track-second").assertTextContains("Duration unknown")
        composeRule.onNodeWithTag("room-next").assertIsEnabled()

        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val primary = composeRule.onNodeWithTag("room-next").fetchSemanticsNode().boundsInRoot
        val inviteBounds = composeRule.onNodeWithTag("room-invite").fetchSemanticsNode().boundsInRoot
        val queue = composeRule.onNodeWithTag("queue-list").fetchSemanticsNode().boundsInRoot
        assertTrue("room actions should be side by side", primary.right <= inviteBounds.left)
        assertTrue("queue should follow room actions", maxOf(primary.bottom, inviteBounds.bottom) <= queue.top)
        assertTrue("queue should remain within root", queue.left >= root.left && queue.right <= root.right && queue.bottom <= root.bottom)

        composeRule.runOnIdle { model.value = model.value.copy(commandPending = true) }
        composeRule.onNodeWithText("Command pending…").assertExists()
        composeRule.onNodeWithTag("room-next").assertIsNotEnabled()
        composeRule.onNodeWithTag("room-invite").assertIsEnabled()
        composeRule.runOnIdle { model.value = model.value.copy(commandPending = false, synchronization = room(selected = null)) }
        composeRule.onNodeWithTag("room-next").assertDoesNotExist()
        composeRule.onNodeWithTag("room-start").assertIsEnabled()
        composeRule.onNodeWithText("No track playing").assertExists()
    }
}
