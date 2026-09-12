package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackEngineContractTest {
    @Test
    fun prepare_exposes_buffering_for_track_identity() {
        val engine = FakePlaybackEngine()

        engine.prepare(PlaybackMedia("track-1", "https://qmix.test/current/stream"))

        assertEquals("track-1", engine.state.mediaId)
        assertEquals(PlaybackStatus.BUFFERING, engine.state.status)
    }

    private class FakePlaybackEngine : PlaybackEngine {
        override var state = PlaybackState()
            private set

        override fun prepare(media: PlaybackMedia) {
            state = state.copy(mediaId = media.trackId, status = PlaybackStatus.BUFFERING)
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
        override fun addListener(listener: (PlaybackState) -> Unit) = Unit
        override fun removeListener(listener: (PlaybackState) -> Unit) = Unit
    }
}
