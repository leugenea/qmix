package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

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
        private val listeners = linkedSetOf<(PlaybackState) -> Unit>()
        val hasListeners: Boolean get() = listeners.isNotEmpty()

        override fun prepare(media: PlaybackMedia) {
            prepared += media
            publish(PlaybackState(mediaId = media.trackId, status = PlaybackStatus.BUFFERING))
        }

        override fun play() { playCount++ }
        override fun pause() { pauseCount++ }
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
        override fun addListener(listener: (PlaybackState) -> Unit) { listeners += listener }
        override fun removeListener(listener: (PlaybackState) -> Unit) { listeners -= listener }

        fun emit(next: PlaybackState) = publish(next)

        private fun publish(next: PlaybackState) {
            state = next
            listeners.toList().forEach { it(next) }
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
