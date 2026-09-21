package com.qmix.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSessionControllerTest {
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @org.junit.After
    fun cancelQueueScope() { queueScope.cancel() }

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** qmix#178: off-main recovery must not hold the foreground lock while waiting for main. */
    @Test
    fun foreground_stop_can_overtake_queued_recovery_without_lock_inversion() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
        val owner = java.util.concurrent.atomic.AtomicReference<Thread>()
        val mutationExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-mutation").apply { isDaemon = true; owner.set(this) }
        }
        val recoveryExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-recovery-delivery").apply { isDaemon = true }
        }
        val recoveryQueued = CountDownLatch(1)
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext) =
                Thread.currentThread() !== owner.get()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                mutationExecutor.execute(block)
                if (Thread.currentThread().name == "test-recovery-delivery") recoveryQueued.countDown()
            }
        }
        val mutation = QueueMutationContext(dispatcher) { Thread.currentThread() === owner.get() }
        val initial = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "", 1, "fixture")))
        val repository = RecordingRoomRepository()
        val reconciler = RecordingReconciler()
        var commands = 0
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", executor = Executor { it.run() },
            roomRepositoryFactory = { repository }, foregroundReconcilerFactory = { reconciler },
            queueMutationContext = mutation,
            queueCoordinatorFactory = { _, credentials, observer ->
                QueueAdvancementCoordinator(credentials.code, credentials.hostToken,
                    QueueAdvanceCommand { _, _ -> commands++; QueueAdvanceCommandResult.Success },
                    QueueRoomReconciler { RoomFetchResult.Success(initial) }, observer, queueScope, mutation)
            },
        )
        val unblockMutation = CountDownLatch(1)
        try {
            assertTrue(controller.createRoom())
            controller.enterRoom()
            repository.publish(RoomSyncState.Active("ABCD", initial, Freshness.FRESH, LiveConnection.CONNECTED))
            controller.onHostStopped()
            controller.onHostStarted()
            val blocked = CountDownLatch(1)
            mutationExecutor.execute { blocked.countDown(); check(unblockMutation.await(5, TimeUnit.SECONDS)) }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val stop = mutationExecutor.submit { controller.onHostStopped() }
            val recovery = recoveryExecutor.submit { reconciler.complete(RoomFetchResult.Success(initial)) }
            assertTrue(recoveryQueued.await(5, TimeUnit.SECONDS))
            unblockMutation.countDown()
            stop.get(5, TimeUnit.SECONDS)
            recovery.get(5, TimeUnit.SECONDS)
            assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
            controller.onStartOrNext()
            assertEquals(0, commands)
            controller.endRoom()
        } finally {
            unblockMutation.countDown()
            mutationExecutor.shutdownNow()
            recoveryExecutor.shutdownNow()
        }
    }

    @Test
    fun playback_callbacks_are_safe_without_an_active_coordinator() {
        val controller = HostSessionController(OkHttpClient())

        controller.onPlayPause()
        controller.onPlay()
        controller.onPause()
        controller.onSeekBy(-10_000)
        controller.onSeekBy(10_000)
        controller.onRetryCurrent()

        assertTrue(controller.state is HostingState.Setup)
    }

    @Test
    fun observer_failure_logs_sanitized_host_session_boundary() {
        val sink = RecordingLogSink()
        val logger = QMixLogger(sink, QMixLogLevel.DEBUG) { null }
            .component(QMixLogComponent.APP_HOST_SESSION)
        val controller = HostSessionController(OkHttpClient(), logger = logger)

        controller.observe { throw IllegalStateException("host_token=do-not-log") }

        assertEquals(
            listOf(
                QMixLogRecord(
                    QMixLogLevel.ERROR,
                    QMixLogComponent.APP_HOST_SESSION,
                    QMixLogOperation.OBSERVER_NOTIFICATION,
                    QMixLogCause.CALLBACK_FAILURE,
                ),
            ),
            sink.records,
        )
        assertFalse(sink.records.toString().contains("do-not-log"))
    }

    @Test
    fun http_creation_requires_one_durable_warning_before_any_request() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val persistence = RecordingEndpointPersistence()
        val backend = server.url("/").toString()
        val controller = HostSessionController(
            OkHttpClient(),
            executor = Executor { it.run() },
            settingsPersistence = persistence,
        )
        controller.updateSettings(backend, "https://guest.example")

        assertTrue(controller.createRoom())
        assertEquals(
            HostingState.HttpWarning(backend.trimEnd('/'), "https://guest.example"),
            controller.state,
        )
        assertEquals(0, server.requestCount)
        assertTrue(controller.confirmHttpWarning())

        assertTrue(persistence.warningAcknowledged)
        assertEquals(1, server.requestCount)
        assertEquals(
            EndpointSettings(backend.trimEnd('/'), "https://guest.example"),
            persistence.saved,
        )
        assertTrue(controller.state is HostingState.Invitation)

        val restored = HostSessionController(
            OkHttpClient(),
            executor = Executor { it.run() },
            settingsPersistence = persistence,
        )
        assertEquals(
            HostingState.Setup(backend.trimEnd('/'), "https://guest.example"),
            restored.state,
        )
    }

    @Test
    fun https_creation_skips_warning_and_failed_creation_does_not_persist() {
        val persistence = RecordingEndpointPersistence()
        val controller = HostSessionController(
            OkHttpClient(),
            executor = Executor { it.run() },
            settingsPersistence = persistence,
        )
        controller.updateSettings("https://127.0.0.1:1/", "https://guest.example/")

        assertTrue(controller.createRoom())

        assertTrue(controller.state is HostingState.Error)
        assertFalse(persistence.warningAcknowledged)
        assertEquals(null, persistence.saved)
    }

    @Test
    fun failed_warning_acknowledgement_that_becomes_visible_stays_quarantined_until_success() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val persistence = RecordingEndpointPersistence().apply { failAcknowledgementAfterMutation = true }
        val backend = server.url("/").toString()
        val controller = HostSessionController(
            OkHttpClient(),
            executor = Executor { it.run() },
            settingsPersistence = persistence,
        )
        controller.updateSettings(backend, "https://guest.example")
        assertTrue(controller.createRoom())

        assertFalse(controller.confirmHttpWarning())
        assertTrue(persistence.warningAcknowledged)
        assertEquals(0, server.requestCount)

        assertTrue(controller.createRoom())
        assertEquals(
            HostingState.HttpWarning(backend.trimEnd('/'), "https://guest.example"),
            controller.state,
        )
        assertEquals(0, server.requestCount)

        persistence.failAcknowledgementAfterMutation = false
        assertTrue(controller.confirmHttpWarning())
        assertEquals(1, server.requestCount)
        assertTrue(controller.state is HostingState.Invitation)
    }

    @Test
    fun warning_cancellation_cannot_race_a_durable_confirmation() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"generated-host-token","url":"/r/ABCD"}"""),
        )
        val persistence = BlockingAcknowledgementPersistence()
        val backend = server.url("/").toString()
        val controller = HostSessionController(
            OkHttpClient(),
            executor = Executor { it.run() },
            settingsPersistence = persistence,
        )
        controller.updateSettings(backend, "https://guest.example")
        assertTrue(controller.createRoom())

        val confirmationThread = Executors.newSingleThreadExecutor()
        val confirmation = confirmationThread.submit<Boolean> { controller.confirmHttpWarning() }
        lateinit var cancellation: Thread
        try {
            assertTrue(persistence.acknowledgementStarted.await(5, TimeUnit.SECONDS))
            val cancellationStarted = CountDownLatch(1)
            val cancellationFinished = CountDownLatch(1)
            cancellation = Thread {
                cancellationStarted.countDown()
                controller.cancelHttpWarning()
                cancellationFinished.countDown()
            }.apply { start() }
            assertTrue(cancellationStarted.await(5, TimeUnit.SECONDS))
            awaitBlockedOnController(cancellation)
            assertEquals(1L, cancellationFinished.count)

            persistence.allowAcknowledgement.countDown()

            assertTrue(confirmation.get(5, TimeUnit.SECONDS))
            assertTrue(cancellationFinished.await(5, TimeUnit.SECONDS))
            assertTrue(persistence.warningAcknowledged)
            assertEquals(1, server.requestCount)
            assertTrue(controller.state is HostingState.Invitation)
        } finally {
            persistence.allowAcknowledgement.countDown()
            confirmationThread.shutdownNow()
        }
    }

    @Test
    fun real_store_warning_flow_persists_only_endpoints_and_acknowledgement_then_restores_without_warning() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(EndpointSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val backend = server.url("/").toString()
        val canonicalBackend = backend.trimEnd('/')
        val guestOrigin = "http://192.168.1.20:8180"
        val hostToken = "generated-host-token-must-not-persist"
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"$hostToken","url":"/r/ABCD"}"""),
        )

        try {
            val store = EndpointSettingsStore(context)
            val controller = HostSessionController(
                OkHttpClient(),
                executor = Executor { it.run() },
                settingsPersistence = store,
            )
            controller.updateSettings(backend, guestOrigin)

            assertTrue(controller.createRoom())
            assertTrue(controller.state is HostingState.HttpWarning)
            assertEquals(0, server.requestCount)
            controller.cancelHttpWarning()
            assertFalse(EndpointSettingsStore(context).isHttpWarningAcknowledged())
            assertEquals(0, server.requestCount)

            assertTrue(controller.createRoom())
            assertTrue(controller.confirmHttpWarning())
            assertEquals(1, server.requestCount)
            assertEquals(
                mapOf(
                    "backend_url" to canonicalBackend,
                    "guest_origin" to guestOrigin,
                    "http_warning_acknowledged" to true,
                ),
                preferences.all,
            )
            assertFalse(preferences.all.any { (key, value) ->
                key.contains(hostToken) || value.toString().contains(hostToken)
            })

            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setBody("""{"code":"EFGH","host_token":"another-host-token","url":"/r/EFGH"}"""),
            )
            val restoredStates = mutableListOf<HostingState>()
            val restored = HostSessionController(
                OkHttpClient(),
                executor = Executor { it.run() },
                settingsPersistence = EndpointSettingsStore(context),
            )
            restored.observe(restoredStates::add)
            assertEquals(HostingState.Setup(canonicalBackend, guestOrigin), restored.state)

            assertTrue(restored.createRoom())

            assertEquals(2, server.requestCount)
            assertTrue(restored.state is HostingState.Invitation)
            assertFalse(restoredStates.any { it is HostingState.HttpWarning })
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun failed_endpoint_save_returns_safe_error_and_is_not_restored_by_a_fresh_controller() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(EndpointSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().putBoolean("http_warning_acknowledged", true).commit()
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"generated-host-token","url":"/r/ABCD"}"""),
        )
        val failingStore = EndpointSettingsStore(
            context = context,
            endpointSaveCommit = { editor ->
                editor.commit()
                false
            },
            acknowledgementCommit = android.content.SharedPreferences.Editor::commit,
        )
        val controller = HostSessionController(
            OkHttpClient(),
            executor = Executor { it.run() },
            settingsPersistence = failingStore,
        )
        val states = mutableListOf<HostingState>()
        controller.observe(states::add)

        try {
            controller.updateSettings(server.url("/").toString(), "https://guest.example")
            assertTrue(controller.createRoom())

            assertEquals(1, server.requestCount)
            assertEquals(UserMessage.PERSISTENCE_ERROR, (controller.state as HostingState.Error).message)
            assertFalse(states.any { it is HostingState.Invitation })
            assertEquals(EndpointSettings.EMPTY, failingStore.load())
            assertEquals(
                HostingState.Setup("", ""),
                HostSessionController(
                    OkHttpClient(),
                    executor = Executor { it.run() },
                    settingsPersistence = EndpointSettingsStore(context),
                ).state,
            )
            assertTrue(EndpointSettingsStore(context).isHttpWarningAcknowledged())
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun warning_can_be_cancelled_without_acknowledgement_or_network_access() {
        val persistence = RecordingEndpointPersistence()
        val backend = server.url("/").toString()
        val controller = HostSessionController(OkHttpClient(), settingsPersistence = persistence)
        controller.updateSettings(backend, "http://192.168.1.20:8180")
        controller.createRoom()

        controller.cancelHttpWarning()

        assertEquals(
            HostingState.Setup(backend.trimEnd('/'), "http://192.168.1.20:8180"),
            controller.state,
        )
        assertFalse(persistence.warningAcknowledged)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun credential_bearing_endpoint_is_rejected_without_retaining_secret_in_error_state() {
        val controller = HostSessionController(OkHttpClient())
        controller.updateSettings(
            "https://user:host-secret@api.example?host_token=host-secret",
            "https://guest.example",
        )

        assertFalse(controller.createRoom())

        assertEquals(
            HostingState.Error(UserMessage.INVALID_ENDPOINT, "", ""),
            controller.state,
        )
        assertFalse(controller.state.toString().contains("host-secret"))
    }

    @Test
    fun repeated_create_while_pending_sends_exactly_one_post() {
        server.enqueue(
            MockResponse()
                .setBodyDelay(150, TimeUnit.MILLISECONDS)
                .setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val controller = HostSessionController(OkHttpClient())
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val completed = CountDownLatch(1)
        controller.observe { if (it is HostingState.Invitation) completed.countDown() }

        assertTrue(controller.createRoom())
        assertFalse(controller.createRoom())

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(1, server.requestCount)
        assertEquals(
            HostingState.Invitation(GuestInvite("ABCD", "https://guest.example/r/ABCD")),
            controller.state,
        )
        assertFalse(controller.state.toString().contains("host-secret"))
    }

    @Test
    fun settings_changes_during_pending_cannot_reenable_create() {
        server.enqueue(
            MockResponse().setBodyDelay(150, TimeUnit.MILLISECONDS).setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val controller = HostSessionController(OkHttpClient())
        controller.updateSettings(server.url("/").toString(), "https://guest.example")

        assertTrue(controller.createRoom())
        controller.updateSettings("https://other.example", "https://other.example")
        assertFalse(controller.createRoom())

        server.takeRequest(5, TimeUnit.SECONDS)
        Thread.sleep(250)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun lost_create_response_is_not_retried_automatically() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val controller = HostSessionController(OkHttpClient())
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val failed = CountDownLatch(1)
        controller.observe { if (it is HostingState.Error) failed.countDown() }

        assertTrue(controller.createRoom())

        assertTrue(failed.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(1, server.requestCount)
        assertEquals(
            UserMessage.SERVER_UNREACHABLE,
            (controller.state as HostingState.Error).message,
        )
    }

    @Test
    fun invitation_action_transitions_to_initial_live_room_state() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val controller = HostSessionController(OkHttpClient())
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val invited = CountDownLatch(1)
        controller.observe { if (it is HostingState.Invitation) invited.countDown() }
        controller.createRoom()
        assertTrue(invited.await(5, TimeUnit.SECONDS))

        controller.enterRoom()

        assertEquals(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Active("ABCD", null, Freshness.LOADING, LiveConnection.CONNECTING),
            ),
            controller.state,
        )
    }

    @Test
    fun ending_a_pending_room_creation_ignores_its_late_response() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""")
                .setBodyDelay(500, TimeUnit.MILLISECONDS),
        )
        val persistence = RecordingEndpointPersistence().apply { warningAcknowledged = true }
        val controller = HostSessionController(OkHttpClient(), settingsPersistence = persistence)
        val setup = HostingState.Setup(server.url("/").toString().trimEnd('/'), "https://guest.example")
        controller.updateSettings(setup.backendUrl, setup.guestOrigin)
        val invited = CountDownLatch(1)
        controller.observe { if (it is HostingState.Invitation) invited.countDown() }

        controller.createRoom()
        server.takeRequest(5, TimeUnit.SECONDS)
        controller.endRoom()

        assertEquals(false, invited.await(1, TimeUnit.SECONDS))
        assertEquals(setup, controller.state)
        assertEquals(null, persistence.saved)
    }

    @Test
    fun primary_action_uses_the_queue_coordinator_and_publishes_pending_state() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = RecordingRoomRepository()
        val command = RecordingAdvanceCommand()
        val reconciler = RecordingReconciler()
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            executor = Executor { it.run() },
            roomRepositoryFactory = { repository },
            queueCoordinatorFactory = { _, credentials, observer ->
                testQueueCoordinator(
        queueScope,
                    credentials.code,
                    credentials.hostToken,
                    command,
                    reconciler,
                    observer,
                )
            },
        )
        controller.createRoom()
        controller.enterRoom()
        val room = RoomState(
            "ABCD",
            null,
            listOf(QueuedTrack("track-1", "https://example/1", "Title", "Artist", 60, "fixture")),
        )
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))

        controller.onStartOrNext()

        assertEquals(1, command.callbacks.size)
        assertTrue((controller.state as HostingState.LiveRoom).commandPending)
        command.complete(QueueAdvanceCommandResult.Indeterminate)
        reconciler.complete(RoomFetchResult.Success(room))
        assertFalse((controller.state as HostingState.LiveRoom).commandPending)
    }

    @Test
    fun returning_to_foreground_requires_its_own_successful_reconciliation_before_commands() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = RecordingRoomRepository()
        val command = RecordingAdvanceCommand()
        val reconciler = RecordingReconciler()
        val engine = HostRecordingPlaybackEngine()
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            executor = Executor { it.run() },
            roomRepositoryFactory = { repository },
            foregroundReconcilerFactory = { reconciler },
            queueCoordinatorFactory = { _, credentials, observer ->
                testQueueCoordinator(queueScope, credentials.code, credentials.hostToken, command, reconciler, observer)
            },
            playbackCoordinatorFactory = { backendUrl, credentials, observer, advanceAfterEnded ->
                AuthoritativePlaybackCoordinator(
                    credentials.code,
                    "${backendUrl.trimEnd('/')}/rooms/${credentials.code}/current/stream",
                    engine,
                    reconciler,
                    Executor { it.run() },
                    advanceAfterEnded,
                    observer,
                )
            },
        )
        controller.createRoom()
        controller.enterRoom()
        val initial = RoomState(
            "ABCD",
            CurrentTrack("one", 0, "playing", "One", "Artist"),
            listOf(QueuedTrack("two", "url", "Two", "Artist", 60, "fixture")),
        )
        repository.publish(RoomSyncState.Active("ABCD", initial, Freshness.FRESH, LiveConnection.CONNECTED))

        controller.onHostStopped()
        controller.onStartOrNext()
        repository.publish(RoomSyncState.Active("ABCD", initial, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.onHostStarted()

        assertEquals(1, engine.pauseCount)
        assertTrue(command.callbacks.isEmpty())
        assertEquals(listOf("ABCD"), reconciler.calls)
        reconciler.complete(RoomFetchResult.Failure)
        repository.publish(RoomSyncState.Active("ABCD", initial, Freshness.FRESH, LiveConnection.CONNECTED))
        assertEquals(listOf("ABCD", "ABCD"), reconciler.calls)
        val replacement = initial.copy(current = CurrentTrack("other", 0, "playing", "Other", "Artist"))
        reconciler.complete(RoomFetchResult.Success(replacement))

        repository.publishAt(
            0,
            RoomSyncState.Active("ABCD", initial, Freshness.FRESH, LiveConnection.CONNECTED),
        )

        controller.onStartOrNext()
        assertEquals(1, command.callbacks.size)
        assertEquals(listOf("one", "other"), engine.prepared.map(PlaybackMedia::trackId))
        assertEquals(1, engine.playCount)
        assertEquals(LocalPlaybackStatus.PAUSED, (controller.state as HostingState.LiveRoom).playback.status)
    }

    @Test
    fun start_command_followed_by_fresh_selection_starts_local_playback_once() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = RecordingRoomRepository()
        val command = RecordingAdvanceCommand()
        val reconciler = RecordingReconciler()
        val engine = HostRecordingPlaybackEngine()
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            executor = Executor { it.run() },
            roomRepositoryFactory = { repository },
            queueCoordinatorFactory = { _, credentials, observer ->
                testQueueCoordinator(queueScope, credentials.code, credentials.hostToken, command, reconciler, observer)
            },
            playbackCoordinatorFactory = { backendUrl, credentials, observer, advanceAfterEnded ->
                AuthoritativePlaybackCoordinator(
                    roomCode = credentials.code,
                    streamUrl = "${backendUrl.trimEnd('/')}/rooms/${credentials.code}/current/stream",
                    playbackEngine = engine,
                    reconciler = reconciler,
                    dispatcher = Executor { it.run() },
                    advanceAfterEnded = advanceAfterEnded,
                    observer = observer,
                )
            },
        )
        controller.createRoom()
        controller.enterRoom()
        repository.publish(
            RoomSyncState.Active(
                "ABCD",
                RoomState("ABCD", null, listOf(QueuedTrack("one", "url", "One", "Artist", 60, "fixture"))),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            ),
        )

        controller.onStartOrNext()
        command.complete(QueueAdvanceCommandResult.Success)
        val selected = RoomState(
            "ABCD",
            CurrentTrack("one", 0, "playing", "One", "Artist"),
            listOf(QueuedTrack("two", "url", "Two", "Artist", 60, "fixture")),
        )
        reconciler.complete(RoomFetchResult.Success(selected))
        repository.publish(RoomSyncState.Active("ABCD", selected, Freshness.FRESH, LiveConnection.CONNECTED))
        repository.publish(RoomSyncState.Active("ABCD", selected, Freshness.FRESH, LiveConnection.CONNECTED))

        assertEquals(listOf("one"), engine.prepared.map(PlaybackMedia::trackId))
        assertEquals(1, engine.playCount)
        assertEquals(LocalPlaybackStatus.BUFFERING, (controller.state as HostingState.LiveRoom).playback.status)

        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.READY,
                isPlaying = true,
                positionMs = 5_000,
                durationMs = 60_000,
                isSeekable = true,
            ),
        )
        assertEquals(LocalPlaybackStatus.PLAYING, (controller.state as HostingState.LiveRoom).playback.status)
        controller.onSeekBy(Long.MIN_VALUE)
        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.READY,
                isPlaying = true,
                positionMs = 55_000,
                durationMs = 60_000,
                isSeekable = true,
            ),
        )
        controller.onSeekBy(Long.MAX_VALUE)
        assertEquals(listOf(0L, 60_000L), engine.seeks)
        controller.onPlayPause()
        assertEquals(1, engine.pauseCount)
        assertEquals(LocalPlaybackStatus.PAUSED, (controller.state as HostingState.LiveRoom).playback.status)
        controller.onPlayPause()
        assertEquals(2, engine.playCount)
        assertEquals(LocalPlaybackStatus.BUFFERING, (controller.state as HostingState.LiveRoom).playback.status)
        controller.pausePlayback()
        assertEquals(2, engine.pauseCount)
        assertEquals(LocalPlaybackStatus.PAUSED, (controller.state as HostingState.LiveRoom).playback.status)
        controller.resumePlayback()
        assertEquals(3, engine.playCount)
        assertEquals(LocalPlaybackStatus.BUFFERING, (controller.state as HostingState.LiveRoom).playback.status)

        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.ERROR,
                error = PlaybackError(PlaybackErrorKind.NETWORK, "offline"),
            ),
        )
        val failedPlayback = (controller.state as HostingState.LiveRoom).playback
        controller.onPause()
        controller.onPlay()
        controller.onPlayPause()
        assertEquals(failedPlayback, (controller.state as HostingState.LiveRoom).playback)
        assertEquals(2, engine.pauseCount)
        assertEquals(3, engine.playCount)
        assertEquals(1, engine.prepared.size)
        controller.onStartOrNext()
        assertEquals(2, command.callbacks.size)
        assertEquals(LocalPlaybackStatus.ERROR, (controller.state as HostingState.LiveRoom).playback.status)

        val staleReplacement = selected.copy(current = CurrentTrack("two", 0, "playing", "Two", "Artist"))
        repository.publish(
            RoomSyncState.Active("ABCD", staleReplacement, Freshness.STALE, LiveConnection.RECONNECTING),
        )
        val presentation = controller.state as HostingState.LiveRoom
        assertEquals(Freshness.STALE, (presentation.synchronization as RoomSyncState.Active).freshness)
        assertEquals("two", presentation.synchronization.room?.current?.trackId)
        assertEquals(listOf("one"), engine.prepared.map(PlaybackMedia::trackId))

        controller.onRetryCurrent()
        assertEquals(listOf("ABCD", "ABCD"), reconciler.calls)
        reconciler.complete(RoomFetchResult.Success(selected))
        assertEquals(listOf("one", "one"), engine.prepared.map(PlaybackMedia::trackId))
        assertEquals(4, engine.playCount)
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ENDED))
        assertEquals(2, command.callbacks.size)

        controller.endRoom()
        assertEquals(3, engine.pauseCount)
        assertTrue(repository.closed)
        assertTrue(controller.state is HostingState.Setup)
    }

    @Test
    fun room_sync_is_application_session_owned_and_canceled_when_session_ends() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = RecordingRoomRepository()
        val controller = HostSessionController(
            OkHttpClient(),
            roomRepositoryFactory = { repository },
        )
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val invited = CountDownLatch(1)
        controller.observe { if (it is HostingState.Invitation) invited.countDown() }
        controller.createRoom()
        assertTrue(invited.await(5, TimeUnit.SECONDS))

        controller.enterRoom()
        val synchronized = RoomSyncState.Active(
            "ABCD",
            RoomState("ABCD", null, emptyList()),
            Freshness.FRESH,
            LiveConnection.CONNECTED,
        )
        repository.publish(synchronized)

        assertEquals("ABCD", repository.observedCode)
        assertEquals(synchronized, controller.roomSyncState)
        controller.endRoom()
        assertTrue(repository.closed)
        assertEquals(
            HostingState.Setup(server.url("/").toString().trimEnd('/'), "https://guest.example"),
            controller.state,
        )
        repository.publish(synchronized.copy(freshness = Freshness.STALE))
        assertEquals(null, controller.roomSyncState)
    }

    private fun awaitBlockedOnController(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.isAlive && thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(
            "cancellation must be waiting for the controller monitor",
            Thread.State.BLOCKED,
            thread.state,
        )
    }

    private class BlockingAcknowledgementPersistence : EndpointSettingsPersistence {
        val acknowledgementStarted = CountDownLatch(1)
        val allowAcknowledgement = CountDownLatch(1)
        private val acknowledged = AtomicBoolean(false)
        var saved: EndpointSettings? = null
            private set
        val warningAcknowledged: Boolean
            get() = acknowledged.get()

        override fun load(): EndpointSettings = saved ?: EndpointSettings.EMPTY

        override fun save(settings: EndpointSettings) {
            saved = settings
        }

        override fun isHttpWarningAcknowledged(): Boolean = acknowledged.get()

        override fun acknowledgeHttpWarning() {
            acknowledgementStarted.countDown()
            check(allowAcknowledgement.await(5, TimeUnit.SECONDS)) { "Acknowledgement test gate timed out" }
            acknowledged.set(true)
        }
    }

    private class RecordingEndpointPersistence : EndpointSettingsPersistence {
        var restored = EndpointSettings.EMPTY
        var saved: EndpointSettings? = null
        var warningAcknowledged = false
        var failAcknowledgementAfterMutation = false

        override fun load(): EndpointSettings = saved ?: restored

        override fun save(settings: EndpointSettings) {
            saved = settings
        }

        override fun isHttpWarningAcknowledged(): Boolean = warningAcknowledged

        override fun acknowledgeHttpWarning() {
            warningAcknowledged = true
            check(!failAcknowledgementAfterMutation) { "Synthetic acknowledgement failure" }
        }
    }

    private class HostRecordingPlaybackEngine : PlaybackEngine {
        override var state = PlaybackState()
            private set
        val prepared = mutableListOf<PlaybackMedia>()
        var playCount = 0
        var pauseCount = 0
        val seeks = mutableListOf<Long>()
        private val listeners = linkedSetOf<(PlaybackState) -> Unit>()

        override fun prepare(media: PlaybackMedia) {
            prepared += media
            state = PlaybackState(mediaId = media.trackId, status = PlaybackStatus.BUFFERING)
            listeners.toList().forEach { it(state) }
        }
        override fun play() { playCount++ }
        override fun pause() { pauseCount++ }
        override fun seekTo(positionMs: Long) { seeks += positionMs }
        override fun release() = Unit
        override fun addListener(listener: (PlaybackState) -> Unit) { listeners += listener }
        override fun removeListener(listener: (PlaybackState) -> Unit) { listeners -= listener }

        fun emit(next: PlaybackState) {
            state = next
            listeners.toList().forEach { it(next) }
        }
    }

    private class RecordingAdvanceCommand : TestQueueCommand {
        val callbacks = mutableListOf<(QueueAdvanceCommandResult) -> Unit>()

        override fun skip(
            roomCode: String,
            hostToken: String,
            callback: (QueueAdvanceCommandResult) -> Unit,
        ) {
            assertEquals("ABCD", roomCode)
            assertEquals("host-secret", hostToken)
            callbacks += callback
        }

        fun complete(result: QueueAdvanceCommandResult) {
            callbacks.last()(result)
        }
    }

    private class RecordingReconciler : RoomStateFetcher {
        private var callback: ((RoomFetchResult) -> Unit)? = null
        val calls = mutableListOf<String>()

        override fun fetch(roomCode: String, callback: (RoomFetchResult) -> Unit): Cancelable {
            assertEquals("ABCD", roomCode)
            calls += roomCode
            this.callback = callback
            return Cancelable { }
        }

        fun complete(result: RoomFetchResult) {
            callback?.invoke(result)
        }
    }

    private class RecordingRoomRepository : RoomRepository {
        var observedCode: String? = null
        var closed = false
        private val observers = mutableListOf<(RoomSyncState) -> Unit>()

        override fun observe(roomCode: String, onUpdate: (RoomSyncState) -> Unit): AutoCloseable {
            observedCode = roomCode
            observers += onUpdate
            return AutoCloseable { closed = true }
        }

        fun publish(state: RoomSyncState) {
            observers.lastOrNull()?.invoke(state)
        }

        fun publishAt(index: Int, state: RoomSyncState) {
            observers[index](state)
        }
    }
}
