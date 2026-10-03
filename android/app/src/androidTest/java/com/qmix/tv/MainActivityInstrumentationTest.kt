package com.qmix.tv

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5_000) {
                    roomScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("join native Activity test root: timed out", timeout)
        }
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
                roomCollectionScope = roomScope,
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
                roomCollectionScope = roomScope,
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
    @OptIn(ExperimentalTestApi::class)
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
                controller.createRoom()
                controller.awaitCreatedForTest(step = "stopped Activity: invitation published")
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                controller.awaitStateForTest(step = "stopped Activity: invitation marked for foreground recovery") {
                    (it as? HostingState.Invitation)?.foregroundRecoveryPending == true
                }
                controller.enterRoom()
                controller.awaitStateForTest(step = "stopped Activity: live room published while collector stopped") {
                    it is HostingState.LiveRoom
                }
                assertTrue(controller.state is HostingState.LiveRoom)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                val title = application.getString(R.string.room_title, "ABCD")
                try {
                    composeRule.waitUntilAtLeastOneExists(hasText(title), timeoutMillis = 10_000)
                } catch (timeout: ComposeTimeoutException) {
                    throw AssertionError("stopped Activity: latest room title not rendered after resume; state=${controller.state}", timeout)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue((activity.application as QMixApplication).hostSessionForActivity() === controller)
                }
                try {
                    composeRule.waitUntilAtLeastOneExists(hasText(title), timeoutMillis = 10_000)
                } catch (timeout: ComposeTimeoutException) {
                    throw AssertionError("recreated Activity: same session room title not rendered; state=${controller.state}", timeout)
                }
                assertTrue(controller.state is HostingState.LiveRoom)
            }
        } finally {
            controller.endRoom()
            controller.awaitSetupForTest(step = "stopped Activity: session teardown joined")
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

            createdController.createRoom()
            createdController.awaitCreatedForTest(step = "Activity session: invitation published")
            awaitInvitationObservation(repository)
            createdController.enterRoom()
            awaitLiveObservation(repository)
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
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.BUFFERING, "media keys: initial current selected")
            awaitConditionForTest(step = "media keys: initial engine prepare and play completed") {
                playback.prepared == listOf("current") && playback.playCount == 1
            }
            playback.emit(playingState())
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.PLAYING, "media keys: initial playing callback")

            scenario = ActivityScenario.launch(MainActivity::class.java)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            awaitUiCondition("media keys: injected onCreate controller bound to Compose") {
                device.hasObject(By.text("Room ABCD"))
            }
            assertTrue(
                "MainActivity did not bind the injected onCreate controller to Compose",
                device.hasObject(By.text("Room ABCD")),
            )

            scenario.onActivity { createdController.pausePlayback() }
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.PAUSED, "media PLAY: prepare explicit pause")
            assertInitialDownDispatchesOnce(
                scenario, createdController, KeyEvent.KEYCODE_MEDIA_PLAY, playback.playCount,
            ) { playback.playCount }
            scenario.onActivity { createdController.pausePlayback() }
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.PAUSED, "media PLAY: prepare repeat/up check")
            assertRepeatDownAndUpAreConsumedWithoutRedispatch(
                scenario, createdController, KeyEvent.KEYCODE_MEDIA_PLAY, playback.playCount,
            ) { playback.playCount }

            scenario.onActivity { createdController.resumePlayback() }
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.BUFFERING, "media PAUSE: prepare resume")
            playback.emit(playingState())
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.PLAYING, "media PAUSE: prepare playing callback")
            assertInitialDownDispatchesOnce(
                scenario, createdController, KeyEvent.KEYCODE_MEDIA_PAUSE, playback.pauseCount,
            ) { playback.pauseCount }
            scenario.onActivity { createdController.resumePlayback() }
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.BUFFERING, "media PAUSE: prepare repeat/up resume")
            playback.emit(playingState())
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.PLAYING, "media PAUSE: prepare repeat/up playing")
            assertRepeatDownAndUpAreConsumedWithoutRedispatch(
                scenario, createdController, KeyEvent.KEYCODE_MEDIA_PAUSE, playback.pauseCount,
            ) { playback.pauseCount }

            scenario.onActivity { createdController.pausePlayback() }
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.PAUSED, "media toggle: prepare explicit pause")
            scenario.onActivity { createdController.resumePlayback() }
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.BUFFERING, "media toggle: prepare resume")
            playback.emit(playingState())
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.PLAYING, "media toggle: prepare playing callback")
            assertInitialDownDispatchesOnce(
                scenario, createdController, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, playback.pauseCount,
            ) { playback.pauseCount }
            scenario.onActivity { createdController.resumePlayback() }
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.BUFFERING, "media toggle: prepare repeat/up resume")
            playback.emit(playingState())
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.PLAYING, "media toggle: prepare repeat/up playing")
            assertRepeatDownAndUpAreConsumedWithoutRedispatch(
                scenario, createdController, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, playback.pauseCount,
            ) { playback.pauseCount }

            listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT).forEach { keyCode ->
                assertDispatchDoesNotControlPlayback(
                    scenario, createdController, KeyEvent(KeyEvent.ACTION_DOWN, keyCode), playback,
                )
                assertDispatchDoesNotControlPlayback(
                    scenario, createdController, KeyEvent(KeyEvent.ACTION_UP, keyCode), playback,
                )
            }
        } finally {
            try {
                scenario?.close()
            } finally {
                try {
                    controller?.let {
                        it.endRoom()
                        // Setup is published only after any admitted session has joined.
                        if (it.state !is HostingState.Setup) {
                            it.awaitSetupForTest(step = "Activity cleanup: admitted session teardown joined")
                        }
                    }
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

    /** qmix#211: ambient mode must not interrupt active local playback. */
    @Test
    fun keep_screen_on_follows_local_playback_and_room_teardown() {
        val server = MockWebServer()
        var controller: HostSessionController? = null
        var providerLease: AutoCloseable? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            server.start()
            server.enqueue(MockResponse().setResponseCode(201).setBody(
                """{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""",
            ))
            val repository = RecordingRepository()
            val playback = RecordingPlaybackEngine()
            val createdController = createController(server, repository, playback)
            controller = createdController
            val application = ApplicationProvider.getApplicationContext<QMixApplication>()
            providerLease = application.installActivityHostSessionProvider { createdController }

            // Establish the real foreground Activity before admitting a room and its playback.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            composeRule.waitForIdle()
            assertKeepScreenOn(scenario, false, "initial setup")
            createdController.createRoom()
            val invitation = createdController.awaitStep("room invitation") {
                it is HostingState.Invitation || it is HostingState.Error
            }
            assertTrue("room creation failed: $invitation", invitation is HostingState.Invitation)
            awaitInvitationObservation(repository)
            createdController.enterRoom()
            createdController.awaitStep("live room admission") { it is HostingState.LiveRoom }
            awaitLiveObservation(repository)
            val current = CurrentTrack("current", 0, "playing", "Current", "Artist")
            repository.publishCurrent(current)
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.BUFFERING, "first current track")
            assertKeepScreenOn(scenario, true, "first buffering track")

            playback.emit(playingState())
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.PLAYING, "playing callback")
            assertKeepScreenOn(scenario, true, "playing callback")

            createdController.pausePlayback()
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.PAUSED, "explicit pause")
            assertKeepScreenOn(scenario, false, "explicit pause")

            createdController.resumePlayback()
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.BUFFERING, "explicit resume")
            assertKeepScreenOn(scenario, true, "explicit resume")

            playback.emit(PlaybackState(mediaId = "current", status = PlaybackStatus.ENDED))
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.COMPLETED, "track completed")
            assertKeepScreenOn(scenario, false, "track completed")

            repository.publishCurrent(current.copy(trackId = "next"))
            createdController.awaitStep("next track selected") {
                (it as? HostingState.LiveRoom)?.playback?.trackId == "next"
            }
            assertKeepScreenOn(scenario, true, "next track buffering")
            playback.emit(PlaybackState(mediaId = "next", status = PlaybackStatus.ERROR))
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.ERROR, "next track error")
            assertKeepScreenOn(scenario, false, "next track error")

            repository.publishCurrent(null)
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.IDLE, "current track removed")
            assertKeepScreenOn(scenario, false, "current track removed")

            repository.publishCurrent(current)
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.BUFFERING, "current track restored")
            assertKeepScreenOn(scenario, true, "current track restored")
            createdController.endRoom()
            createdController.awaitSetupForTest(step = "keep screen on: room teardown joined")
            assertKeepScreenOn(scenario, false, "room teardown")
        } finally {
            try {
                scenario?.close()
            } finally {
                try {
                    controller?.let {
                        it.endRoom()
                        // Setup is published only after any admitted session has joined.
                        if (it.state !is HostingState.Setup) {
                            it.awaitSetupForTest(step = "Activity cleanup: admitted session teardown joined")
                        }
                    }
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

    private fun assertKeepScreenOn(
        scenario: ActivityScenario<MainActivity>, expected: Boolean, step: String,
    ) {
        var actual = false
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                scenario.onActivity { activity ->
                    actual = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                }
                actual == expected
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$step: expected FLAG_KEEP_SCREEN_ON=$expected, observed=$actual", timeout)
        }
    }

    private fun awaitInvitationObservation(repository: RecordingRepository) {
        awaitUiCondition("invitation repository subscription started") { repository.observations.get() >= 1 }
    }

    private fun awaitLiveObservation(repository: RecordingRepository) {
        // Wait through the Compose rule so its main-dispatcher effects can run.
        awaitUiCondition("live room repository subscription started") { repository.observations.get() >= 2 }
    }

    private fun awaitUiCondition(step: String, predicate: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) { predicate() }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$step: UI condition did not become true", timeout)
        }
    }

    private fun RecordingRepository.publishCurrent(current: CurrentTrack?) {
        publish(RoomSyncState.Active(
            "ABCD", RoomState("ABCD", current, emptyList()), Freshness.FRESH, LiveConnection.CONNECTED,
        ))
    }

    private fun HostSessionController.awaitStep(
        step: String, predicate: (HostingState) -> Boolean,
    ): HostingState = awaitStateForTest(step = step, predicate = predicate)

    private fun HostSessionController.awaitPlaybackStatus(status: LocalPlaybackStatus, step: String) {
        awaitStep("$step: expected local playback $status") {
            (it as? HostingState.LiveRoom)?.playback?.status == status
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
            createdController.createRoom()
            createdController.awaitCreatedForTest(step = "Activity session: invitation published")
            awaitInvitationObservation(repository)
            createdController.enterRoom()
            awaitLiveObservation(repository)
            val initial = RoomState(
                "ABCD",
                CurrentTrack("current", 0, "playing", "Current", "Artist"),
                listOf(QueuedTrack("next", "url", "Next", "Artist", 4, "fixture")),
            )
            repository.publish(RoomSyncState.Active("ABCD", initial, Freshness.FRESH, LiveConnection.CONNECTED))
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.BUFFERING, "Home/return: initial current selected")
            awaitConditionForTest(step = "Home/return: initial engine prepare and play completed") {
                playback.prepared == listOf("current") && playback.playCount == 1
            }
            playback.emit(playingState())
            createdController.awaitPlaybackStatus(LocalPlaybackStatus.PLAYING, "Home/return: initial playback published")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            composeRule.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val device = UiDevice.getInstance(instrumentation)

            device.pressHome()
            createdController.awaitStateForTest(step = "Home/return: background pause and recovery gate published") {
                (it as? HostingState.LiveRoom)?.let {
                    it.foregroundRecoveryPending && it.playback.status == LocalPlaybackStatus.PAUSED
                } == true
            }
            awaitConditionForTest(step = "Home/return: stopped subscription completed and engine paused") {
                repository.activeObservations.get() == 0 && playback.pauseCount == 1
            }
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
            awaitUiCondition("Home/return: original ActivityScenario resumed") {
                scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            awaitConditionForTest(step = "Home/return: original controller foreground fetch reached") { recovery.get() != null }
            assertTrue(recovery.get() != null)
            val replacement = initial.copy(
                current = CurrentTrack("replacement", 0, "playing", "Replacement", "Artist"),
            )
            checkNotNull(recovery.getAndSet(null))(RoomFetchResult.Success(replacement))
            createdController.awaitStateForTest(step = "Home/return: fresh replacement reconciled without autoplay") {
                (it as? HostingState.LiveRoom)?.let {
                    !it.foregroundRecoveryPending && it.playback.trackId == "replacement" &&
                        it.playback.status == LocalPlaybackStatus.PAUSED
                } == true
            }
            awaitConditionForTest(step = "Home/return: recovered repository subscription started") {
                repository.observations.get() >= 3 && repository.activeObservations.get() == 1
            }

            assertEquals(listOf("current", "replacement"), playback.prepared)
            assertEquals(1, playback.playCount)
            assertEquals(LocalPlaybackStatus.PAUSED, (createdController.state as HostingState.LiveRoom).playback.status)
        } finally {
            try {
                scenario?.close()
            } finally {
                try {
                    controller?.let {
                        it.endRoom()
                        if (it.state !is HostingState.Setup) {
                            it.awaitSetupForTest(step = "Home/return cleanup: admitted session teardown joined")
                        }
                    }
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

    private fun assertSetupEndpoints() {
        assertEndpointText("Backend URL", "https://api.example")
        assertEndpointText("Guest origin", "https://guest.example")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun assertEndpointText(label: String, expected: String) {
        try {
            composeRule.waitUntilAtLeastOneExists(hasContentDescription(label), timeoutMillis = 5_000)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("restored setup: endpoint field '$label' not rendered; expected=$expected", timeout)
        }
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
                mutationContext = mainTestMutationContext(),
                advanceAfterEnded = advanceAfterEnded,
                observer = observer,
            )
        },
    )

    private fun assertInitialDownDispatchesOnce(
        scenario: ActivityScenario<MainActivity>,
        controller: HostSessionController,
        keyCode: Int,
        before: Int,
        actualActions: () -> Int,
    ) {
        scenario.onActivity { activity ->
            assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode)))
        }
        awaitConditionForTest(step = "media key $keyCode: initial DOWN reached engine") {
            actualActions() >= before + 1
        }
        controller.awaitPlaybackStatus(
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) LocalPlaybackStatus.BUFFERING else LocalPlaybackStatus.PAUSED,
            "media key $keyCode: initial DOWN playback published",
        )
        assertEquals(before + 1, actualActions())
    }

    private fun assertRepeatDownAndUpAreConsumedWithoutRedispatch(
        scenario: ActivityScenario<MainActivity>,
        controller: HostSessionController,
        keyCode: Int,
        expectedActions: Int,
        actualActions: () -> Int,
    ) {
        scenario.onActivity { activity ->
            assertTrue(activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 1)))
            controller.onInvite()
        }
        // Opening and dismissing the real invitation are positive FIFO state transitions,
        // so a wrongly redispatched repeat/UP cannot hide behind a same-Main immediate read.
        controller.awaitStateForTest(step = "media key $keyCode: invitation opened after repeated DOWN") {
            (it as? HostingState.LiveRoom)?.invitationVisible == true
        }
        assertEquals(expectedActions, actualActions())
        scenario.onActivity { activity ->
            assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode)))
            controller.onBack { }
        }
        controller.awaitStateForTest(step = "media key $keyCode: invitation dismissed after UP") {
            (it as? HostingState.LiveRoom)?.invitationVisible == false
        }
        assertEquals(expectedActions, actualActions())
    }

    private fun assertDispatchDoesNotControlPlayback(
        scenario: ActivityScenario<MainActivity>,
        controller: HostSessionController,
        event: KeyEvent,
        playback: RecordingPlaybackEngine,
    ) {
        val playCount = playback.playCount
        val pauseCount = playback.pauseCount

        scenario.onActivity { activity ->
            activity.dispatchKeyEvent(event)
            controller.onInvite()
        }
        controller.awaitStateForTest(step = "D-pad ${event.keyCode}/${event.action}: invitation opened after dispatch") {
            (it as? HostingState.LiveRoom)?.invitationVisible == true
        }
        assertEquals(playCount, playback.playCount)
        assertEquals(pauseCount, playback.pauseCount)
        scenario.onActivity { controller.onBack { } }
        controller.awaitStateForTest(step = "D-pad ${event.keyCode}/${event.action}: invitation dismissed") {
            (it as? HostingState.LiveRoom)?.invitationVisible == false
        }
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
        val observations = java.util.concurrent.atomic.AtomicInteger()
        val activeObservations = java.util.concurrent.atomic.AtomicInteger()
        private val states = Channel<RoomSyncState>(Channel.UNLIMITED)

        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            activeObservations.incrementAndGet()
            try {
                observations.incrementAndGet()
                for (state in states) emit(state)
            } finally {
                activeObservations.decrementAndGet()
            }
        }

        fun publish(state: RoomSyncState) {
            states.trySend(state)
        }
    }

    private class RecordingPlaybackEngine : PlaybackEngine {
        @Volatile override var state = PlaybackState()
            private set
        @Volatile var playCount = 0
        @Volatile var pauseCount = 0
        val prepared = CopyOnWriteArrayList<String>()
        private val listeners = CopyOnWriteArrayList<(PlaybackState) -> Unit>()

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
