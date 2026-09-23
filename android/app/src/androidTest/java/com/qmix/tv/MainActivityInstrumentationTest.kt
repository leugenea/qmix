package com.qmix.tv

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentationTest {
    private val roomScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun cancelRoomScope() {
        roomScope.cancel()
    }

    @Test
    fun recreated_activity_and_replacement_session_restore_endpoints_from_application_storage() {
        val application = ApplicationProvider.getApplicationContext<QMixApplication>()
        application.getSharedPreferences(EndpointSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        EndpointSettingsStore(application).save(
            EndpointSettings("https://api.example", "https://guest.example"),
        )
        try {
            val firstController = HostSessionController(
                OkHttpClient(),
                settingsPersistence = EndpointSettingsStore(application),
            )
            val firstLease = application.installActivityHostSessionProvider { firstController }
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    assertSetupEndpoints()
                    scenario.recreate()
                    assertSetupEndpoints()
                }
            } finally {
                firstLease.close()
            }

            val replacementController = HostSessionController(
                OkHttpClient(),
                settingsPersistence = EndpointSettingsStore(application),
            )
            val replacementLease = application.installActivityHostSessionProvider { replacementController }
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    assertSetupEndpoints()
                }
            } finally {
                replacementLease.close()
            }
        } finally {
            application.getSharedPreferences(EndpointSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    /** qmix#182: stopped collectors resume at the latest application-owned state. */
    @Test
    fun stopped_activity_collects_latest_room_and_recreation_keeps_the_same_session() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""",
        ))
        val application = ApplicationProvider.getApplicationContext<QMixApplication>()
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomCollectionScope = roomScope,
            queueMutationContext = QueueMutationContext(Dispatchers.Main.immediate) {
                android.os.Looper.myLooper() === android.os.Looper.getMainLooper()
            },
        )
        val lease = application.installActivityHostSessionProvider { controller }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertTrue(controller.createRoom())
                controller.awaitCreatedForTest()
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                controller.enterRoom()
                assertTrue(controller.state is HostingState.LiveRoom)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val title = application.getString(R.string.room_title, "ABCD")
                assertTrue(device.wait(Until.hasObject(By.text(title)), 10_000))
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue((activity.application as QMixApplication).hostSessionForActivity() === controller)
                }
                assertTrue(device.wait(Until.hasObject(By.text(title)), 10_000))
                assertTrue(controller.state is HostingState.LiveRoom)
            }
        } finally {
            controller.endRoom()
            lease.close()
            server.shutdown()
        }
    }

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
            createdController.awaitCreatedForTest()
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

    @Test
    fun home_and_return_pause_then_require_fresh_reconciliation_without_autoplay() {
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
            val recovery = AtomicReference<(RoomFetchResult) -> Unit>()
            val createdController = createController(
                server,
                repository,
                playback,
                foregroundFetcher = TestRoomFetcher { _, callback ->
                    recovery.set(callback)
                    Cancelable { recovery.compareAndSet(callback, null) }
                },
            )
            controller = createdController
            val application = ApplicationProvider.getApplicationContext<QMixApplication>()
            providerLease = application.installActivityHostSessionProvider { createdController }
            assertTrue(createdController.createRoom())
            createdController.awaitCreatedForTest()
            createdController.enterRoom()
            val initial = RoomState(
                "ABCD",
                CurrentTrack("current", 0, "playing", "Current", "Artist"),
                listOf(QueuedTrack("next", "url", "Next", "Artist", 4, "fixture")),
            )
            repository.publish(RoomSyncState.Active("ABCD", initial, Freshness.FRESH, LiveConnection.CONNECTED))
            playback.emit(playingState())
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val device = UiDevice.getInstance(instrumentation)

            device.pressHome()
            device.waitForIdle()
            assertEquals(1, playback.pauseCount)
            assertTrue((createdController.state as HostingState.LiveRoom).foregroundRecoveryPending)

            val targetContext = instrumentation.targetContext
            val launchIntent = checkNotNull(
                targetContext.packageManager.getLeanbackLaunchIntentForPackage(targetContext.packageName),
            )
            // ActivityScenario starts a standard-mode activity in its own task. A bare
            // launcher NEW_TASK intent would add a second MainActivity to that task,
            // so explicitly select the existing instance the user left behind.
            targetContext.startActivity(
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
            val expectedRoomTitle = targetContext.getString(R.string.room_title, "ABCD")
            assertTrue(
                "MainActivity did not return from the TV launcher",
                device.wait(Until.hasObject(By.text(expectedRoomTitle)), 30_000),
            )
            instrumentation.waitForIdleSync()
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            assertTrue(recovery.get() != null)
            val replacement = initial.copy(
                current = CurrentTrack("replacement", 0, "playing", "Replacement", "Artist"),
            )
            checkNotNull(recovery.getAndSet(null))(RoomFetchResult.Success(replacement))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals(listOf("current", "replacement"), playback.prepared)
            assertEquals(1, playback.playCount)
            assertEquals(LocalPlaybackStatus.PAUSED, (createdController.state as HostingState.LiveRoom).playback.status)
        } finally {
            scenario?.close()
            controller?.endRoom()
            providerLease?.close()
            server.shutdown()
        }
    }

    private fun assertSetupEndpoints() {
        assertEndpointText("Backend URL", "https://api.example")
        assertEndpointText("Guest origin", "https://guest.example")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun assertEndpointText(label: String, expected: String) {
        composeRule.waitUntilAtLeastOneExists(hasContentDescription(label), timeoutMillis = 5_000)
        composeRule.onNodeWithContentDescription(label)
            .assertTextEquals(expected, includeEditableText = true)
    }

    private fun createController(
        server: MockWebServer,
        repository: RecordingRepository,
        playback: RecordingPlaybackEngine,
        foregroundFetcher: TestRoomFetcher = TestRoomFetcher { _, _ -> Cancelable { } },
    ) = HostSessionController(
        httpClient = OkHttpClient(),
        initialBackendUrl = server.url("/").toString(),
        initialGuestOrigin = "https://guest.example",
        roomRepositoryFactory = { repository },
        roomCollectionScope = roomScope,
        roomCollectionContext = Dispatchers.Unconfined,
        queueMutationContext = QueueMutationContext(Dispatchers.Main.immediate) {
            android.os.Looper.myLooper() === android.os.Looper.getMainLooper()
        },
        foregroundReconcilerFactory = { foregroundFetcher::fetchRoom },
        playbackCoordinatorFactory = { backendUrl, credentials, observer, advanceAfterEnded, sessionScope ->
            AuthoritativePlaybackCoordinator(
                roomCode = credentials.code,
                streamUrl = "${backendUrl.trimEnd('/')}/rooms/${credentials.code}/current/stream",
                playbackEngine = playback,
                reconciler = { RoomFetchResult.Failure },
                parentScope = sessionScope,
                mutationContext = QueueMutationContext(Dispatchers.Unconfined) { true },
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
        private val states = Channel<RoomSyncState>(Channel.UNLIMITED)

        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            for (state in states) emit(state)
        }

        fun publish(state: RoomSyncState) {
            states.trySend(state)
        }
    }

    private class RecordingPlaybackEngine : PlaybackEngine {
        override var state = PlaybackState()
            private set
        var playCount = 0
        var pauseCount = 0
        val prepared = mutableListOf<String>()
        private val listeners = linkedSetOf<(PlaybackState) -> Unit>()

        override fun prepare(media: PlaybackMedia) {
            prepared += media.trackId
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
