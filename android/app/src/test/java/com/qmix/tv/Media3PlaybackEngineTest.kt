package com.qmix.tv

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PlaybackEngineTest {
    private val backend = FakePlayerBackend()
    private val engine = Media3PlaybackEngine(backend)
    private val media = PlaybackMedia("track-1", "https://qmix.test/current/stream")

    @Test
    fun prepare_sets_media_identity_and_starts_buffering() {
        engine.prepare(media)

        assertEquals(listOf(media), backend.media)
        assertEquals(1, backend.prepareCount)
        assertEquals("track-1", engine.state.mediaId)
        assertEquals(PlaybackStatus.BUFFERING, engine.state.status)
    }

    @Test
    fun repeated_identical_healthy_track_does_not_prepare_again() {
        engine.prepare(media)
        backend.emit(PlayerBackend.Event.Ready(snapshot(durationMs = 1_000, isSeekable = true)))
        engine.prepare(media)

        assertEquals(1, backend.prepareCount)
        assertEquals(PlaybackStatus.READY, engine.state.status)
    }

    @Test
    fun changed_url_for_same_track_replaces_media() {
        engine.prepare(media)
        val refreshed = media.copy(streamUrl = "https://qmix.test/current/stream?token=new")
        engine.prepare(refreshed)

        assertEquals(listOf(media, refreshed), backend.media)
        assertEquals(2, backend.prepareCount)
    }

    @Test
    fun same_track_can_retry_after_error_without_url_change() {
        engine.prepare(media)
        backend.emit(PlayerBackend.Event.Failed(BackendFailure.Network("offline")))
        engine.prepare(media)

        assertEquals(listOf(media, media), backend.media)
        assertEquals(2, backend.prepareCount)
        assertEquals(PlaybackStatus.BUFFERING, engine.state.status)
        assertNull(engine.state.error)
    }

    @Test
    fun different_track_at_same_url_replaces_media_and_prepares() {
        engine.prepare(media)
        engine.prepare(media.copy(trackId = "track-2"))

        assertEquals(listOf(media, media.copy(trackId = "track-2")), backend.media)
        assertEquals(2, backend.prepareCount)
    }

    @Test
    fun backend_snapshots_are_cached_and_safe_to_read_without_player_access() {
        engine.prepare(media)
        backend.emit(PlayerBackend.Event.Ready(snapshot(positionMs = 120, durationMs = 1_000, isSeekable = true)))
        backend.emit(PlayerBackend.Event.StateChanged(snapshot(positionMs = 240, durationMs = 1_000, isSeekable = true, isPlaying = true)))

        repeat(10) {
            assertEquals(240, engine.state.positionMs)
            assertEquals(1_000L, engine.state.durationMs)
            assertTrue(engine.state.isSeekable)
            assertTrue(engine.state.isPlaying)
        }
    }

    @Test
    fun player_events_publish_buffering_ready_playing_and_ended() {
        val observed = mutableListOf<PlaybackState>()
        engine.addListener(observed::add)
        engine.prepare(media)
        backend.emit(PlayerBackend.Event.Buffering(snapshot()))
        backend.emit(PlayerBackend.Event.Ready(snapshot(positionMs = 120, durationMs = 1_000, isSeekable = true)))
        backend.emit(PlayerBackend.Event.StateChanged(snapshot(positionMs = 130, durationMs = 1_000, isSeekable = true, isPlaying = true)))
        backend.emit(PlayerBackend.Event.Ended(snapshot(positionMs = 1_000, durationMs = 1_000, isSeekable = true)))

        assertEquals(
            listOf(
                PlaybackStatus.BUFFERING,
                PlaybackStatus.BUFFERING,
                PlaybackStatus.READY,
                PlaybackStatus.READY,
                PlaybackStatus.ENDED,
            ),
            observed.map { it.status },
        )
        assertTrue(observed[2].isSeekable)
        assertEquals(120, observed[2].positionMs)
        assertTrue(observed[3].isPlaying)
        assertFalse(observed.last().isPlaying)
    }

    @Test
    fun play_pause_delegate() {
        engine.prepare(media)
        engine.play()
        engine.pause()

        assertEquals(1, backend.playCount)
        assertEquals(1, backend.pauseCount)
    }

    @Test
    fun seek_uses_cached_timeline_and_clamps_position() {
        engine.prepare(media)
        engine.seekTo(500)
        backend.emit(PlayerBackend.Event.Ready(snapshot(durationMs = 1_000, isSeekable = true)))
        engine.seekTo(1_500)

        assertEquals(listOf(1_000L), backend.seeks)
    }

    @Test
    fun unavailable_duration_disables_seek() {
        engine.prepare(media)
        backend.emit(PlayerBackend.Event.Ready(snapshot(durationMs = null, isSeekable = true)))
        engine.seekTo(100)

        assertTrue(backend.seeks.isEmpty())
        assertFalse(engine.state.isSeekable)
        assertNull(engine.state.durationMs)
    }

    @Test
    fun http_response_code_is_structured_and_other_failures_are_typed() {
        engine.prepare(media)
        backend.emit(PlayerBackend.Event.Failed(BackendFailure.Http(502, "bad gateway")))

        assertEquals(PlaybackStatus.ERROR, engine.state.status)
        assertEquals(PlaybackErrorKind.HTTP, engine.state.error?.kind)
        assertEquals(502, engine.state.error?.httpResponseCode)

        backend.emit(PlayerBackend.Event.Failed(BackendFailure.Range("range rejected", responseCode = 416)))
        assertEquals(PlaybackErrorKind.RANGE, engine.state.error?.kind)
        assertEquals(416, engine.state.error?.httpResponseCode)

        listOf(
            BackendFailure.Decode("bad audio") to PlaybackErrorKind.DECODE,
            BackendFailure.Network("disconnected") to PlaybackErrorKind.NETWORK,
        ).forEach { (failure, expected) ->
            backend.emit(PlayerBackend.Event.Failed(failure))
            assertEquals(expected, engine.state.error?.kind)
            assertNull(engine.state.error?.httpResponseCode)
        }
    }

    @Test
    fun stale_callbacks_after_media_switch_are_ignored() {
        engine.prepare(media)
        val stale = backend.listeners.single()
        engine.prepare(media.copy(trackId = "track-2"))
        stale(PlayerBackend.Event.Ended(snapshot()))

        assertEquals("track-2", engine.state.mediaId)
        assertEquals(PlaybackStatus.BUFFERING, engine.state.status)
    }

    @Test
    fun synchronous_set_media_callback_merges_into_new_buffering_track() {
        engine.prepare(media)
        backend.emit(PlayerBackend.Event.Ready(snapshot(durationMs = 1_000, isSeekable = true)))
        val observed = mutableListOf<PlaybackState>()
        engine.addListener(observed::add)
        backend.setMediaEvent = PlayerBackend.Event.Ready(
            snapshot(positionMs = 25, durationMs = 2_000, isSeekable = true),
        )

        engine.prepare(media.copy(trackId = "track-2"))

        assertEquals(listOf("track-2", "track-2"), observed.map { it.mediaId })
        assertEquals(listOf(PlaybackStatus.BUFFERING, PlaybackStatus.READY), observed.map { it.status })
        assertEquals(25, engine.state.positionMs)
    }

    @Test
    fun synchronous_prepare_callback_merges_into_new_buffering_track() {
        engine.prepare(media)
        backend.emit(PlayerBackend.Event.Ready(snapshot(durationMs = 1_000, isSeekable = true)))
        val observed = mutableListOf<PlaybackState>()
        engine.addListener(observed::add)
        backend.prepareEvent = PlayerBackend.Event.Buffering(
            snapshot(positionMs = 10, durationMs = 2_000, isSeekable = true),
        )

        engine.prepare(media.copy(trackId = "track-2"))

        assertEquals(listOf("track-2", "track-2"), observed.map { it.mediaId })
        assertEquals(listOf(PlaybackStatus.BUFFERING, PlaybackStatus.BUFFERING), observed.map { it.status })
        assertEquals(10, engine.state.positionMs)
    }

    @Test
    fun release_is_idempotent_and_ignores_all_late_callbacks() {
        engine.prepare(media)
        val stale = backend.listeners.single()
        engine.release()
        engine.release()
        stale(PlayerBackend.Event.Ready(snapshot()))

        assertEquals(1, backend.releaseCount)
        assertEquals(PlaybackStatus.RELEASED, engine.state.status)
    }

    @Test
    fun media_audio_focus_is_delegated_to_media3_with_handling_enabled() {
        var appliedAttributes: androidx.media3.common.AudioAttributes? = null
        var handlesAudioFocus = false

        configureMediaAudioFocus(
            MediaAudioFocusTarget { attributes, handleAudioFocus ->
                appliedAttributes = attributes
                handlesAudioFocus = handleAudioFocus
            },
            enabled = true,
        )

        assertEquals(C.USAGE_MEDIA, appliedAttributes?.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, appliedAttributes?.contentType)
        assertTrue(handlesAudioFocus)
        assertTrue(backend.audioFocusEnabled)
    }

    @Test
    fun host_stop_pauses_and_host_destroy_releases() {
        engine.prepare(media)
        engine.play()
        engine.onHostStop()
        engine.onHostDestroy()

        assertEquals(1, backend.pauseCount)
        assertEquals(1, backend.releaseCount)
    }

    private fun snapshot(
        positionMs: Long = 0,
        durationMs: Long? = null,
        isSeekable: Boolean = false,
        isPlaying: Boolean = false,
    ) = PlayerBackend.Snapshot(positionMs, durationMs, isSeekable, isPlaying)

    private class FakePlayerBackend : PlayerBackend {
        val media = mutableListOf<PlaybackMedia>()
        val listeners = mutableListOf<(PlayerBackend.Event) -> Unit>()
        var prepareCount = 0
        var playCount = 0
        var pauseCount = 0
        var releaseCount = 0
        var audioFocusEnabled = false
        var setMediaEvent: PlayerBackend.Event? = null
        var prepareEvent: PlayerBackend.Event? = null
        val seeks = mutableListOf<Long>()

        override fun configureAudioFocus(enabled: Boolean) { audioFocusEnabled = enabled }
        override fun setMedia(media: PlaybackMedia) {
            this.media += media
            setMediaEvent?.let(::emit)
        }
        override fun setListener(listener: (PlayerBackend.Event) -> Unit) { listeners += listener }
        override fun prepare() {
            prepareCount++
            prepareEvent?.let(::emit)
        }
        override fun play() { playCount++ }
        override fun pause() { pauseCount++ }
        override fun seekTo(positionMs: Long) { seeks += positionMs }
        override fun release() { releaseCount++ }
        fun emit(event: PlayerBackend.Event) = listeners.last()(event)
    }
}
