package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.ArrayDeque

class AuthoritativePlaybackCoordinatorTest {
    @Test
    fun stream_url_appends_encoded_room_path_to_backend_base() {
        assertEquals(
            "https://qmix.test/base/rooms/A%2FB/current/stream",
            currentPlaybackStreamUrl("https://qmix.test/base/", "A/B"),
        )
    }

    private val engine = RecordingPlaybackEngine()
    private val reconciler = RecordingRoomFetcher()
    private val advances = mutableListOf<String>()
    private val coordinator = AuthoritativePlaybackCoordinator(
        roomCode = "ABCD",
        streamUrl = "https://qmix.test/rooms/ABCD/current/stream",
        playbackEngine = engine,
        reconciler = reconciler,
        dispatcher = Executor { it.run() },
        advanceAfterEnded = { trackId -> advances += trackId; true },
    )

    @Test
    fun fresh_selected_current_prepares_and_starts_exactly_once() {
        val selected = fresh(room(currentId = "one"))

        coordinator.onSynchronization(selected)
        coordinator.onSynchronization(selected)

        assertEquals(
            listOf(PlaybackMedia("one", "https://qmix.test/rooms/ABCD/current/stream")),
            engine.prepared,
        )
        assertEquals(1, engine.playCount)
    }

    @Test
    fun foreground_loss_stops_audio_and_blocks_controls_and_stale_player_signals() {
        coordinator.onSynchronization(fresh(room(currentId = "one", queueIds = listOf("two"))))
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.READY, isPlaying = true))

        coordinator.onForegroundLost()
        coordinator.resume()
        coordinator.togglePlayPause()
        coordinator.seekBy(10_000)
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ENDED))

        assertEquals(1, engine.pauseCount)
        assertEquals(1, engine.playCount)
        assertTrue(engine.seeks.isEmpty())
        assertTrue(advances.isEmpty())
        assertEquals(LocalPlaybackStatus.PAUSED, coordinator.state.status)
    }

    @Test
    fun later_foreground_loss_invalidates_an_already_queued_reconciliation() {
        val queued = QueuedExecutor()
        val guarded = AuthoritativePlaybackCoordinator(
            roomCode = "ABCD",
            streamUrl = "https://qmix.test/rooms/ABCD/current/stream",
            playbackEngine = engine,
            reconciler = reconciler,
            dispatcher = queued,
            advanceAfterEnded = { true },
        )
        guarded.onSynchronization(fresh(room(currentId = "one")))
        queued.runAll()
        guarded.onForegroundLost(1)
        queued.runAll()

        guarded.onForegroundReconciled(2, room(currentId = "two"))
        guarded.onForegroundLost(3)
        queued.runAll()
        guarded.onSynchronization(fresh(room(currentId = "three")))
        queued.runAll()

        assertEquals(listOf("one"), engine.prepared.map(PlaybackMedia::trackId))
        assertEquals(1, engine.playCount)
    }

    @Test
    fun fresh_foreground_reconciliation_replaces_changed_current_without_autoplay() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        coordinator.onForegroundLost()

        coordinator.onSynchronization(fresh(room(currentId = "stale")))
        coordinator.onForegroundReconciled(room(currentId = "two"))

        assertEquals(listOf("one", "two"), engine.prepared.map(PlaybackMedia::trackId))
        assertEquals(1, engine.playCount)
        assertEquals(2, engine.pauseCount)
        assertEquals("two", coordinator.state.trackId)
        assertEquals(LocalPlaybackStatus.PAUSED, coordinator.state.status)
    }

    @Test
    fun foreground_reconciliation_with_no_current_resets_to_idle_and_accepts_a_later_selection() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.READY, isPlaying = true))
        coordinator.onForegroundLost()

        coordinator.onForegroundReconciled(room(currentId = null))

        assertEquals(LocalPlaybackState(), coordinator.state)
        coordinator.onSynchronization(fresh(room(currentId = "two")))
        assertEquals(listOf("one", "two"), engine.prepared.map(PlaybackMedia::trackId))
        assertEquals(2, engine.playCount)
    }

    @Test
    fun foreground_reconciliation_ignores_a_room_with_the_wrong_code_until_the_expected_room_arrives() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        coordinator.onForegroundLost()

        coordinator.onForegroundReconciled(room(currentId = "other").copy(code = "OTHER"))
        coordinator.resume()

        assertEquals(1, engine.playCount)
        assertEquals(LocalPlaybackStatus.PAUSED, coordinator.state.status)
        coordinator.onForegroundReconciled(room(currentId = "one"))
        coordinator.resume()
        assertEquals(2, engine.playCount)
    }

    @Test
    fun a_fresh_room_without_a_current_track_waits_for_a_later_authoritative_selection() {
        coordinator.onSynchronization(fresh(room(currentId = null)))
        assertEquals(LocalPlaybackState(), coordinator.state)

        coordinator.onSynchronization(fresh(room(currentId = "one")))

        assertEquals(listOf("one"), engine.prepared.map(PlaybackMedia::trackId))
        assertEquals(1, engine.playCount)
    }

    @Test
    fun same_active_current_returns_paused_and_requires_an_explicit_resume() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.READY, isPlaying = true))
        coordinator.onForegroundLost()

        coordinator.onForegroundReconciled(room(currentId = "one"))

        assertEquals(LocalPlaybackStatus.PAUSED, coordinator.state.status)
        assertEquals(1, engine.playCount)
        coordinator.resume()
        assertEquals(2, engine.playCount)
        assertEquals(LocalPlaybackStatus.BUFFERING, coordinator.state.status)
    }

    @Test
    fun same_current_foreground_reconciliation_preserves_completed_and_error_states() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ENDED))
        coordinator.onForegroundLost()
        coordinator.onForegroundReconciled(room(currentId = "one"))
        assertEquals(LocalPlaybackStatus.COMPLETED, coordinator.state.status)

        coordinator.onSynchronization(fresh(room(currentId = "two")))
        engine.emit(
            PlaybackState(
                mediaId = "two",
                status = PlaybackStatus.ERROR,
                error = PlaybackError(PlaybackErrorKind.NETWORK, "offline"),
            ),
        )
        coordinator.onForegroundLost()
        coordinator.onForegroundReconciled(room(currentId = "two"))

        assertEquals(LocalPlaybackStatus.ERROR, coordinator.state.status)
        coordinator.retryCurrent()
        assertEquals(1, reconciler.calls.size)
    }

    @Test
    fun player_error_arriving_in_background_is_published_only_after_fresh_reconciliation() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        coordinator.onForegroundLost()
        val failure = PlaybackError(PlaybackErrorKind.DECODE, "bad stream")
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ERROR, error = failure))

        assertEquals(LocalPlaybackStatus.PAUSED, coordinator.state.status)
        coordinator.onForegroundReconciled(room(currentId = "one"))

        assertEquals(LocalPlaybackStatus.ERROR, coordinator.state.status)
        assertEquals(failure, coordinator.state.error)
    }

    @Test
    fun explicit_pause_is_sticky_across_same_current_snapshots() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))

        coordinator.pause()
        coordinator.onSynchronization(fresh(room(currentId = "one", queueIds = listOf("two"))))

        assertEquals(1, engine.pauseCount)
        assertEquals(1, engine.playCount)
        assertEquals(LocalPlaybackStatus.PAUSED, coordinator.state.status)
    }

    @Test
    fun final_track_ended_is_completed_and_later_queue_additions_do_not_start() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))

        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ENDED))
        coordinator.onSynchronization(fresh(room(currentId = "one", queueIds = listOf("two"))))
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.READY, isPlaying = true))

        assertEquals(LocalPlaybackStatus.COMPLETED, coordinator.state.status)
        assertEquals(1, engine.playCount)
        assertEquals(emptyList<String>(), advances)
    }

    @Test
    fun stream_error_is_recoverable_only_after_authoritative_retry_verification() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        val failure = PlaybackError(PlaybackErrorKind.NETWORK, "offline")
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ERROR, error = failure))

        assertEquals(LocalPlaybackStatus.ERROR, coordinator.state.status)
        assertEquals(failure, coordinator.state.error)
        coordinator.retryCurrent()
        assertEquals(1, reconciler.calls.size)
        assertEquals(1, engine.prepared.size)

        reconciler.complete(RoomFetchResult.Success(room(currentId = "one")))

        assertEquals(2, engine.prepared.size)
        assertEquals(2, engine.playCount)
    }

    @Test
    fun retry_rejects_a_success_response_for_another_room_and_remains_recoverable() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        val failure = PlaybackError(PlaybackErrorKind.NETWORK, "offline")
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ERROR, error = failure))
        coordinator.retryCurrent()

        reconciler.complete(RoomFetchResult.Success(room(currentId = "one").copy(code = "OTHER")))

        assertEquals(LocalPlaybackStatus.ERROR, coordinator.state.status)
        assertEquals(failure, coordinator.state.error)
        assertEquals(1, engine.prepared.size)
        coordinator.retryCurrent()
        assertEquals(2, reconciler.calls.size)
    }

    @Test
    fun media_play_pause_commands_preserve_recoverable_error_until_retry_or_next() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        val failure = PlaybackError(PlaybackErrorKind.NETWORK, "offline")
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ERROR, error = failure))
        val failedState = coordinator.state

        coordinator.pause()
        coordinator.resume()
        coordinator.togglePlayPause()

        assertEquals(failedState, coordinator.state)
        assertEquals(0, engine.pauseCount)
        assertEquals(1, engine.playCount)
        assertEquals(1, engine.prepared.size)
    }

    @Test
    fun dedicated_media_commands_only_pause_active_playback_and_resume_an_explicit_pause() {
        coordinator.pause()
        coordinator.resume()
        assertEquals(0, engine.pauseCount)
        assertEquals(0, engine.playCount)
        assertEquals(LocalPlaybackStatus.IDLE, coordinator.state.status)

        coordinator.onSynchronization(fresh(room(currentId = "one")))
        coordinator.resume()
        assertEquals(1, engine.playCount)
        assertEquals(LocalPlaybackStatus.BUFFERING, coordinator.state.status)

        coordinator.pause()
        coordinator.pause()
        assertEquals(1, engine.pauseCount)
        assertEquals(LocalPlaybackStatus.PAUSED, coordinator.state.status)

        coordinator.resume()
        coordinator.resume()
        assertEquals(2, engine.playCount)
        assertEquals(LocalPlaybackStatus.BUFFERING, coordinator.state.status)

        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.READY, isPlaying = true))
        coordinator.resume()
        assertEquals(2, engine.playCount)
        coordinator.pause()
        assertEquals(2, engine.pauseCount)
        assertEquals(LocalPlaybackStatus.PAUSED, coordinator.state.status)

        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ENDED))
        coordinator.pause()
        coordinator.resume()
        coordinator.togglePlayPause()
        assertEquals(2, engine.pauseCount)
        assertEquals(2, engine.playCount)
        assertEquals(LocalPlaybackStatus.COMPLETED, coordinator.state.status)
    }

    @Test
    fun stale_and_reconnecting_snapshots_never_replace_or_autoplay() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))

        coordinator.onSynchronization(
            fresh(room(currentId = "two")).copy(freshness = Freshness.STALE),
        )
        coordinator.onSynchronization(
            fresh(room(currentId = "three")).copy(connection = LiveConnection.RECONNECTING),
        )

        assertEquals(listOf("one"), engine.prepared.map(PlaybackMedia::trackId))
        assertEquals(1, engine.playCount)
    }

    @Test
    fun ended_with_a_nonempty_queue_delegates_one_eligible_advance() {
        coordinator.onSynchronization(fresh(room(currentId = "one", queueIds = listOf("two"))))

        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ENDED))
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ENDED))

        assertEquals(listOf("one"), advances)
    }

    @Test
    fun replacement_rejects_late_callbacks_from_the_previous_track() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        coordinator.onSynchronization(fresh(room(currentId = "two")))

        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ENDED))

        assertEquals("two", coordinator.state.trackId)
        assertEquals(LocalPlaybackStatus.BUFFERING, coordinator.state.status)
        assertTrue(advances.isEmpty())
    }

    @Test
    fun retry_callback_cannot_reprepare_after_a_fresh_replacement() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.ERROR,
                error = PlaybackError(PlaybackErrorKind.HTTP, "unavailable", 502),
            ),
        )
        coordinator.retryCurrent()

        coordinator.onSynchronization(fresh(room(currentId = "two")))
        reconciler.complete(RoomFetchResult.Success(room(currentId = "one")))

        assertEquals(listOf("one", "two"), engine.prepared.map(PlaybackMedia::trackId))
        assertEquals(2, engine.playCount)
    }

    @Test
    fun play_pause_toggle_follows_the_actual_local_state() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))

        coordinator.togglePlayPause()
        assertEquals(1, engine.pauseCount)
        assertEquals(LocalPlaybackStatus.PAUSED, coordinator.state.status)

        coordinator.togglePlayPause()
        assertEquals(2, engine.playCount)
        assertEquals(LocalPlaybackStatus.BUFFERING, coordinator.state.status)

        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.READY, isPlaying = true))
        coordinator.togglePlayPause()
        assertEquals(2, engine.pauseCount)
        assertEquals(LocalPlaybackStatus.PAUSED, coordinator.state.status)
    }

    @Test
    fun relative_seek_uses_the_current_timeline_and_clamps_to_both_bounds() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.READY,
                isPlaying = true,
                positionMs = 5_000,
                durationMs = 12_000,
                isSeekable = true,
            ),
        )

        coordinator.seekBy(-10_000)
        coordinator.seekBy(10_000)

        assertEquals(listOf(0L, 12_000L), engine.seeks)
    }

    @Test
    fun relative_seek_moves_within_the_timeline_in_both_directions() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.READY,
                isPlaying = true,
                positionMs = 5_000,
                durationMs = 12_000,
                isSeekable = true,
            ),
        )

        coordinator.seekBy(2_000)
        coordinator.seekBy(-2_000)

        assertEquals(listOf(7_000L, 3_000L), engine.seeks)
    }

    @Test
    fun relative_seek_is_ignored_without_a_seekable_known_timeline() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.READY,
                positionMs = 5_000,
                durationMs = null,
                isSeekable = true,
            ),
        )
        coordinator.seekBy(10_000)
        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.READY,
                positionMs = 5_000,
                durationMs = 12_000,
                isSeekable = false,
            ),
        )
        coordinator.seekBy(-10_000)

        assertTrue(engine.seeks.isEmpty())
    }

    @Test
    fun pause_can_resume_but_completed_track_cannot_replay() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        coordinator.pause()
        coordinator.resume()
        assertEquals(2, engine.playCount)

        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ENDED))
        coordinator.resume()
        coordinator.pause()

        assertEquals(2, engine.playCount)
        assertEquals(1, engine.pauseCount)
        assertEquals(LocalPlaybackStatus.COMPLETED, coordinator.state.status)
    }

    @Test
    fun close_cancels_retry_and_ignores_late_results_and_player_events() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.ERROR,
                error = PlaybackError(PlaybackErrorKind.NETWORK, "offline"),
            ),
        )
        coordinator.retryCurrent()

        coordinator.close()
        coordinator.close()
        reconciler.complete(RoomFetchResult.Success(room(currentId = "one")))
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.READY, isPlaying = true))

        assertTrue(reconciler.calls.single().canceled)
        assertEquals(1, engine.prepared.size)
        assertEquals(1, engine.pauseCount)
        assertEquals(LocalPlaybackStatus.ERROR, coordinator.state.status)
        assertFalse(engine.hasListeners)
    }

    @Test
    fun retry_that_discovers_a_replacement_plays_only_the_authoritative_replacement() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.ERROR,
                error = PlaybackError(PlaybackErrorKind.DECODE, "bad stream"),
            ),
        )
        coordinator.retryCurrent()

        reconciler.complete(RoomFetchResult.Success(room(currentId = "two")))

        assertEquals(listOf("one", "two"), engine.prepared.map(PlaybackMedia::trackId))
        assertEquals("two", coordinator.state.trackId)
    }

    @Test
    fun failed_retry_stays_recoverable_and_can_reconcile_again() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))
        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.ERROR,
                error = PlaybackError(PlaybackErrorKind.NETWORK, "offline"),
            ),
        )

        coordinator.retryCurrent()
        reconciler.complete(RoomFetchResult.Failure)
        coordinator.retryCurrent()
        reconciler.complete(RoomFetchResult.Missing)

        assertEquals(2, reconciler.calls.size)
        assertEquals(LocalPlaybackStatus.ERROR, coordinator.state.status)
        assertEquals(1, engine.prepared.size)
    }

    @Test
    fun player_snapshots_publish_playing_timeline_for_the_current_track() {
        coordinator.onSynchronization(fresh(room(currentId = "one")))

        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.READY,
                isPlaying = true,
                positionMs = 125,
                durationMs = 1_000,
                isSeekable = true,
            ),
        )

        assertEquals(
            LocalPlaybackState(
                trackId = "one",
                status = LocalPlaybackStatus.PLAYING,
                isPlaying = true,
                positionMs = 125,
                durationMs = 1_000,
                isSeekable = true,
            ),
            coordinator.state,
        )
    }

    @Test
    fun irrelevant_room_states_cannot_select_media() {
        coordinator.onSynchronization(RoomSyncState.Missing("ABCD"))
        coordinator.onSynchronization(
            RoomSyncState.Active("ABCD", null, Freshness.FRESH, LiveConnection.CONNECTED),
        )
        coordinator.onSynchronization(
            RoomSyncState.Active("OTHER", room(currentId = "one"), Freshness.FRESH, LiveConnection.CONNECTED),
        )

        assertTrue(engine.prepared.isEmpty())
        assertEquals(LocalPlaybackStatus.IDLE, coordinator.state.status)
    }

    private fun fresh(room: RoomState) = RoomSyncState.Active(
        roomCode = room.code,
        room = room,
        freshness = Freshness.FRESH,
        connection = LiveConnection.CONNECTED,
    )

    private fun room(currentId: String?, queueIds: List<String> = emptyList()) = RoomState(
        code = "ABCD",
        current = currentId?.let { CurrentTrack(it, 0, "playing", it, "Artist") },
        queue = queueIds.map { QueuedTrack(it, "https://example/$it", it, "Artist", 60, "fixture") },
    )

    private class RecordingPlaybackEngine : PlaybackEngine {
        override var state = PlaybackState()
            private set
        val prepared = mutableListOf<PlaybackMedia>()
        var playCount = 0
        var pauseCount = 0
        val seeks = mutableListOf<Long>()
        private val listeners = linkedSetOf<(PlaybackState) -> Unit>()
        val hasListeners: Boolean get() = listeners.isNotEmpty()

        override fun prepare(media: PlaybackMedia) {
            prepared += media
            publish(PlaybackState(mediaId = media.trackId, status = PlaybackStatus.BUFFERING))
        }

        override fun play() { playCount++ }
        override fun pause() { pauseCount++ }
        override fun seekTo(positionMs: Long) { seeks += positionMs }
        override fun release() = Unit
        override fun addListener(listener: (PlaybackState) -> Unit) { listeners += listener }
        override fun removeListener(listener: (PlaybackState) -> Unit) { listeners -= listener }

        fun emit(next: PlaybackState) = publish(next)

        private fun publish(next: PlaybackState) {
            state = next
            listeners.toList().forEach { it(next) }
        }
    }

    private class QueuedExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            commands.addLast(command)
        }

        fun runAll() {
            while (commands.isNotEmpty()) commands.removeFirst().run()
        }
    }

    private class RecordingRoomFetcher : RoomStateFetcher {
        data class Call(
            val roomCode: String,
            val callback: (RoomFetchResult) -> Unit,
            var canceled: Boolean = false,
        )
        val calls = mutableListOf<Call>()

        override fun fetch(roomCode: String, callback: (RoomFetchResult) -> Unit): Cancelable {
            val call = Call(roomCode, callback)
            calls += call
            return Cancelable { call.canceled = true }
        }

        fun complete(result: RoomFetchResult, index: Int = calls.lastIndex) {
            calls[index].callback(result)
        }
    }
}
