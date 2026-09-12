package com.qmix.tv

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackLifecycleObserverTest {
    @Test
    fun stop_pauses_and_destroy_releases_resources() {
        val engine = RecordingEngine()
        val owner = TestOwner()
        val observer = PlaybackLifecycleObserver(engine)
        owner.lifecycle.addObserver(observer)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        assertEquals(1, engine.pauseCount)
        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun factory_registers_lifecycle_release_for_its_backend() {
        val backend = RecordingBackend()
        val owner = TestOwner()
        PlaybackEngines.create(backend, owner)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        assertEquals(1, backend.pauseCount)
        assertEquals(1, backend.releaseCount)
    }

    private class RecordingBackend : PlayerBackend {
        var pauseCount = 0
        var releaseCount = 0
        override fun configureAudioFocus(enabled: Boolean) = Unit
        override fun setMedia(media: PlaybackMedia) = Unit
        override fun setListener(listener: (PlayerBackend.Event) -> Unit) = Unit
        override fun prepare() = Unit
        override fun play() = Unit
        override fun pause() { pauseCount++ }
        override fun seekTo(positionMs: Long) = Unit
        override fun release() { releaseCount++ }
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class RecordingEngine : PlaybackEngine {
        override val state = PlaybackState()
        var pauseCount = 0
        var releaseCount = 0
        override fun prepare(media: PlaybackMedia) = Unit
        override fun play() = Unit
        override fun pause() { pauseCount++ }
        override fun seekTo(positionMs: Long) = Unit
        override fun release() { releaseCount++ }
        override fun addListener(listener: (PlaybackState) -> Unit) = Unit
        override fun removeListener(listener: (PlaybackState) -> Unit) = Unit
    }
}
