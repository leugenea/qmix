package com.qmix.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExoPlayerBackendThreadContractTest {
    private val executor = Executors.newSingleThreadExecutor()
    private var engine: PlaybackEngine? = null

    @After
    fun tearDown() {
        engine?.release()
        executor.shutdownNow()
    }

    @Test
    fun real_factory_rejects_creation_off_main_thread() {
        val failure = assertThrows(ExecutionException::class.java) {
            executor.submit<PlaybackEngine> {
                PlaybackEngines.create(ApplicationProvider.getApplicationContext<Context>())
            }.get()
        }

        assertEquals(IllegalStateException::class.java, failure.cause?.javaClass)
        assertEquals(MAIN_THREAD_ERROR, failure.cause?.message)
    }

    @Test
    fun playback_boundary_rejects_every_mutation_off_main_thread_without_partial_release() {
        val playback = PlaybackEngines.create(ApplicationProvider.getApplicationContext<Context>())
            .also { engine = it }

        val operations = listOf<() -> Unit>(
            { playback.prepare(PlaybackMedia("track", "https://qmix.test/stream")) },
            { playback.play() },
            { playback.pause() },
            { playback.seekTo(1) },
            { playback.addListener { } },
            { playback.removeListener { } },
            { playback.release() },
        )
        operations.forEach { operation ->
            val failure = assertThrows(ExecutionException::class.java) {
                executor.submit(operation).get()
            }
            assertEquals(IllegalStateException::class.java, failure.cause?.javaClass)
            assertEquals(MAIN_THREAD_ERROR, failure.cause?.message)
        }

        playback.release()
        assertTrue(playback.state.status == PlaybackStatus.RELEASED)
        engine = null
    }

    private companion object {
        const val MAIN_THREAD_ERROR = "Playback must be created and operated on the main thread"
    }
}
