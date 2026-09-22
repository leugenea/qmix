package com.qmix.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** qmix#181: a cancelled retry cannot return authority to an obsolete selection. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoroutineOwnershipTest {
    @Test
    fun suspended_retry_is_cancelled_by_replacement() = runTest {
        val deferred = CompletableDeferred<RoomFetchResult>()
        val engine = object : PlaybackEngine {
            override var state = PlaybackState()
            val prepared = mutableListOf<String>()
            private val listeners = mutableListOf<(PlaybackState) -> Unit>()
            fun emit(next: PlaybackState) { state = next; listeners.forEach { it(next) } }
            override fun prepare(media: PlaybackMedia) { prepared += media.trackId }
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(positionMs: Long) = Unit
            override fun release() = Unit
            override fun addListener(listener: (PlaybackState) -> Unit) { listeners += listener }
            override fun removeListener(listener: (PlaybackState) -> Unit) { listeners -= listener }
        }
        var fetches = 0
        var canceled = false
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = AuthoritativePlaybackCoordinator(
            roomCode = "ABCD", streamUrl = "https://qmix.test/stream", playbackEngine = engine,
            reconciler = { fetches++; try { deferred.await() } finally { canceled = true } }, parentScope = backgroundScope,
            mutationContext = QueueMutationContext(dispatcher) { true },
            advanceAfterEnded = { true },
        )
        fun room(id: String) = RoomState("ABCD", CurrentTrack(id, 0, "playing", id, "Artist"), emptyList())
        fun sync(id: String) = RoomSyncState.Active("ABCD", room(id), Freshness.FRESH, LiveConnection.CONNECTED)
        coordinator.onSynchronization(sync("one"))
        runCurrent()
        engine.emit(PlaybackState("one", PlaybackStatus.ERROR))
        runCurrent()
        coordinator.retryCurrent()
        runCurrent()
        assertEquals(1, fetches)
        coordinator.onSynchronization(sync("two"))
        runCurrent()
        assertEquals(true, canceled)
        assertEquals(listOf("one", "two"), engine.prepared)
        coordinator.close()
        runCurrent()
    }
}
