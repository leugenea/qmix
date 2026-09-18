package com.qmix.tv

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentationTest {
    @Test
    fun activity_uses_its_on_create_controller_for_framework_media_key_dispatch() {
        val server = MockWebServer()
        var controller: HostSessionController? = null
        var providerLease: AutoCloseable? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""",
                ),
            )
            val repository = RecordingRepository()
            val playback = RecordingPlaybackEngine()
            val createdController = createController(server, repository, playback)
            controller = createdController
            val application = ApplicationProvider.getApplicationContext<QMixApplication>()
            providerLease = application.installActivityHostSessionProvider { createdController }

            assertTrue(createdController.createRoom())
            createdController.enterRoom()
            repository.publish(
                RoomSyncState.Active(
                    "ABCD",
                    RoomState(
                        "ABCD",
                        CurrentTrack("current", 0, "playing", "Current", "Artist"),
                        emptyList(),
                    ),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            )
            playback.emit(playingState())

            scenario = ActivityScenario.launch(MainActivity::class.java)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.waitForIdle()
            assertTrue(
                "MainActivity did not bind the injected onCreate controller to Compose",
                device.hasObject(By.text("Room ABCD")),
            )

            scenario.onActivity { activity ->
                createdController.pausePlayback()
                assertInitialDownDispatchesOnce(
                    activity,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    playback.playCount,
                ) { playback.playCount }
                createdController.pausePlayback()
                assertRepeatDownAndUpAreConsumedWithoutRedispatch(
                    activity,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    playback.playCount,
                ) { playback.playCount }

                createdController.resumePlayback()
                playback.emit(playingState())
                assertInitialDownDispatchesOnce(
                    activity,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    playback.pauseCount,
                ) { playback.pauseCount }
                createdController.resumePlayback()
                playback.emit(playingState())
                assertRepeatDownAndUpAreConsumedWithoutRedispatch(
                    activity,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    playback.pauseCount,
                ) { playback.pauseCount }

                createdController.resumePlayback()
                playback.emit(playingState())
                assertInitialDownDispatchesOnce(
                    activity,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    playback.pauseCount,
                ) { playback.pauseCount }
                createdController.resumePlayback()
                playback.emit(playingState())
                assertRepeatDownAndUpAreConsumedWithoutRedispatch(
                    activity,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    playback.pauseCount,
                ) { playback.pauseCount }

                listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT).forEach { keyCode ->
                    assertDispatchDoesNotControlPlayback(
                        activity,
                        KeyEvent(KeyEvent.ACTION_DOWN, keyCode),
                        playback,
                    )
                    assertDispatchDoesNotControlPlayback(
                        activity,
                        KeyEvent(KeyEvent.ACTION_UP, keyCode),
                        playback,
                    )
                }
            }
        } finally {
            try {
                scenario?.close()
            } finally {
                try {
                    controller?.endRoom()
                } finally {
                    try {
                        providerLease?.close()
                    } finally {
                        server.shutdown()
                    }
                }
            }
        }
    }

    private fun createController(
        server: MockWebServer,
        repository: RecordingRepository,
        playback: RecordingPlaybackEngine,
    ) = HostSessionController(
        httpClient = OkHttpClient(),
        initialBackendUrl = server.url("/").toString(),
        initialGuestOrigin = "https://guest.example",
        executor = Executor { it.run() },
        roomRepositoryFactory = { repository },
        playbackCoordinatorFactory = { backendUrl, credentials, observer, advanceAfterEnded ->
            AuthoritativePlaybackCoordinator(
                roomCode = credentials.code,
                streamUrl = "${backendUrl.trimEnd('/')}/rooms/${credentials.code}/current/stream",
                playbackEngine = playback,
                reconciler = RoomStateFetcher { _, _ -> Cancelable { } },
                dispatcher = Executor { it.run() },
                advanceAfterEnded = advanceAfterEnded,
                observer = observer,
            )
        },
    )

    private fun assertInitialDownDispatchesOnce(
        activity: MainActivity,
        keyCode: Int,
        before: Int,
        actualActions: () -> Int,
    ) {
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode)))
        assertEquals(before + 1, actualActions())
    }

    private fun assertRepeatDownAndUpAreConsumedWithoutRedispatch(
        activity: MainActivity,
        keyCode: Int,
        expectedActions: Int,
        actualActions: () -> Int,
    ) {
        assertTrue(activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 1)))
        assertEquals(expectedActions, actualActions())
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode)))
        assertEquals(expectedActions, actualActions())
    }

    private fun assertDispatchDoesNotControlPlayback(
        activity: MainActivity,
        event: KeyEvent,
        playback: RecordingPlaybackEngine,
    ) {
        val playCount = playback.playCount
        val pauseCount = playback.pauseCount

        activity.dispatchKeyEvent(event)

        assertEquals(playCount, playback.playCount)
        assertEquals(pauseCount, playback.pauseCount)
    }

    private fun playingState() = PlaybackState(
        mediaId = "current",
        status = PlaybackStatus.READY,
        isPlaying = true,
        positionMs = 20_000,
        durationMs = 60_000,
        isSeekable = true,
    )

    private class RecordingRepository : RoomRepository {
        private var observer: ((RoomSyncState) -> Unit)? = null

        override fun observe(roomCode: String, onUpdate: (RoomSyncState) -> Unit): AutoCloseable {
            observer = onUpdate
            return AutoCloseable { observer = null }
        }

        fun publish(state: RoomSyncState) {
            observer?.invoke(state)
        }
    }

    private class RecordingPlaybackEngine : PlaybackEngine {
        override var state = PlaybackState()
            private set
        var playCount = 0
        var pauseCount = 0
        private val listeners = linkedSetOf<(PlaybackState) -> Unit>()

        override fun prepare(media: PlaybackMedia) {
            state = PlaybackState(media.trackId, PlaybackStatus.BUFFERING)
            listeners.toList().forEach { it(state) }
        }

        override fun play() { playCount++ }
        override fun pause() { pauseCount++ }
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
        override fun addListener(listener: (PlaybackState) -> Unit) { listeners += listener }
        override fun removeListener(listener: (PlaybackState) -> Unit) { listeners -= listener }

        fun emit(next: PlaybackState) {
            state = next
            listeners.toList().forEach { it(next) }
        }
    }
}
