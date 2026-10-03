package com.qmix.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
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
    private var serializedMutation: SerializedTestMutationContext? = null
    private val observations = mutableListOf<AutoCloseable>()

    private fun HostSessionController.observeForTest(observer: (HostingState) -> Unit): AutoCloseable =
        collectStatesForTest(observer = observer).also(observations::add)

    private fun HostSessionController.awaitLiveForTest(
        step: String, predicate: (HostingState.LiveRoom) -> Boolean,
    ): HostingState.LiveRoom = awaitStateForTest(step) {
        it is HostingState.LiveRoom && predicate(it)
    } as HostingState.LiveRoom

    private fun HostSessionController.awaitSetupAfterCleanupForTest(): Boolean {
        awaitStateForTest("Setup after owned-worker cleanup") { it is HostingState.Setup }
        return true
    }

    private fun awaitBoundary(step: String, boundary: CountDownLatch) {
        assertTrue("$step did not arrive", boundary.await(5, TimeUnit.SECONDS))
    }

    private fun withHeldCreateResponse(
        body: String, action: (started: CountDownLatch, release: CountDownLatch) -> Unit,
    ) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(request.method == "POST") { "unexpected request while holding room creation" }
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "create-response gate timed out" }
                return MockResponse().setResponseCode(201).setBody(body)
            }
        }
        try { action(started, release) }
        finally { release.countDown() }
    }


    private fun mutationForTest(): QueueMutationContext =
        (serializedMutation ?: SerializedTestMutationContext("host-session-test-mutation")
            .also { serializedMutation = it }).mutationContext

    @org.junit.After
    fun cancelQueueScope() {
        queueScope.cancel()
        var failure: Throwable? = null
        for (observation in observations) {
            try { observation.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        try { serializedMutation?.close() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

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

    /** qmix#312: processed Stop cancels a dispatched recovery before its fetch begins. */
    @Test
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    fun stop_start_stop_cancels_queued_recovery_without_reopening_commands() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        data class QueuedRecovery(
            val runnable: Runnable, val cancelled: CountDownLatch, val completed: CountDownLatch,
        )
        val recoveryTasks = LinkedBlockingQueue<QueuedRecovery>()
        val recoveryDispatcher = object : CoroutineDispatcher() {
            private var released = false
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                synchronized(recoveryTasks) {
                    if (!released) {
                        val cancelled = CountDownLatch(1)
                        requireNotNull(context[Job]).invokeOnCompletion(
                            onCancelling = true, invokeImmediately = true,
                        ) { cause -> if (cause != null) cancelled.countDown() }
                        val completed = CountDownLatch(1)
                        requireNotNull(context[Job]).invokeOnCompletion { completed.countDown() }
                        recoveryTasks.add(QueuedRecovery(block, cancelled, completed))
                        return
                    }
                }
                Dispatchers.IO.dispatch(context, block)
            }
            fun release() {
                val pending = synchronized(recoveryTasks) {
                    released = true
                    buildList { while (true) add(recoveryTasks.poll() ?: break) }
                }
                pending.forEach { it.runnable.run() }
            }
        }
        val initial = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "", 1, "fixture")))
        val fetches = AtomicInteger()
        val recoveryEntered = CountDownLatch(1)
        val finishRecovery = CompletableDeferred<Unit>()
        val commands = AtomicInteger()
        val commandEntered = CountDownLatch(1)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { mutationDispatcher ->
            val controller = HostSessionController(
                OkHttpClient(), initialBackendUrl = server.url("/").toString(),
                initialGuestOrigin = "https://guest.example",
                roomRepositoryFactory = { repository }, roomCollectionScope = queueScope,
                roomCollectionContext = Dispatchers.IO,
                foregroundRecoveryContext = recoveryDispatcher,
                foregroundReconcilerFactory = { { _: String ->
                    fetches.incrementAndGet()
                    recoveryEntered.countDown()
                    finishRecovery.await()
                    RoomFetchResult.Success(initial)
                } },
                queueMutationContext = QueueMutationContext(mutationDispatcher),
                primaryActionHandler = { commands.incrementAndGet(); commandEntered.countDown() },
            )
            try {
                controller.createRoom()
                controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation }
                repository.awaitInvitationCollection()
                controller.enterRoom()
                repository.awaitCollection()
                val sync = RoomSyncState.Active("ABCD", initial, Freshness.FRESH, LiveConnection.CONNECTED)
                repository.publish(sync)
                controller.awaitLiveForTest("initial authoritative queue") { it.synchronization == sync }
                controller.onHostStopped()
                controller.onHostStarted()
                val cancelledRecovery = checkNotNull(recoveryTasks.poll(5, TimeUnit.SECONDS)) {
                    "foreground recovery worker was not dispatched"
                }
                controller.onHostStopped()
                // Offers do not cancel synchronously. Wait for Stop's actual Job cancellation,
                // then resume that cancelled dispatch so Stop can finish its full-worker join.
                awaitBoundary("queued recovery Job cancellation", cancelledRecovery.cancelled)
                cancelledRecovery.runnable.run()
                awaitBoundary("cancelled recovery worker completed", cancelledRecovery.completed)
                assertEquals("cancelled recovery fetched", 0, fetches.get())
                recoveryDispatcher.release()
                controller.onStartOrNext()
                controller.onHostStarted()
                // The next genuine recovery entry is FIFO after the stopped command.
                awaitBoundary("next foreground recovery fetch", recoveryEntered)
                assertEquals("only the next genuine recovery fetched", 1, fetches.get())
                assertEquals("command admitted while stopped", 0, commands.get())
                assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)

                finishRecovery.complete(Unit)
                controller.awaitLiveForTest("successful fresh recovery") { !it.foregroundRecoveryPending }
                assertEquals("foreground recovery fetches", 1, fetches.get())
                controller.onStartOrNext()
                awaitBoundary("command after successful recovery", commandEntered)
                assertEquals("command admitted after recovery", 1, commands.get())
            } finally {
                finishRecovery.complete(Unit)
                recoveryDispatcher.release()
                controller.endRoom()
                controller.awaitSetupAfterCleanupForTest()
            }
        }
    }

    @Test
    fun playback_callbacks_are_safe_without_an_active_coordinator() {
        val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(), httpClient = OkHttpClient())

        controller.onPlayPause()
        controller.onPlay()
        controller.onPause()
        controller.onSeekBy(-10_000)
        controller.onSeekBy(10_000)
        controller.onRetryCurrent()

        // This real settings publication is FIFO after all out-of-session callbacks.
        controller.updateSettings("https://backend.example", "https://guest.example")
        assertEquals(HostingState.Setup("https://backend.example", "https://guest.example"),
            controller.awaitStateForTest("callbacks leave setup editable") {
                it == HostingState.Setup("https://backend.example", "https://guest.example")
            })
    }

    @Test
    fun observer_failure_logs_sanitized_host_session_boundary() {
        val sink = RecordingLogSink()
        val logger = QMixLogger(sink, QMixLogLevel.DEBUG) { null }
            .component(QMixLogComponent.APP_HOST_SESSION)
        val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(), httpClient = OkHttpClient())

        observations += controller.collectStatesForTest(isolateFailures = true) {
            throw IllegalStateException("host_token=do-not-log")
        }

        assertTrue(sink.records.isEmpty())
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
        val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(),
            httpClient = OkHttpClient(),

            settingsPersistence = persistence,
        )
        controller.updateSettings(backend, "https://guest.example")

        controller.createRoom()
        controller.awaitStateForTest("HTTP warning before traffic") { it is HostingState.HttpWarning }
        assertEquals(
            HostingState.HttpWarning(backend.trimEnd('/'), "https://guest.example"),
            controller.state,
        )
        assertEquals(0, server.requestCount)
        controller.confirmHttpWarning()
        controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }

        assertTrue(persistence.warningAcknowledged)
        assertEquals(1, server.requestCount)
        assertEquals(
            EndpointSettings(backend.trimEnd('/'), "https://guest.example"),
            persistence.saved,
        )
        assertTrue(controller.state is HostingState.Invitation)

        val restored = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(),
            httpClient = OkHttpClient(),

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
        val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(),
            httpClient = OkHttpClient(),

            settingsPersistence = persistence,
        )
        controller.updateSettings("https://127.0.0.1:1/", "https://guest.example/")

        controller.createRoom()
        controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }

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
        val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(),
            httpClient = OkHttpClient(),

            settingsPersistence = persistence,
        )
        controller.updateSettings(backend, "https://guest.example")
        controller.createRoom()

        controller.confirmHttpWarning()
        controller.awaitStateForTest("failed durable acknowledgement") {
            it is HostingState.Error && it.message == UserMessage.PERSISTENCE_ERROR
        }
        assertTrue(persistence.warningAcknowledged)
        assertEquals(0, server.requestCount)

        controller.createRoom()
        controller.awaitStateForTest("quarantined acknowledgement reprompts") { it is HostingState.HttpWarning }
        assertEquals(
            HostingState.HttpWarning(backend.trimEnd('/'), "https://guest.example"),
            controller.state,
        )
        assertEquals(0, server.requestCount)

        persistence.failAcknowledgementAfterMutation = false
        controller.confirmHttpWarning()
        controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
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
        val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "warning-mutation") }
            .asCoroutineDispatcher()
        val controller = HostSessionController(
            OkHttpClient(),
            settingsPersistence = persistence,
            queueMutationContext = QueueMutationContext(dispatcher) {
                Thread.currentThread().name == "warning-mutation"
            },
        )
        controller.updateSettings(backend, "https://guest.example")
        controller.createRoom()
        controller.awaitStateForTest("warning awaiting durable confirmation") { it is HostingState.HttpWarning }

        val confirmationThread = Executors.newSingleThreadExecutor()
        val confirmation = confirmationThread.submit<Unit> { controller.confirmHttpWarning() }
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
            awaitBoundary("CancelWarning offer returns while confirmation runs", cancellationFinished)
            assertFalse(persistence.warningAcknowledged)
            assertEquals(0, server.requestCount)
            assertTrue(controller.state is HostingState.HttpWarning)

            persistence.allowAcknowledgement.countDown()

            confirmation.get(5, TimeUnit.SECONDS)
            assertTrue(cancellationFinished.await(5, TimeUnit.SECONDS))
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            assertTrue(persistence.warningAcknowledged)
            assertEquals(1, server.requestCount)
            assertTrue(controller.state is HostingState.Invitation)
        } finally {
            persistence.allowAcknowledgement.countDown()
            confirmationThread.shutdownNow()
            dispatcher.close()
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
            val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(),
                httpClient = OkHttpClient(),

                settingsPersistence = store,
            )
            controller.updateSettings(backend, guestOrigin)

            controller.createRoom()
            controller.awaitStateForTest("real-store HTTP warning") { it is HostingState.HttpWarning }
            assertTrue(controller.state is HostingState.HttpWarning)
            assertEquals(0, server.requestCount)
            controller.cancelHttpWarning()
            controller.awaitStateForTest("real-store warning cancellation") { it is HostingState.Setup }
            assertFalse(EndpointSettingsStore(context).isHttpWarningAcknowledged())
            assertEquals(0, server.requestCount)

            controller.createRoom()
            controller.confirmHttpWarning()
        controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
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
            val restored = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(),
                httpClient = OkHttpClient(),

                settingsPersistence = EndpointSettingsStore(context),
            )
            restored.observeForTest(restoredStates::add)
            assertEquals(HostingState.Setup(canonicalBackend, guestOrigin), restored.state)

            restored.createRoom()
            restored.awaitStateForTest("restored creation worker result") { it is HostingState.Invitation || it is HostingState.Error }

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
        val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(),
            httpClient = OkHttpClient(),

            settingsPersistence = failingStore,
        )
        val states = mutableListOf<HostingState>()
        controller.observeForTest(states::add)

        try {
            controller.updateSettings(server.url("/").toString(), "https://guest.example")
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }

            assertEquals(1, server.requestCount)
            assertEquals(UserMessage.PERSISTENCE_ERROR, (controller.state as HostingState.Error).message)
            assertFalse(states.any { it is HostingState.Invitation })
            assertEquals(EndpointSettings.EMPTY, failingStore.load())
            assertEquals(
                HostingState.Setup("", ""),
                HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(),
                    httpClient = OkHttpClient(),

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
        val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(), httpClient = OkHttpClient(), settingsPersistence = persistence)
        controller.updateSettings(backend, "http://192.168.1.20:8180")
        controller.createRoom()
        controller.awaitStateForTest("warning before cancellation") { it is HostingState.HttpWarning }

        controller.cancelHttpWarning()
        controller.awaitStateForTest("cancelled warning returns canonical setup") { it is HostingState.Setup }

        assertEquals(
            HostingState.Setup(backend.trimEnd('/'), "http://192.168.1.20:8180"),
            controller.state,
        )
        assertFalse(persistence.warningAcknowledged)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun credential_bearing_endpoint_is_rejected_without_retaining_secret_in_error_state() {
        val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(), httpClient = OkHttpClient())
        controller.updateSettings(
            "https://user:host-secret@api.example?host_token=host-secret",
            "https://guest.example",
        )

        controller.createRoom()
        controller.awaitStateForTest("credential-bearing endpoint rejected") { it is HostingState.Error }

        assertEquals(
            HostingState.Error(UserMessage.INVALID_ENDPOINT, "", ""),
            controller.state,
        )
        assertFalse(controller.state.toString().contains("host-secret"))
    }

    @Test
    fun repeated_create_while_pending_sends_exactly_one_post() {
        withHeldCreateResponse("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""") { started, release ->
            val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(), httpClient = OkHttpClient())
            controller.updateSettings(server.url("/").toString(), "https://guest.example")
            controller.createRoom()
            controller.awaitStateForTest("first create is pending") { it is HostingState.Pending }
            awaitBoundary("first room POST", started)
            controller.createRoom()
            // The duplicate offer precedes the worker result, and Pending rejects it.
            release.countDown()
            controller.awaitStateForTest("single room creation completed") { it is HostingState.Invitation }
            assertEquals(1, server.requestCount)
            assertEquals(
                HostingState.Invitation(GuestInvite("ABCD", "https://guest.example/r/ABCD")), controller.state,
            )
            assertFalse(controller.state.toString().contains("host-secret"))
        }
    }

    @Test
    fun settings_changes_during_pending_cannot_reenable_create() {
        withHeldCreateResponse("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""") { started, release ->
            val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(), httpClient = OkHttpClient())
            controller.updateSettings(server.url("/").toString(), "https://guest.example")
            controller.createRoom()
            controller.awaitStateForTest("create remains pending") { it is HostingState.Pending }
            awaitBoundary("original room POST", started)
            controller.updateSettings("https://other.example", "https://other.example")
            controller.createRoom()
            release.countDown()
            controller.awaitStateForTest("pending create keeps original endpoints") { it is HostingState.Invitation }
            assertEquals(1, server.requestCount)
            assertEquals(GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                (controller.state as HostingState.Invitation).invite)
        }
    }

    @Test
    fun lost_create_response_is_not_retried_automatically() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(), httpClient = OkHttpClient())
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val failed = CountDownLatch(1)
        controller.observeForTest { if (it is HostingState.Error) failed.countDown() }

        controller.createRoom()

        assertTrue(failed.await(5, TimeUnit.SECONDS))
        controller.awaitStateForTest("failed create worker fully settled") { it is HostingState.Error }
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
        val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(), httpClient = OkHttpClient())
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val invited = CountDownLatch(1)
        controller.observeForTest { if (it is HostingState.Invitation) invited.countDown() }
        controller.createRoom()
        assertTrue(invited.await(5, TimeUnit.SECONDS))

        controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
        controller.enterRoom()
        controller.awaitLiveForTest("invitation entered") { true }

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
        withHeldCreateResponse("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""") { started, release ->
            val persistence = RecordingEndpointPersistence().apply { warningAcknowledged = true }
            val controller = HostSessionController(roomCollectionScope = queueScope, queueMutationContext = mutationForTest(), httpClient = OkHttpClient(),
                settingsPersistence = persistence)
            val setup = HostingState.Setup(server.url("/").toString().trimEnd('/'), "https://guest.example")
            controller.updateSettings(setup.backendUrl, setup.guestOrigin)
            val invited = AtomicBoolean(false)
            controller.observeForTest { if (it is HostingState.Invitation) invited.set(true) }
            controller.createRoom()
            controller.awaitStateForTest("room POST pending before End") { it is HostingState.Pending }
            awaitBoundary("POST entered before cancellation", started)
            controller.endRoom()
            // Setup is a positive full-create-worker cancellation/join boundary, not a timeout
            // hoping that the delayed response never becomes an invitation.
            controller.awaitStateForTest("pending creation cancelled and joined") { it == setup }
            release.countDown()
            assertFalse(invited.get())
            assertEquals(setup, controller.state)
            assertEquals(null, persistence.saved)
        }
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

            roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope,
            roomCollectionContext = Dispatchers.Unconfined,
            queueMutationContext = mutationForTest(),
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                testQueueCoordinator(
                    sessionScope,
                    credentials.code,
                    credentials.hostToken,
                    command,
                    reconciler,
                    observer,
                    mutationForTest(),
                )
            },
        )
        controller.createRoom()
        controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
        repository.awaitInvitationCollection()
        controller.enterRoom()
        val room = RoomState(
            "ABCD",
            null,
            listOf(QueuedTrack("track-1", "https://example/1", "Title", "Artist", 60, "fixture")),
        )
        repository.awaitCollection()
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitLiveForTest("primary_action_uses_the_queue_coordinator_and_publishes_pending_state: live publication") { it.isPrimaryActionEnabled }

        controller.onStartOrNext()
        command.awaitCall()
        controller.awaitLiveForTest("queue command pending") { it.commandPending }

        assertEquals(1, command.callbacks.size)
        assertTrue((controller.state as HostingState.LiveRoom).commandPending)
        command.complete(QueueAdvanceCommandResult.Indeterminate)
        reconciler.awaitCall()
        reconciler.complete(RoomFetchResult.Success(room))
        controller.awaitLiveForTest("primary_action_uses_the_queue_coordinator_and_publishes_pending_state: live publication") { !it.commandPending }
        assertFalse((controller.state as HostingState.LiveRoom).commandPending)
    }

    /** qmix#312: the processed Stop publishes its coordinator's paused playback snapshot. */
    @Test
    fun stopping_a_playing_room_preserves_paused_playback_and_closes_recovery_gate() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        val engine = HostRecordingPlaybackEngine()
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = { repository }, roomCollectionScope = queueScope,
            roomCollectionContext = Dispatchers.Unconfined,
            queueMutationContext = mutationForTest(),
            playbackCoordinatorFactory = { backendUrl, credentials, observer, advance, sessionScope ->
                AuthoritativePlaybackCoordinator(
                    credentials.code, "${backendUrl.trimEnd('/')}/rooms/${credentials.code}/current/stream",
                    engine, { RoomFetchResult.Failure }, sessionScope,
                    mutationForTest(), advance, observer,
                )
            },
        )
        try {
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            repository.awaitInvitationCollection()
            controller.enterRoom()
            repository.awaitCollection()
            val room = RoomState("ABCD", CurrentTrack("one", 0, "playing", "One", "Artist"), emptyList())
            repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
            controller.awaitLiveForTest("stopping_a_playing_room_preserves_paused_playback_and_closes_recovery_gate: live publication") { it.synchronization == RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED) }
            engine.awaitPrepared(1)
            engine.awaitPlayed(1)
            assertEquals(listOf("one"), engine.prepared.map(PlaybackMedia::trackId))
            engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.READY, isPlaying = true))
            controller.awaitLiveForTest("stopping_a_playing_room_preserves_paused_playback_and_closes_recovery_gate: live publication") { it.playback.status == LocalPlaybackStatus.PLAYING }
            assertEquals(LocalPlaybackStatus.PLAYING, (controller.state as HostingState.LiveRoom).playback.status)

            controller.onHostStopped()
            controller.awaitLiveForTest("stop pauses playback and closes recovery gate") {
                it.foregroundRecoveryPending && it.playback.status == LocalPlaybackStatus.PAUSED
            }

            val stopped = controller.state as HostingState.LiveRoom
            assertEquals(1, engine.pauseCount)
            assertEquals(LocalPlaybackStatus.PAUSED, stopped.playback.status)
            assertTrue(stopped.foregroundRecoveryPending)
            assertFalse(stopped.commandPending)
        } finally {
            controller.endRoom()
            controller.awaitSetupAfterCleanupForTest()
        }
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
        val seekEntered = CountDownLatch(1)
        val releaseSeek = CountDownLatch(1)
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",

            roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope,
            roomCollectionContext = Dispatchers.Unconfined,
            foregroundReconcilerFactory = { reconciler::fetchRoom },
            queueMutationContext = mutationForTest(),
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                testQueueCoordinator(sessionScope, credentials.code, credentials.hostToken, command, reconciler, observer, mutationForTest())
            },
            playbackCoordinatorFactory = { backendUrl, credentials, observer, advanceAfterEnded, sessionScope ->
                AuthoritativePlaybackCoordinator(
                    credentials.code,
                    "${backendUrl.trimEnd('/')}/rooms/${credentials.code}/current/stream",
                    engine,
                    reconciler::fetchRoom,
                    sessionScope,
                    mutationForTest(),
                    advanceAfterEnded,
                    observer,
                )
            },
        )
        try {
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            repository.awaitInvitationCollection()
            controller.enterRoom()
            repository.awaitCollection()
            val initial = RoomState(
                "ABCD",
                CurrentTrack("one", 0, "playing", "One", "Artist"),
                listOf(QueuedTrack("two", "url", "Two", "Artist", 60, "fixture")),
            )
            repository.publish(RoomSyncState.Active("ABCD", initial, Freshness.FRESH, LiveConnection.CONNECTED))
            controller.awaitLiveForTest("returning_to_foreground_requires_its_own_successful_reconciliation_before_commands: live publication") { it.synchronization == RoomSyncState.Active("ABCD", initial, Freshness.FRESH, LiveConnection.CONNECTED) }
            engine.awaitPrepared(1)
            engine.awaitPlayed(1)

            repository.awaitDelivery(1)
            engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.READY,
                isPlaying = true, durationMs = 60_000, isSeekable = true))
            controller.awaitLiveForTest("playing seekable before queued lifecycle events") { it.playback.isSeekable }
            engine.beforeSeek = {
                engine.beforeSeek = null
                seekEntered.countDown()
                check(releaseSeek.await(5, TimeUnit.SECONDS)) { "seek-call gate timed out" }
            }
            controller.onSeekBy(1_000)
            awaitBoundary("real engine seek holds current handler", seekEntered)
            controller.onHostStopped()
            controller.onStartOrNext()
            repository.publish(RoomSyncState.Active("ABCD", initial, Freshness.STALE, LiveConnection.RECONNECTING))
            repository.awaitDelivery(2)
            controller.onHostStarted()
            releaseSeek.countDown()
            reconciler.awaitCall()
            controller.awaitLiveForTest("stop pauses before foreground recovery") {
                it.foregroundRecoveryPending && it.playback.status == LocalPlaybackStatus.PAUSED
            }
            assertEquals(1, engine.pauseCount)
            assertTrue(command.callbacks.isEmpty())
            // The old stream offered a tagged result after Stop was queued. Recovery's
            // real fetch entry proves that FIFO result was handled and rejected.
            assertEquals(Freshness.FRESH, (controller.roomSyncState as RoomSyncState.Active).freshness)
            assertEquals(listOf("ABCD"), reconciler.calls)
            reconciler.complete(RoomFetchResult.Failure)
            repository.awaitCollection()
            repository.publish(RoomSyncState.Active("ABCD", initial, Freshness.FRESH, LiveConnection.CONNECTED))
            reconciler.awaitCall()
            assertEquals(listOf("ABCD", "ABCD"), reconciler.calls)
            reconciler.complete(RoomFetchResult.Success(initial.copy(code = "WRONG")))
            repository.awaitCollection()
            controller.onStartOrNext()
            assertTrue(command.callbacks.isEmpty())
            assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
            repository.publish(RoomSyncState.Active("ABCD", initial, Freshness.FRESH, LiveConnection.CONNECTED))
            reconciler.awaitCall()
            assertEquals(listOf("ABCD", "ABCD", "ABCD"), reconciler.calls)
            val replacement = initial.copy(current = CurrentTrack("other", 0, "playing", "Other", "Artist"))
            // Foreground reconciliation is newer than the last stream snapshot. The
            // restarted collector must replay that authoritative room, not "one".
            repository.rememberSnapshot(RoomSyncState.Active("ABCD", replacement, Freshness.FRESH, LiveConnection.CONNECTED))
            reconciler.complete(RoomFetchResult.Success(replacement))
            // awaitCall only observes fetch entry; the IO-launched Unconfined
            // recovery may not have reduced the callback yet. The next collection
            // starts after queue/playback handoff and is the readiness boundary.
            repository.awaitCollection()

            controller.awaitLiveForTest("successful recovery hands off replacement paused") {
                !it.foregroundRecoveryPending && it.playback.trackId == "other" &&
                    it.playback.status == LocalPlaybackStatus.PAUSED
            }
            controller.onStartOrNext()
            command.awaitCall()
            controller.awaitLiveForTest("foreground command pending") { it.commandPending }
            assertEquals(1, command.callbacks.size)
            assertEquals(listOf("one", "other"), engine.prepared.map(PlaybackMedia::trackId))
            assertEquals(1, engine.playCount)
            assertEquals(LocalPlaybackStatus.PAUSED, (controller.state as HostingState.LiveRoom).playback.status)
        } finally {
            releaseSeek.countDown()
            controller.endRoom()
            controller.awaitSetupAfterCleanupForTest()
        }
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

            roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope,
            roomCollectionContext = Dispatchers.Unconfined,
            queueMutationContext = mutationForTest(),
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                testQueueCoordinator(sessionScope, credentials.code, credentials.hostToken, command, reconciler, observer, mutationForTest())
            },
            playbackCoordinatorFactory = { backendUrl, credentials, observer, advanceAfterEnded, sessionScope ->
                AuthoritativePlaybackCoordinator(
                    roomCode = credentials.code,
                    streamUrl = "${backendUrl.trimEnd('/')}/rooms/${credentials.code}/current/stream",
                    playbackEngine = engine,
                    reconciler = reconciler::fetchRoom,
                    parentScope = sessionScope,
                    mutationContext = mutationForTest(),
                    advanceAfterEnded = advanceAfterEnded,
                    observer = observer,
                )
            },
        )
        controller.createRoom()
        controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
        repository.awaitInvitationCollection()
        controller.enterRoom()
        repository.awaitCollection()
        repository.publish(
            RoomSyncState.Active(
                "ABCD",
                RoomState("ABCD", null, listOf(QueuedTrack("one", "url", "One", "Artist", 60, "fixture"))),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            ),
        )
        controller.awaitLiveForTest("start_command_followed_by_fresh_selection_starts_local_playback_once: live publication") { it.isPrimaryActionEnabled }

        controller.onStartOrNext()
        command.awaitCall()
        command.complete(QueueAdvanceCommandResult.Success)
        reconciler.awaitCall()
        val selected = RoomState(
            "ABCD",
            CurrentTrack("one", 0, "playing", "One", "Artist"),
            listOf(QueuedTrack("two", "url", "Two", "Artist", 60, "fixture")),
        )
        reconciler.complete(RoomFetchResult.Success(selected))
        repository.publish(RoomSyncState.Active("ABCD", selected, Freshness.FRESH, LiveConnection.CONNECTED))
        repository.publish(RoomSyncState.Active("ABCD", selected, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitLiveForTest("start_command_followed_by_fresh_selection_starts_local_playback_once: live publication") { it.playback.trackId == "one" }
        engine.awaitPrepared(1)
        engine.awaitPlayed(1)

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
        controller.awaitLiveForTest("engine READY is published as playing") {
            it.playback.status == LocalPlaybackStatus.PLAYING && it.playback.positionMs == 5_000L
        }
        assertEquals(LocalPlaybackStatus.PLAYING, (controller.state as HostingState.LiveRoom).playback.status)
        controller.onSeekBy(Long.MIN_VALUE)
        engine.awaitSeeks(1)
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
        controller.awaitLiveForTest("latest seekable engine position") { it.playback.positionMs == 55_000L }
        controller.onSeekBy(Long.MAX_VALUE)
        engine.awaitSeeks(2)
        assertEquals(listOf(0L, 60_000L), engine.seeks)
        controller.onPlayPause()
        engine.awaitPaused(1)
        controller.awaitLiveForTest("engine.awaitPaused(1) playback publication") { it.playback.status == LocalPlaybackStatus.PAUSED }
        assertEquals(1, engine.pauseCount)
        assertEquals(LocalPlaybackStatus.PAUSED, (controller.state as HostingState.LiveRoom).playback.status)
        controller.onPlayPause()
        engine.awaitPlayed(2)
        controller.awaitLiveForTest("engine.awaitPlayed(2) playback publication") { it.playback.status == LocalPlaybackStatus.BUFFERING }
        assertEquals(2, engine.playCount)
        assertEquals(LocalPlaybackStatus.BUFFERING, (controller.state as HostingState.LiveRoom).playback.status)
        controller.pausePlayback()
        engine.awaitPaused(2)
        controller.awaitLiveForTest("engine.awaitPaused(2) playback publication") { it.playback.status == LocalPlaybackStatus.PAUSED }
        assertEquals(2, engine.pauseCount)
        assertEquals(LocalPlaybackStatus.PAUSED, (controller.state as HostingState.LiveRoom).playback.status)
        controller.resumePlayback()
        engine.awaitPlayed(3)
        controller.awaitLiveForTest("engine.awaitPlayed(3) playback publication") { it.playback.status == LocalPlaybackStatus.BUFFERING }
        assertEquals(3, engine.playCount)
        assertEquals(LocalPlaybackStatus.BUFFERING, (controller.state as HostingState.LiveRoom).playback.status)

        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.ERROR,
                error = PlaybackError(PlaybackErrorKind.NETWORK, "offline"),
            ),
        )
        controller.awaitLiveForTest("engine error remains visible") { it.playback.status == LocalPlaybackStatus.ERROR }
        val failedPlayback = (controller.state as HostingState.LiveRoom).playback
        controller.onPause()
        controller.onPlay()
        controller.onPlayPause()
        controller.onStartOrNext()
        command.awaitCall(2)
        assertEquals(failedPlayback, (controller.state as HostingState.LiveRoom).playback)
        assertEquals(2, engine.pauseCount)
        assertEquals(3, engine.playCount)
        assertEquals(1, engine.prepared.size)
        controller.onStartOrNext()
        controller.awaitLiveForTest("start_command_followed_by_fresh_selection_starts_local_playback_once: live publication") { it.commandPending }
        assertEquals(2, command.callbacks.size)
        assertEquals(LocalPlaybackStatus.ERROR, (controller.state as HostingState.LiveRoom).playback.status)

        val staleReplacement = selected.copy(current = CurrentTrack("two", 0, "playing", "Two", "Artist"))
        repository.publish(
            RoomSyncState.Active("ABCD", staleReplacement, Freshness.STALE, LiveConnection.RECONNECTING),
        )
        controller.awaitLiveForTest("start_command_followed_by_fresh_selection_starts_local_playback_once: live publication") { live ->
            (live.synchronization as? RoomSyncState.Active)?.let { sync ->
                sync.freshness == Freshness.STALE && sync.room?.current?.trackId == "two"
            } == true
        }
        val presentation = controller.state as HostingState.LiveRoom
        assertEquals(Freshness.STALE, (presentation.synchronization as RoomSyncState.Active).freshness)
        assertEquals("two", presentation.synchronization.room?.current?.trackId)
        assertEquals(listOf("one"), engine.prepared.map(PlaybackMedia::trackId))

        controller.onRetryCurrent()
        reconciler.awaitCall()
        assertEquals(listOf("ABCD", "ABCD"), reconciler.calls)
        reconciler.complete(RoomFetchResult.Success(selected))
        // Await the real retry prepare/play pair and publication before inspecting ENDED.
        engine.awaitPrepared(2)
        engine.awaitPlayed(4)
        controller.awaitLiveForTest("retry returns to buffering") { it.playback.status == LocalPlaybackStatus.BUFFERING }
        assertEquals(listOf("one", "one"), engine.prepared.map(PlaybackMedia::trackId))
        assertEquals(4, engine.playCount)
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.ENDED))
        controller.endRoom()
        // End joins the still-pending second command and the ENDED callback, so the
        // existing in-flight advance cannot dispatch another POST.
        assertTrue(controller.awaitSetupAfterCleanupForTest())
        assertEquals(2, command.callbacks.size)
        assertEquals(3, engine.pauseCount)
        assertTrue(repository.closed)
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
            roomCollectionScope = queueScope,
            roomCollectionContext = Dispatchers.Unconfined,
            queueMutationContext = mutationForTest(),
        )
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val invited = CountDownLatch(1)
        controller.observeForTest { if (it is HostingState.Invitation) invited.countDown() }
        controller.createRoom()
        assertTrue(invited.await(5, TimeUnit.SECONDS))

        controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
        repository.awaitInvitationCollection()
        controller.enterRoom()
        val synchronized = RoomSyncState.Active(
            "ABCD",
            RoomState("ABCD", null, emptyList()),
            Freshness.FRESH,
            LiveConnection.CONNECTED,
        )
        repository.awaitCollection()
        repository.publish(synchronized)
        controller.awaitLiveForTest("room_sync_is_application_session_owned_and_canceled_when_session_ends: live publication") { it.synchronization == synchronized }

        assertEquals("ABCD", repository.observedCode)
        assertEquals(synchronized, controller.roomSyncState)
        controller.endRoom()
        assertTrue(controller.awaitSetupAfterCleanupForTest())
        assertTrue(repository.closed)
        assertEquals(
            HostingState.Setup(server.url("/").toString().trimEnd('/'), "https://guest.example"),
            controller.state,
        )
        repository.publish(synchronized.copy(freshness = Freshness.STALE))
        assertEquals(null, controller.roomSyncState)
    }

    /** qmix#179: one active host session owns exactly one Flow collector. */
    @Test
    fun room_sync_flow_has_one_active_collector_per_generation_and_cancels_on_end() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = FlowRoomRepository()
        val controller = HostSessionController(queueMutationContext = mutationForTest(),
            httpClient = OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",

            roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope,
            roomCollectionContext = Dispatchers.Unconfined,
        )

        controller.createRoom()
        controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
        repository.awaitSubscription()
        controller.enterRoom()
        repository.awaitSecondSubscription()
        repository.publish(
            RoomSyncState.Active(
                "ABCD",
                RoomState("ABCD", null, emptyList()),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            ),
        )

        assertEquals(2, repository.collections)
        assertEquals(1, repository.cancellations)
        assertEquals(1, repository.activeCollectors)
        controller.endRoom()
        // End is offered immediately; its handler joins the entire collector tree before Setup.
        assertTrue(controller.awaitSetupAfterCleanupForTest())
        assertEquals(2, repository.cancellations)
        assertEquals(0, repository.activeCollectors)
    }

    @Test
    fun ending_room_returns_before_collection_cleanup_but_setup_waits() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = BlockingCleanupRoomRepository()
        val controller = HostSessionController(queueMutationContext = mutationForTest(),
            httpClient = OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",

            roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope,
            roomCollectionContext = Dispatchers.IO,
        )
        val endReturned = CountDownLatch(1)
        val endThread = Executors.newSingleThreadExecutor()

        try {
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            assertTrue(repository.collectionStarted.await(5, TimeUnit.SECONDS))
            endThread.execute {
                controller.endRoom()
                endReturned.countDown()
            }
            assertTrue(repository.cleanupStarted.await(5, TimeUnit.SECONDS))
            assertTrue("endRoom must not wait for cleanup", endReturned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.state is HostingState.Ending)

            repository.allowCleanup.countDown()
            assertTrue(endReturned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.awaitSetupAfterCleanupForTest())
            assertTrue(repository.cleanupCompleted.get())
            assertTrue(controller.awaitSetupAfterCleanupForTest())
        } finally {
            repository.allowCleanup.countDown()
            endThread.shutdownNow()
        }
    }

    /** qmix#182: a queued host command is part of the session, not only cancelled on close. */
    @Test
    fun rejected_queue_command_can_exit_reentrantly_from_its_callback() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        val finishCommand = CompletableDeferred<Unit>()
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "host-mutation").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val sawPending = AtomicBoolean(false)
        val pendingObserved = CountDownLatch(1)
        val mutation = QueueMutationContext(dispatcher) {
            Thread.currentThread().name.startsWith("host-mutation")
        }
        val endedFromCollector = AtomicBoolean(false)
        val teardownCallback = CountDownLatch(1)
        lateinit var controller: HostSessionController
        controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope, roomCollectionContext = Dispatchers.Unconfined,
            queueMutationContext = mutation,
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                QueueAdvancementCoordinator(credentials.code, credentials.hostToken,
                    QueueAdvanceCommand { _, _ ->
                        finishCommand.await()
                        QueueAdvanceCommandResult.Rejected
                    },
                    QueueRoomReconciler { RoomFetchResult.Failure }, { update ->
                        observer(update)
                        if (update.pending) { sawPending.set(true); pendingObserved.countDown() }
                        else if (sawPending.get()) {
                            endedFromCollector.set(true)
                            controller.onBack()
                            teardownCallback.countDown()
                        }
                    },
                    sessionScope, mutation)
            },
        )
        try {
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            repository.awaitInvitationCollection()
            controller.enterRoom()
            val room = RoomState("ABCD", null,
                listOf(QueuedTrack("one", "https://example/one", "One", "", 1, "fixture")))
            repository.awaitCollection()
            repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
            controller.awaitLiveForTest("rejected_queue_command_can_exit_reentrantly_from_its_callback: live publication") { it.isPrimaryActionEnabled }
            controller.onStartOrNext()
            controller.awaitLiveForTest("owned queue command pending") { it.commandPending }
            awaitBoundary("queue observer pending callback", pendingObserved)
            assertTrue("pending state was not observed", sawPending.get())
            // Resume on a daemon so the RED self-join cannot wedge the test JVM.
            val returned = CountDownLatch(1)
            Thread({ finishCommand.complete(Unit); returned.countDown() }, "reentrant-queue-probe")
                .apply { isDaemon = true }.start()
            assertTrue("queue callback joined its own owner", returned.await(3, TimeUnit.SECONDS))
            assertTrue("teardown callback was not reached", teardownCallback.await(5, TimeUnit.SECONDS))
            assertTrue(endedFromCollector.get())
            assertTrue(controller.awaitSetupAfterCleanupForTest())
        } finally {
            finishCommand.complete(Unit)
            controller.endRoom()
            controller.awaitSetupAfterCleanupForTest()
            dispatcher.close()
        }
    }

    /** qmix#182: the callback returns promptly; Setup waits for the entire owner. */
    @Test
    fun reentrant_queue_callback_exits_immediately_but_setup_waits_for_owner() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
        val room = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "", 1, "fixture")))
        val invitationStarted = CountDownLatch(1)
        val collectionStarted = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val cleanupCompleted = AtomicBoolean(false)
        val collections = AtomicInteger()
        val repository = object : RoomRepository {
            override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                val generation = collections.incrementAndGet()
                if (generation == 1) {
                    invitationStarted.countDown()
                    kotlinx.coroutines.awaitCancellation()
                } else {
                    collectionStarted.countDown()
                    try {
                        emit(RoomSyncState.Active(roomCode, room, Freshness.FRESH, LiveConnection.CONNECTED))
                        kotlinx.coroutines.awaitCancellation()
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            cleanupStarted.countDown()
                            check(allowCleanup.await(5, TimeUnit.SECONDS))
                            cleanupCompleted.set(true)
                        }
                    }
                }
            }
        }
        val finishCommand = CompletableDeferred<Unit>()
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "joined-host-mutation").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val mutation = QueueMutationContext(dispatcher) {
            Thread.currentThread().name.startsWith("joined-host-mutation")
        }
        val callbackReached = CountDownLatch(1)
        val queueReady = CountDownLatch(1)
        val teardownReturned = CountDownLatch(1)
        val sawPending = AtomicBoolean(false)
        val exitCompleted = CountDownLatch(1)
        val pendingObserved = CountDownLatch(1)
        val teardownResult = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        lateinit var controller: HostSessionController
        controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope, roomCollectionContext = Dispatchers.IO,
            queueMutationContext = mutation,
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                QueueAdvancementCoordinator(credentials.code, credentials.hostToken,
                    QueueAdvanceCommand { _, _ ->
                        finishCommand.await()
                        QueueAdvanceCommandResult.Rejected
                    }, QueueRoomReconciler { RoomFetchResult.Failure }, observer,
                    sessionScope, mutation)
            },
        )
        val observation = controller.observeForTest { state ->
            val live = state as? HostingState.LiveRoom ?: return@observeForTest
            if (live.isPrimaryActionEnabled) queueReady.countDown()
            if (live.commandPending) {
                sawPending.set(true)
                pendingObserved.countDown()
            } else if (sawPending.get() && callbackReached.count > 0L) {
                callbackReached.countDown()
                controller.onBack { teardownResult.set(true); exitCompleted.countDown() }
                teardownReturned.countDown()
            }
        }
        try {
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            assertTrue("invitation collection did not start", invitationStarted.await(5, TimeUnit.SECONDS))
            controller.enterRoom()
            assertTrue("live collection did not start", collectionStarted.await(5, TimeUnit.SECONDS))
            assertEquals(2, collections.get())
            assertTrue("authoritative queue was not delivered", queueReady.await(5, TimeUnit.SECONDS))
            controller.onStartOrNext()
            controller.awaitLiveForTest("owned queue command pending") { it.commandPending }
            awaitBoundary("observer sees owned queue pending", pendingObserved)
            assertTrue("pending state was not observed", sawPending.get())

            finishCommand.complete(Unit)
            assertTrue("real queue-child callback was not reached", callbackReached.await(5, TimeUnit.SECONDS))
            assertTrue("owned collection cleanup did not start", cleanupStarted.await(5, TimeUnit.SECONDS))
            assertTrue("reentrant teardown must return before owner cleanup", teardownReturned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.state is HostingState.Ending)
            assertEquals("Back exit must wait for full owner cleanup", null, teardownResult.get())

            allowCleanup.countDown()
            assertTrue("reentrant teardown did not return", teardownReturned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.awaitSetupAfterCleanupForTest())
            awaitBoundary("Back explicit exit effect after cleanup", exitCompleted)
            assertEquals(true, teardownResult.get())
            assertTrue("owner cleanup was incomplete at readiness", cleanupCompleted.get())
            assertTrue(controller.awaitSetupAfterCleanupForTest())
        } finally {
            finishCommand.complete(Unit)
            allowCleanup.countDown()
            observation.close()
            dispatcher.close()
        }
    }

    /** qmix#182: a player-report child may synchronously notify a host callback. */
    @Test
    fun conflicted_player_report_can_end_room_reentrantly_from_its_callback() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        val engine = HostRecordingPlaybackEngine()
        val finishReport = CompletableDeferred<Unit>()
        val endedFromCallback = AtomicBoolean(false)
        lateinit var controller: HostSessionController
        controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope, roomCollectionContext = Dispatchers.Unconfined,
            queueMutationContext = mutationForTest(),
            playbackCoordinatorFactory = { _, credentials, observer, advance, sessionScope ->
                val mutation = mutationForTest()
                AuthoritativePlaybackCoordinator(credentials.code, "https://example/stream", engine,
                    { RoomFetchResult.Failure }, sessionScope, mutation, advance, { playback ->
                        observer(playback)
                        if (!playback.reportSynchronized) {
                            endedFromCallback.set(true)
                            controller.endRoom()
                        }
                    }, statePublisherFactory = { listener ->
                        PlayerStatePublisher(credentials.code, credentials.hostToken,
                            { _, _, _ -> finishReport.await(); PlayerReportResult.CONFLICT },
                            sessionScope, mutation, queueScope, listener)
                    })
            },
        )
        controller.createRoom()
        controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
        repository.awaitInvitationCollection()
        controller.enterRoom()
        repository.awaitCollection()
        repository.publish(RoomSyncState.Active("ABCD",
            RoomState("ABCD", CurrentTrack("one", 0, "playing", "One", "Artist"), emptyList()),
            Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitLiveForTest("conflicted_player_report_can_end_room_reentrantly_from_its_callback: live publication") { it.playback.trackId == "one" }
        engine.awaitPrepared(1)
        engine.awaitPlayed(1)
        engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.READY, isPlaying = true))
        val returned = CountDownLatch(1)
        Thread({ finishReport.complete(Unit); returned.countDown() }, "reentrant-report-probe")
            .apply { isDaemon = true }.start()
        assertTrue("player report callback joined its own owner", returned.await(3, TimeUnit.SECONDS))
        assertTrue(controller.awaitSetupAfterCleanupForTest())
        assertTrue("teardown callback was not reached", endedFromCallback.get())
    }

    /** qmix#182: a queued host command is part of the session, not only cancelled on close. */
    @Test
    fun ending_room_returns_before_queue_cleanup_but_setup_waits() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        val started = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val cleaned = AtomicBoolean(false)
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope, roomCollectionContext = Dispatchers.Unconfined,
            queueMutationContext = mutationForTest(),
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                QueueAdvancementCoordinator(credentials.code, credentials.hostToken,
                    QueueAdvanceCommand { _, _ ->
                        started.countDown()
                        try { kotlinx.coroutines.awaitCancellation() }
                        finally {
                            withContext(NonCancellable + Dispatchers.IO) {
                                cleanupStarted.countDown()
                                check(allowCleanup.await(5, TimeUnit.SECONDS))
                                cleaned.set(true)
                            }
                        }
                    }, QueueRoomReconciler { RoomFetchResult.Failure }, observer,
                    sessionScope, mutationForTest())
            },
        )
        val returned = CountDownLatch(1)
        val endThread = Executors.newSingleThreadExecutor()
        try {
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            repository.awaitInvitationCollection()
            controller.enterRoom()
            val room = RoomState("ABCD", null,
                listOf(QueuedTrack("one", "https://example/one", "One", "", 1, "fixture")))
            repository.awaitCollection()
            repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
            controller.awaitLiveForTest("ending_room_returns_before_queue_cleanup_but_setup_waits: live publication") { it.isPrimaryActionEnabled }
            controller.onStartOrNext()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            endThread.execute { controller.endRoom(); returned.countDown() }
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.state is HostingState.Ending)
            allowCleanup.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.awaitSetupAfterCleanupForTest())
            assertTrue(cleaned.get())
            assertTrue(controller.awaitSetupAfterCleanupForTest())
        } finally {
            allowCleanup.countDown()
            endThread.shutdownNow()
        }
    }

    /** qmix#182: reporting belongs to the same room owner as queue and repository work. */
    @Test
    fun ending_room_returns_before_report_cleanup_but_setup_waits() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        val engine = HostRecordingPlaybackEngine()
        val reportStarted = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val cleaned = AtomicBoolean(false)
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope, roomCollectionContext = Dispatchers.Unconfined,
            queueMutationContext = mutationForTest(),
            playbackCoordinatorFactory = { _, credentials, observer, advance, sessionScope ->
                val mutation = mutationForTest()
                AuthoritativePlaybackCoordinator(credentials.code, "https://example/stream", engine,
                    { RoomFetchResult.Failure }, sessionScope, mutation, advance, observer,
                    statePublisherFactory = { listener ->
                        PlayerStatePublisher(credentials.code, credentials.hostToken,
                            { _, _, _ ->
                                reportStarted.countDown()
                                try { kotlinx.coroutines.awaitCancellation() }
                                finally {
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        cleanupStarted.countDown()
                                        check(allowCleanup.await(5, TimeUnit.SECONDS))
                                        cleaned.set(true)
                                    }
                                }
                            }, sessionScope, mutation, queueScope, listener)
                    })
            },
        )
        val returned = CountDownLatch(1)
        val endThread = Executors.newSingleThreadExecutor()
        try {
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            repository.awaitInvitationCollection()
            controller.enterRoom()
            repository.awaitCollection()
            repository.publish(RoomSyncState.Active("ABCD",
                RoomState("ABCD", CurrentTrack("one", 0, "playing", "One", "Artist"), emptyList()),
                Freshness.FRESH, LiveConnection.CONNECTED))
            controller.awaitLiveForTest("ending_room_returns_before_report_cleanup_but_setup_waits: live publication") { it.playback.trackId == "one" }
            engine.awaitPrepared(1)
            engine.awaitPlayed(1)
            engine.emit(PlaybackState(mediaId = "one", status = PlaybackStatus.READY, isPlaying = true))
            assertTrue(reportStarted.await(5, TimeUnit.SECONDS))
            endThread.execute { controller.endRoom(); returned.countDown() }
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.state is HostingState.Ending)
            allowCleanup.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.awaitSetupAfterCleanupForTest())
            assertTrue(cleaned.get())
            assertTrue(controller.awaitSetupAfterCleanupForTest())
        } finally {
            allowCleanup.countDown()
            endThread.shutdownNow()
        }
    }

    @Test
    fun concurrent_stop_calls_return_before_the_same_collection_cleanup() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = BlockingCleanupRoomRepository(blockedGeneration = 2)
        val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "stop-mutation") }
            .asCoroutineDispatcher()
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",

            roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope,
            roomCollectionContext = Dispatchers.IO,
            queueMutationContext = QueueMutationContext(dispatcher) {
                Thread.currentThread().name == "stop-mutation"
            },
        )
        val begin = CountDownLatch(1)
        val returned = CountDownLatch(2)
        val callers = Executors.newFixedThreadPool(2)

        try {
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            assertTrue("invitation collection did not start", repository.invitationStarted.await(5, TimeUnit.SECONDS))
            controller.enterRoom()
            assertTrue("live collection did not start", repository.collectionStarted.await(5, TimeUnit.SECONDS))
            assertEquals(2, repository.collections.get())
            repeat(2) {
                callers.execute {
                    begin.await()
                    controller.onHostStopped()
                    returned.countDown()
                }
            }
            begin.countDown()
            assertTrue(repository.cleanupStarted.await(5, TimeUnit.SECONDS))
            assertTrue("stop must not wait for cleanup", returned.await(5, TimeUnit.SECONDS))

            repository.allowCleanup.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertTrue(repository.cleanupDone.await(5, TimeUnit.SECONDS))
            assertTrue(repository.cleanupCompleted.get())
        } finally {
            repository.allowCleanup.countDown()
            callers.shutdownNow()
            dispatcher.close()
        }
    }

    @Test
    fun room_collection_can_end_the_session_reentrantly_without_deadlock() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = FlowRoomRepository()
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",

            roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope,
            roomCollectionContext = Dispatchers.IO,
            queueMutationContext = mutationForTest(),
        )
        val ended = CountDownLatch(1)
        val observation = controller.observeForTest { state ->
            val sync = (state as? HostingState.LiveRoom)?.synchronization as? RoomSyncState.Active
            if (sync?.freshness == Freshness.FRESH) {
                controller.endRoom()
                ended.countDown()
            }
        }
        try {
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            repository.awaitSubscription()
            controller.enterRoom()
            repository.awaitSecondSubscription()

            repository.publish(
                RoomSyncState.Active(
                    "ABCD",
                    RoomState("ABCD", null, emptyList()),
                    Freshness.FRESH,
                    LiveConnection.CONNECTED,
                ),
            )

            assertTrue(ended.await(5, TimeUnit.SECONDS))
            // End only offers an event; Setup is published after its handler joins the collection Job.
            assertTrue(controller.awaitSetupAfterCleanupForTest())
            assertEquals(2, repository.cancellations)
            assertEquals(0, repository.activeCollectors)
        } finally {
            observation.close()
            if (controller.state !is HostingState.Setup) controller.endRoom()
            controller.awaitSetupAfterCleanupForTest()
        }
    }

    /** qmix#182: a nested playback callback cannot join the collecting Job. */
    @Test
    fun nested_playback_callback_can_end_room_on_shared_mutation_dispatcher() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
        val room = RoomState("ABCD", CurrentTrack("one", 0, "playing", "One", "Artist"), emptyList())
        val collecting = CountDownLatch(1)
        val emitted = CountDownLatch(1)
        val repository = object : RoomRepository {
            override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                collecting.countDown()
                emit(RoomSyncState.Active(roomCode, room, Freshness.FRESH, LiveConnection.CONNECTED))
                emitted.countDown()
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val engine = HostRecordingPlaybackEngine()
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "nested-host-mutation").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val mutation = QueueMutationContext(dispatcher) {
            Thread.currentThread().name.startsWith("nested-host-mutation")
        }
        val entered = CountDownLatch(1)
        val ended = CountDownLatch(1)
        lateinit var controller: HostSessionController
        controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope, roomCollectionContext = Dispatchers.IO,
            queueMutationContext = mutation,
            playbackCoordinatorFactory = { _, credentials, observer, advance, sessionScope ->
                AuthoritativePlaybackCoordinator(credentials.code, "https://example/stream", engine,
                    { RoomFetchResult.Failure }, sessionScope, mutation, advance, { playback ->
                        observer(playback)
                        if (playback.status == LocalPlaybackStatus.BUFFERING) {
                            entered.countDown()
                            controller.endRoom()
                            ended.countDown()
                        }
                    })
            },
        )
        try {
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            controller.enterRoom()
            assertTrue("room flow did not start", collecting.await(5, TimeUnit.SECONDS))
            assertTrue("nested playback callback was not reached: state=${controller.state}",
                entered.await(5, TimeUnit.SECONDS))
            assertTrue("nested callback joined the collecting Job", ended.await(5, TimeUnit.SECONDS))
            assertTrue(controller.awaitSetupAfterCleanupForTest())
        } finally {
            dispatcher.close()
        }
    }

    /** qmix#182: foreground-loss reentrancy is non-blocking; Setup is delayed. */
    @Test
    fun foreground_loss_callback_ending_room_defers_setup_until_cleanup() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        val commandStarted = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val cleanupCompleted = AtomicBoolean(false)
        val callbackReached = CountDownLatch(1)
        val endReturned = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val setupPublished = CountDownLatch(1)
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "foreground-host-mutation").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val mutation = QueueMutationContext(dispatcher) {
            Thread.currentThread().name.startsWith("foreground-host-mutation")
        }
        lateinit var controller: HostSessionController
        controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope, roomCollectionContext = Dispatchers.Unconfined,
            queueMutationContext = mutation,
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                QueueAdvancementCoordinator(credentials.code, credentials.hostToken,
                    QueueAdvanceCommand { _, _ ->
                        commandStarted.countDown()
                        try { kotlinx.coroutines.awaitCancellation() }
                        finally {
                            withContext(NonCancellable + Dispatchers.IO) {
                                cleanupStarted.countDown()
                                check(allowCleanup.await(5, TimeUnit.SECONDS))
                                cleanupCompleted.set(true)
                            }
                        }
                    }, QueueRoomReconciler { RoomFetchResult.Failure }, { update ->
                        observer(update)
                        if (!update.pending && callbackReached.count > 0L) {
                            callbackReached.countDown()
                            controller.endRoom()
                            endReturned.countDown()
                        }
                    }, sessionScope, mutation)
            },
        )
        val caller = Executors.newSingleThreadExecutor()
        val observation = controller.observeForTest { state ->
            if (state is HostingState.Setup && cleanupCompleted.get()) setupPublished.countDown()
        }
        try {
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            repository.awaitInvitationCollection()
            controller.enterRoom()
            val room = RoomState("ABCD", null,
                listOf(QueuedTrack("one", "https://example/one", "One", "", 1, "fixture")))
            repository.awaitCollection()
            repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
            controller.awaitLiveForTest("authoritative queue before owned command") { it.isPrimaryActionEnabled }
            controller.onStartOrNext()
            assertTrue("owned queue command did not start", commandStarted.await(5, TimeUnit.SECONDS))

            caller.execute { controller.onHostStopped(); stopReturned.countDown() }
            assertTrue("foreground-loss callback was not reached", callbackReached.await(5, TimeUnit.SECONDS))
            assertTrue("owned cleanup did not start", cleanupStarted.await(5, TimeUnit.SECONDS))
            assertTrue("endRoom must return before owned cleanup", endReturned.await(5, TimeUnit.SECONDS))
            assertTrue("onHostStopped must return before owned cleanup", stopReturned.await(5, TimeUnit.SECONDS))
            // End offered by the coordinator cannot preempt Stop, which is joining
            // the foreground command's cleanup before it can handle the queued End.
            val stopped = controller.state as HostingState.LiveRoom
            assertTrue(stopped.foregroundRecoveryPending)
            assertFalse(stopped.commandPending)
            assertFalse(cleanupCompleted.get())
            assertEquals(1L, setupPublished.count)

            allowCleanup.countDown()
            assertTrue("reentrant endRoom did not return", endReturned.await(5, TimeUnit.SECONDS))
            assertTrue("onHostStopped did not return", stopReturned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.awaitSetupAfterCleanupForTest())
            assertTrue("owned cleanup was incomplete at readiness", cleanupCompleted.get())
            assertTrue("Setup was not published after cleanup", setupPublished.await(5, TimeUnit.SECONDS))
            assertTrue("stale LiveRoom published after Setup", controller.awaitSetupAfterCleanupForTest())
        } finally {
            allowCleanup.countDown()
            observation.close()
            caller.shutdownNow()
            dispatcher.close()
        }
    }

    /** qmix#182: a room-collection callback never joins; the finalizer waits for siblings. */
    @Test
    fun room_collection_callback_ending_room_defers_setup_until_sibling_cleanup() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        val engine = HostRecordingPlaybackEngine()
        val commandStarted = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val cleanupCompleted = AtomicBoolean(false)
        val callbackReached = CountDownLatch(1)
        val endReturned = CountDownLatch(1)
        val queueReady = CountDownLatch(1)
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "collection-sibling-mutation").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val mutation = QueueMutationContext(dispatcher) {
            Thread.currentThread().name.startsWith("collection-sibling-mutation")
        }
        lateinit var controller: HostSessionController
        controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope, roomCollectionContext = Dispatchers.IO,
            queueMutationContext = mutation,
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                QueueAdvancementCoordinator(credentials.code, credentials.hostToken,
                    QueueAdvanceCommand { _, _ ->
                        commandStarted.countDown()
                        try { kotlinx.coroutines.awaitCancellation() }
                        finally {
                            withContext(NonCancellable + Dispatchers.IO) {
                                cleanupStarted.countDown()
                                check(allowCleanup.await(5, TimeUnit.SECONDS))
                                cleanupCompleted.set(true)
                            }
                        }
                    }, QueueRoomReconciler { RoomFetchResult.Failure }, observer, sessionScope, mutation)
            },
            playbackCoordinatorFactory = { _, credentials, observer, advance, sessionScope ->
                AuthoritativePlaybackCoordinator(credentials.code, "https://example/stream", engine,
                    { RoomFetchResult.Failure }, sessionScope, mutation, advance, { playback ->
                        observer(playback)
                        if (playback.status == LocalPlaybackStatus.BUFFERING && callbackReached.count > 0L) {
                            callbackReached.countDown()
                            controller.endRoom()
                            endReturned.countDown()
                        }
                    })
            },
        )
        val observation = controller.observeForTest { state ->
            if ((state as? HostingState.LiveRoom)?.isPrimaryActionEnabled == true) queueReady.countDown()
        }
        try {
            controller.createRoom()
            controller.awaitStateForTest("creation worker result") { it is HostingState.Invitation || it is HostingState.Error }
            repository.awaitInvitationCollection()
            controller.enterRoom()
            repository.awaitCollection()
            val queued = QueuedTrack("one", "https://example/one", "One", "", 1, "fixture")
            repository.publish(RoomSyncState.Active("ABCD", RoomState("ABCD", null, listOf(queued)),
                Freshness.FRESH, LiveConnection.CONNECTED))
            assertTrue("authoritative queue was not delivered", queueReady.await(5, TimeUnit.SECONDS))
            controller.onStartOrNext()
            assertTrue("sibling queue command did not start", commandStarted.await(5, TimeUnit.SECONDS))

            repository.publish(RoomSyncState.Active("ABCD",
                RoomState("ABCD", CurrentTrack("one", 0, "playing", "One", "Artist"), emptyList()),
                Freshness.FRESH, LiveConnection.CONNECTED))
            assertTrue("room-collection callback was not reached", callbackReached.await(5, TimeUnit.SECONDS))
            assertTrue("sibling cleanup did not start", cleanupStarted.await(5, TimeUnit.SECONDS))
            assertTrue("room must remain Ending until sibling cleanup", controller.state is HostingState.Ending)
            assertTrue("endRoom must return before sibling cleanup", endReturned.await(5, TimeUnit.SECONDS))

            allowCleanup.countDown()
            assertTrue("reentrant endRoom did not return", endReturned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.awaitSetupAfterCleanupForTest())
            assertTrue("sibling cleanup was incomplete at readiness", cleanupCompleted.get())
            assertTrue(controller.awaitSetupAfterCleanupForTest())
        } finally {
            allowCleanup.countDown()
            observation.close()
            dispatcher.close()
        }
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
        private val completedCalls = MutableStateFlow(0)

        private fun awaitCall(step: String, predicate: () -> Boolean) {
            try {
                runBlocking { withTimeout(5_000) { completedCalls.first { predicate() } } }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("$step: engine call did not arrive", error)
            }
        }
        fun awaitPrepared(count: Int) = awaitCall("prepare #$count") { prepared.size >= count }
        fun awaitPlayed(count: Int) = awaitCall("play #$count") { playCount >= count }
        fun awaitPaused(count: Int) = awaitCall("pause #$count") { pauseCount >= count }
        fun awaitSeeks(count: Int) = awaitCall("seek #$count") { seeks.size >= count }


        override fun prepare(media: PlaybackMedia) {
            prepared += media
            state = PlaybackState(mediaId = media.trackId, status = PlaybackStatus.BUFFERING)
            listeners.toList().forEach { it(state) }
            completedCalls.value++
        }
        override fun play() { playCount++; completedCalls.value++ }
        override fun pause() { pauseCount++; completedCalls.value++ }
        var beforeSeek: (() -> Unit)? = null
        override fun seekTo(positionMs: Long) {
            beforeSeek?.invoke()
            seeks += positionMs
            completedCalls.value++
        }
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
        private val entered = LinkedBlockingQueue<Int>()

        fun awaitCall(expected: Int = 1) {
            assertEquals("queue POST #$expected did not arrive", expected, entered.poll(5, TimeUnit.SECONDS))
        }

        override fun skip(
            roomCode: String,
            hostToken: String,
            callback: (QueueAdvanceCommandResult) -> Unit,
        ) {
            assertEquals("ABCD", roomCode)
            assertEquals("host-secret", hostToken)
            callbacks += callback
            entered.add(callbacks.size)
        }

        fun complete(result: QueueAdvanceCommandResult) {
            callbacks.last()(result)
        }
    }

    private class RecordingReconciler : TestRoomFetcher {
        private var callback: ((RoomFetchResult) -> Unit)? = null
        val calls = mutableListOf<String>()
        private val entered = java.util.concurrent.LinkedBlockingQueue<String>()

        fun awaitCall() {
            assertEquals("ABCD", entered.poll(5, TimeUnit.SECONDS))
        }

        override fun fetch(roomCode: String, callback: (RoomFetchResult) -> Unit): Cancelable {
            assertEquals("ABCD", roomCode)
            calls += roomCode
            this.callback = callback
            entered.add(roomCode)
            return Cancelable { }
        }

        fun complete(result: RoomFetchResult) {
            callback?.invoke(result)
        }
    }

    private class BlockingCleanupRoomRepository(private val blockedGeneration: Int = 1) : RoomRepository {
        val invitationStarted = CountDownLatch(1)
        val collectionStarted = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val cleanupDone = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val cleanupCompleted = AtomicBoolean(false)
        val collections = AtomicInteger()

        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            assertEquals("ABCD", roomCode)
            val generation = collections.incrementAndGet()
            if (generation == 1) invitationStarted.countDown()
            if (generation != blockedGeneration) {
                kotlinx.coroutines.awaitCancellation()
            } else {
                collectionStarted.countDown()
                try {
                    Channel<RoomSyncState>(Channel.UNLIMITED).receive()
                } finally {
                    cleanupStarted.countDown()
                    check(allowCleanup.await(5, TimeUnit.SECONDS)) { "Collection cleanup gate timed out" }
                    cleanupCompleted.set(true)
                    cleanupDone.countDown()
                }
            }
        }
    }

    private class FlowRoomRepository : RoomRepository {
        private val states = MutableSharedFlow<RoomSyncState>(extraBufferCapacity = 8)
        private val started = MutableStateFlow(0)
        var collections = 0
        var cancellations = 0
        var activeCollectors = 0

        // MutableSharedFlow has no replay: wait for its actual subscriber, not flow entry.
        fun awaitSubscription() {
            runBlocking { withTimeout(5_000) { states.subscriptionCount.first { it == 1 } } }
        }

        fun awaitSecondSubscription() {
            runBlocking { withTimeout(5_000) {
                started.first { it >= 2 }
                states.subscriptionCount.first { it == 1 }
            } }
        }

        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            assertEquals("ABCD", roomCode)
            collections++
            started.value = collections
            activeCollectors++
            try {
                states.collect { emit(it) }
            } finally {
                activeCollectors--
                cancellations++
            }
        }

        fun publish(state: RoomSyncState) {
            assertTrue(states.tryEmit(state))
        }
    }

    private class RecordingRoomRepository : RoomRepository {
        var observedCode: String? = null
        val collectionStarted = CountDownLatch(1)
        val closed: Boolean get() = activeCollectors.get() == 0 && cancellations.get() == started.value
        private val collectors = mutableListOf<Channel<RoomSyncState>>()
        @Volatile private var lastState: RoomSyncState? = null
        private val started = MutableStateFlow(0)
        private var nextCollection = 2
        private val activeCollectors = AtomicInteger()
        private val cancellations = AtomicInteger()
        private val delivered = MutableStateFlow(0)

        fun awaitInvitationCollection() {
            try {
                runBlocking { withTimeout(5_000) { started.first { it >= 1 } } }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("invitation collection did not start", error)
            }
        }

        fun awaitDelivery(expected: Int) {
            try {
                runBlocking { withTimeout(5_000) { delivered.first { it >= expected } } }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("repository offer #$expected did not reach host inbox", error)
            }
        }

        fun awaitCollection() {
            val expected = nextCollection++
            try {
                runBlocking { withTimeout(5_000) { started.first { it >= expected } } }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("room collection #$expected did not start", error)
            }
        }

        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            observedCode = roomCode
            val states = Channel<RoomSyncState>(Channel.UNLIMITED)
            collectors += states
            activeCollectors.incrementAndGet()
            lastState?.let(states::trySend)
            started.value = collectors.size
            collectionStarted.countDown()
            try {
                for (state in states) { emit(state); delivered.value++ }
            } finally {
                states.close()
                activeCollectors.decrementAndGet()
                cancellations.incrementAndGet()
            }
        }

        fun rememberSnapshot(state: RoomSyncState) {
            lastState = state
        }

        fun publish(state: RoomSyncState) {
            rememberSnapshot(state)
            collectors.lastOrNull()?.trySend(state)
        }

    }
}
