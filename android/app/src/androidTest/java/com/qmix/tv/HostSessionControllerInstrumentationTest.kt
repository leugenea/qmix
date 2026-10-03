package com.qmix.tv

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostSessionControllerInstrumentationTest {
    private val roomScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val mutationLane = TestMutationLane()
    private lateinit var server: MockWebServer
    private val controllers = mutableListOf<HostSessionController>()
    private val subscriptions = mutableListOf<AutoCloseable>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        cleanupTracer(null,
            *subscriptions.map { subscription -> { subscription.close() } }.toTypedArray(),
            *controllers.map { controller -> {
                controller.abandonRoom()
                // Every registered subject has reached its tested processing boundary first.
                controller.awaitSetupForTest("tearDown joins native host session")
            } }.toTypedArray(),
            {
                try {
                    runBlocking { withTimeout(5_000) { roomScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() } }
                } catch (timeout: TimeoutCancellationException) {
                    throw AssertionError("join native host test root: timed out; states=${controllers.map { it.state }}", timeout)
                }
            },
            { mutationLane.close() },
            { server.shutdown() },
        )
    }

    private fun routeCreations(vararg tokens: String) {
        val creations = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "DELETE" && request.path == "/rooms/ABCD" ->
                    MockResponse().setResponseCode(204)
                request.method == "POST" && request.path == "/rooms" -> {
                    val token = tokens.getOrNull(creations.getAndIncrement())
                    if (token == null) MockResponse().setResponseCode(404)
                    else MockResponse().setResponseCode(201)
                        .setBody("""{"code":"ABCD","host_token":"$token","url":"/r/ABCD"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    /** qmix#312: observer-offered commands must queue, not re-enter the host transition. */
    @Test
    fun fresh_live_room_observer_offers_command_without_inline_state_mutation() {
        routeCreations("host-secret")
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Main.immediate)
        val invitationRepository = RecordingRoomRepository()
        val liveRepository = RecordingRoomRepository()
        val repositories = ArrayDeque(listOf(invitationRepository, liveRepository))
        // Main.immediate deliberately challenges non-nested host admission; production
        // now uses posted Main. The Main-thread predicate remains the production one.
        val mutation = QueueMutationContext(Dispatchers.Main.immediate) {
            Looper.myLooper() === Looper.getMainLooper()
        }
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            roomCollectionContext = Dispatchers.IO, queueMutationContext = mutation,
            roomRepositoryFactory = { repositories.removeFirst() },
        )
        val synchronization = RoomSyncState.Active(
            "ABCD", RoomState("ABCD", null, emptyList()), Freshness.FRESH, LiveConnection.CONNECTED,
        )
        val fresh = HostingState.LiveRoom(GuestInvite("ABCD", "https://guest.example/r/ABCD"), synchronization)
        val observerCalled = AtomicBoolean()
        val onMain = AtomicBoolean()
        val commandOffered = AtomicBoolean()
        val callbackFailure = AtomicReference<Throwable?>()
        val inspected = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        // Unconfined is only the synchronous public observer; host mutations use real Android Main.
        val observer = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            controller.states.collect { published ->
                if (published != fresh || !observerCalled.compareAndSet(false, true)) return@collect
                callbackFailure.set(runCatching {
                    onMain.set(Looper.myLooper() === Looper.getMainLooper())
                    assertTrue("fresh LiveRoom observer must run on Android Main", onMain.get())
                    val before = controller.state
                    assertEquals("observer must handle actual fresh LiveRoom(commandPending=false)", fresh, before)
                    commandOffered.set(true)
                    controller.setCommandPending(true)
                    val after = controller.state
                    assertEquals(
                        "qmix#312: observer-offered setCommandPending(true) mutated host state before observer returned; " +
                            "origin=StateFlow fresh LiveRoom observer on Android Main; before=$before; after=$after",
                        before, after,
                    )
                }.exceptionOrNull())
                inspected.countDown()
                runCatching {
                    awaitTracerLatch("release fresh LiveRoom observer", releaseObserver, controller, 10)
                }.onFailure { gateFailure ->
                    callbackFailure.updateAndGet { it?.apply { addSuppressed(gateFailure) } ?: gateFailure }
                }
                callbackFinished.countDown()
            }
        }
        var primaryFailure: Throwable? = null
        try {
            runTracerStep("offer createRoom on Android Main", controller) {
                withContext(Dispatchers.Main.immediate) { controller.createRoom(); Unit }
            }
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("room creation HTTP call", "POST", request?.method)
            assertEquals("room creation HTTP path", "/rooms", request?.path)
            runTracerStep("created invitation publication", controller) {
                controller.states.first { it is HostingState.Invitation }
            }
            runTracerStep("offer enterRoom on Android Main", controller) {
                withContext(Dispatchers.Main.immediate) { controller.enterRoom() }
            }
            awaitConditionForTracer("live repository collection armed", controller) {
                liveRepository.observations == 1
            }
            liveRepository.publish(synchronization)
            awaitTracerLatch("fresh LiveRoom observer inspected command offer", inspected, controller)
            // Idempotent release precedes every fatal assertion on the observer's results.
            releaseObserver.countDown()
            awaitTracerLatch("fresh LiveRoom observer finished", callbackFinished, controller)
            callbackFailure.get()?.let { throw it }
            assertTrue("public fresh LiveRoom observer was not called", observerCalled.get())
            assertTrue("command origin was not Android Main", onMain.get())
            assertTrue("setCommandPending(true) was not offered", commandOffered.get())
            runTracerStep("queued command eventually publishes commandPending=true after callback", controller) {
                controller.states.first { it == fresh.copy(commandPending = true) }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseObserver.countDown()
            cleanupTracer(primaryFailure,
                { runTracerStep("join public observer", controller) { observer.cancelAndJoin() } },
                { runTracerStep("abandon tracer session on Android Main", controller) {
                    withContext(Dispatchers.Main.immediate) { controller.abandonRoom() }
                } },
                { runTracerStep("session cleanup publishes Setup", controller) {
                    controller.states.first { it is HostingState.Setup }
                } },
                { runTracerStep("cancel and join tracer owner tree", controller) { owner.cancelAndJoin() } },
                {
                    assertTrue("tracer owner was not joined", owner.isCompleted)
                    assertTrue("tracer owner still has children", owner.children.none())
                    assertTrue("invitation collection cleanup missing",
                        invitationRepository.observations == 0 || invitationRepository.closed)
                    assertTrue("live collection cleanup missing",
                        liveRepository.observations == 0 || liveRepository.closed)
                },
            )
        }
    }

    private fun awaitTracerLatch(
        step: String, latch: CountDownLatch, controller: HostSessionController, seconds: Long = 5,
    ) {
        if (!latch.await(seconds, TimeUnit.SECONDS)) {
            throw AssertionError("$step: latch timed out; last state=${controller.state}")
        }
    }

    private fun <T> runTracerStep(
        step: String, controller: HostSessionController, action: suspend CoroutineScope.() -> T,
    ): T = try {
        runBlocking { withTimeout(5_000) { action() } }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("$step: timed out; last state=${controller.state}", timeout)
    }

    private fun awaitConditionForTracer(
        step: String, controller: HostSessionController, predicate: () -> Boolean,
    ) = runTracerStep(step, controller) {
        while (!predicate()) kotlinx.coroutines.delay(10)
    }

    private fun cleanupTracer(primary: Throwable?, vararg steps: () -> Unit) {
        var failure = primary
        for (step in steps) {
            try { step() } catch (cleanupFailure: Throwable) {
                if (failure == null) failure = cleanupFailure else failure.addSuppressed(cleanupFailure)
            }
        }
        if (primary == null) failure?.let { throw it }
    }

    @Test
    fun observer_settings_pending_invitation_and_live_room_form_one_session() {
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST" && request.path == "/rooms") {
                    if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
                        throw AssertionError("release initial creation response: timed out")
                    }
                    return MockResponse().setResponseCode(201)
                        .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }
        val controller = HostSessionController(OkHttpClient(), roomCollectionScope = roomScope,
        ).also { controllers.add(it) }
        val observed = java.util.concurrent.CopyOnWriteArrayList<HostingState>()
        val subscription = controller.collectStatesForTest(observer = observed::add)
        var primaryFailure: Throwable? = null
        try {
            assertEquals(HostingState.Setup("", ""), observed.single())
            controller.updateSettings(server.url("/").toString(), "https://guest.example")
            controller.createRoom()
            controller.awaitStateForTest("initial create publishes Pending") { it is HostingState.Pending }
            assertEquals(
                HostingState.Pending(server.url("/").toString().trimEnd('/'), "https://guest.example"),
                controller.state,
            )

            controller.updateSettings("https://ignored.example", "https://ignored.example")
            controller.createRoom()
            controller.enterRoom()
            releaseResponse.countDown()
            controller.awaitCreatedForTest("observer_settings_pending_invitation_and_live_room_form_one_session: creation result 1")

            assertEquals(
                HostingState.Invitation(GuestInvite("ABCD", "https://guest.example/r/ABCD")),
                controller.state,
            )
            assertFalse(controller.state.toString().contains("host-secret"))
            controller.enterRoom()
            controller.awaitStateForTest("enter publishes loading LiveRoom") { it is HostingState.LiveRoom }
            assertEquals(
                HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    RoomSyncState.Active("ABCD", null, Freshness.LOADING, LiveConnection.CONNECTING),
                ),
                controller.state,
            )
            controller.enterRoom()
            assertEquals(1, server.requestCount)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseResponse.countDown()
            cleanupTracer(primaryFailure, { subscription.close() })
        }

        val observedBeforeUpdate = observed.size
        controller.updateSettings("https://unused.example", "https://unused.example")
        assertEquals(observedBeforeUpdate, observed.size)
    }

    @Test
    fun invalid_settings_and_server_failure_can_be_corrected_and_retried() {
        val controller = HostSessionController(OkHttpClient(), roomCollectionScope = roomScope,
        ).also { controllers.add(it) }
        controller.updateSettings("not a url", "also not a url")

        controller.createRoom()
        controller.awaitStateForTest("invalid endpoint rejected") { it is HostingState.Error }
        assertEquals(
            HostingState.Error(
                UserMessage.INVALID_ENDPOINT,
                "",
                "",
            ),
            controller.state,
        )

        server.enqueue(MockResponse().setResponseCode(503))
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        controller.createRoom()
        controller.awaitStateForTest("corrected endpoint create receives server failure") {
            (it as? HostingState.Error)?.message == UserMessage.SERVER_UNAVAILABLE
        }
        assertEquals(
            HostingState.Error(
                UserMessage.SERVER_UNAVAILABLE,
                server.url("/").toString().trimEnd('/'),
                "https://guest.example",
            ),
            controller.state,
        )

        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"WXYZ","host_token":"replacement-secret","url":"/r/WXYZ"}"""),
        )
        controller.createRoom()
        controller.awaitStateForTest("retry publishes replacement invitation") { it is HostingState.Invitation }
        assertEquals(
            HostingState.Invitation(GuestInvite("WXYZ", "https://guest.example/r/WXYZ")),
            controller.state,
        )
    }

    @Test
    fun http_warning_cancellation_restores_setup_without_acknowledgement_or_request() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(
            EndpointSettingsStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        assertTrue(preferences.edit().clear().commit())
        val backend = server.url("/").toString().trimEnd('/')
        val guestOrigin = "https://guest.example"
        try {
            val controller = HostSessionController(
                OkHttpClient(),

                settingsPersistence = EndpointSettingsStore(context),
                roomCollectionScope = roomScope,
        ).also { controllers.add(it) }
            controller.updateSettings(backend, guestOrigin)

            controller.createRoom()
            controller.awaitStateForTest("HTTP warning before any request") { it is HostingState.HttpWarning }
            assertEquals(HostingState.HttpWarning(backend, guestOrigin), controller.state)
            assertEquals(0, server.requestCount)

            controller.cancelHttpWarning()
            controller.awaitStateForTest("cancel warning restores configured Setup") { it == HostingState.Setup(backend, guestOrigin) }

            assertEquals(HostingState.Setup(backend, guestOrigin), controller.state)
            assertFalse(EndpointSettingsStore(context).isHttpWarningAcknowledged())
            assertEquals(0, server.requestCount)
            assertTrue(preferences.all.isEmpty())
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    @Test
    fun unsafe_server_invitation_is_reported_as_a_url_error() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"//evil.example/r/ABCD"}"""),
        )
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",

            roomCollectionScope = roomScope,
        ).also { controllers.add(it) }

        controller.createRoom()
        controller.awaitCreatedForTest("unsafe_server_invitation_is_reported_as_a_url_error: creation result 1")
        assertEquals(
            HostingState.Error(
                UserMessage.INVALID_ENDPOINT,
                server.url("/").toString().trimEnd('/'),
                "https://guest.example",
            ),
            controller.state,
        )
    }

    @Test
    fun room_synchronization_is_owned_and_terminated_by_the_host_session() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = RecordingRoomRepository()
        val backend = server.url("/").toString()
        val canonicalBackend = backend.trimEnd('/')
        val factoryFailure = AtomicReference<Throwable?>()
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = backend,
            initialGuestOrigin = "https://guest.example",

            roomRepositoryFactory = {
                factoryFailure.set(runCatching { assertEquals(canonicalBackend, it) }.exceptionOrNull())
                repository
            },
            roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
        ).also { controllers.add(it) }
        val observed = java.util.concurrent.CopyOnWriteArrayList<HostingState>()
        val observation = controller.collectStatesForTest(observer = observed::add)
        var primaryFailure: Throwable? = null

        try {
            controller.createRoom()
            controller.awaitCreatedForTest("room_synchronization_is_owned_and_terminated_by_the_host_session: creation result 1")
            repository.awaitObservationForTest()
            controller.enterRoom()
            repository.awaitObservationCountForTest(2)
            val synchronized = RoomSyncState.Active(
                "ABCD",
                RoomState("ABCD", null, emptyList()),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            )
            repository.publish(synchronized)

            controller.awaitStateForTest("room_synchronization_is_owned_and_terminated_by_the_host_session: state boundary 2") { (it as? HostingState.LiveRoom)?.synchronization == synchronized }
            assertEquals("ABCD", repository.roomCode)
            assertEquals(synchronized, controller.roomSyncState)
            assertEquals(synchronized, (observed.last() as HostingState.LiveRoom).synchronization)
            controller.endRoom()
            controller.awaitSetupForTest("room_synchronization_is_owned_and_terminated_by_the_host_session: joined cleanup 3")

            assertTrue(repository.closed)
            assertNull(controller.roomSyncState)
            assertEquals(HostingState.Setup(canonicalBackend, "https://guest.example"), controller.state)
            repository.publish(synchronized.copy(freshness = Freshness.STALE))
            assertNull(controller.roomSyncState)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanupTracer(primaryFailure, { observation.close() })
        }
        factoryFailure.get()?.let { throw it }
    }

    @Test
    fun live_room_presentation_handlers_publish_and_close_the_application_session() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = RecordingRoomRepository()
        var primaryActions = 0
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",

            roomRepositoryFactory = { repository },
            roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
            primaryActionHandler = { primaryActions++ },

        ).also { controllers.add(it) }
        val observed = java.util.concurrent.CopyOnWriteArrayList<HostingState>()
        val receipts = Channel<HostingState>(Channel.UNLIMITED)
        subscriptions += controller.collectStatesForTest { state ->
            observed.add(state)
            // A separate StateFlow wait cannot acknowledge delivery to this observer.
            receipts.trySend(state)
        }
        subscriptions += controller.collectStatesForTest(isolateFailures = true) { state ->
            if (state is HostingState.LiveRoom && state.commandPending) {
                error("observer failure")
            }
        }

        controller.createRoom()
        controller.awaitCreatedForTest("live_room_presentation_handlers_publish_and_close_the_application_session: creation result 1")
        repository.awaitObservationForTest()
        controller.enterRoom()
        repository.awaitObservationCountForTest(2)
        val queue = listOf(QueuedTrack("track-1", "https://example/1", "Title", "Artist", 0, "fixture"))
        val room = RoomState("ABCD", null, queue)
        val synchronized = RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED)
        repository.publish(synchronized)
        controller.awaitStateForTest("live_room_presentation_handlers_publish_and_close_the_application_session: state boundary 2") { (it as? HostingState.LiveRoom)?.synchronization == synchronized }
        val ready = controller.state as HostingState.LiveRoom
        assertEquals(LiveRoomPrimaryAction.START, ready.primaryAction)
        assertTrue(ready.isPrimaryActionEnabled)
        assertFalse(ready.toString().contains("host-secret"))

        controller.onStartOrNext()
        controller.setCommandPending(true)
        controller.onStartOrNext()
        controller.awaitStateForTest("pending primary action publication") { (it as? HostingState.LiveRoom)?.commandPending == true }
        assertEquals(1, primaryActions)
        assertFalse((controller.state as HostingState.LiveRoom).isPrimaryActionEnabled)
        awaitPresentationReceipt("pending primary action observer receipt", receipts, ready.copy(commandPending = true))
        assertTrue((observed.last() as HostingState.LiveRoom).commandPending)

        controller.setCommandPending(false)
        controller.onInvite()
        controller.awaitStateForTest("invite becomes visible") { (it as? HostingState.LiveRoom)?.invitationVisible == true }
        assertTrue((controller.state as HostingState.LiveRoom).invitationVisible)
        awaitPresentationReceipt("visible invite observer receipt", receipts, ready.copy(invitationVisible = true))
        assertTrue((observed.last() as HostingState.LiveRoom).invitationVisible)
        controller.onInvite()
        val exits = AtomicInteger()
        controller.onBack { exits.incrementAndGet() }
        controller.awaitStateForTest("first Back dismisses invite without exit") { (it as? HostingState.LiveRoom)?.invitationVisible == false }
        assertEquals("dismiss invite must not exit Activity", 0, exits.get())
        assertFalse((controller.state as HostingState.LiveRoom).invitationVisible)
        awaitPresentationReceipt("first Back dismissed invite observer receipt", receipts, ready)
        assertFalse((observed.last() as HostingState.LiveRoom).invitationVisible)
        controller.onBack { exits.incrementAndGet() }
        awaitConditionForTest("Back exit effect after cleanup") { exits.get() == 1 }
        controller.awaitSetupForTest("live_room_presentation_handlers_publish_and_close_the_application_session: joined cleanup 3")
        assertTrue(repository.closed)
        assertTrue(controller.state is HostingState.Setup)
        awaitPresentationReceipt("joined cleanup Setup observer receipt", receipts,
            HostingState.Setup(server.url("/").toString().trimEnd('/'), "https://guest.example"))
        assertTrue(observed.last() is HostingState.Setup)
        controller.onBack { exits.incrementAndGet() }
        controller.updateSettings("https://after-back.example", "https://guest.example")
        controller.awaitStateForTest("Setup settings remain usable after ignored Back") {
            it == HostingState.Setup("https://after-back.example", "https://guest.example")
        }
        assertEquals("Back outside room must not exit again", 1, exits.get())
        assertEquals(listOf(LiveRoomPrimaryAction.START, LiveRoomPrimaryAction.NEXT), LiveRoomPrimaryAction.entries)
    }

    private fun awaitPresentationReceipt(
        step: String, receipts: Channel<HostingState>, expected: HostingState,
    ) {
        var lastReceipt: HostingState? = null
        try {
            runBlocking {
                withTimeout(5_000) {
                    do {
                        lastReceipt = receipts.receive()
                    } while (lastReceipt != expected)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("$step: timed out; expected=$expected; last observer receipt=$lastReceipt", timeout)
        }
    }

    @Test
    fun stale_room_updates_retain_data_and_session_generation_rejects_late_updates() {
        routeCreations("host-secret", "replacement-secret")
        val firstInvitationRepository = RecordingRoomRepository()
        val firstRepository = RecordingRoomRepository()
        val secondInvitationRepository = RecordingRoomRepository()
        val secondRepository = RecordingRoomRepository()
        val repositories = ArrayDeque(listOf(
            firstInvitationRepository, firstRepository,
            secondInvitationRepository, secondRepository,
        ))
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",

            roomRepositoryFactory = { repositories.removeFirst() },
            roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
        ).also { controllers.add(it) }
        controller.createRoom()
        controller.awaitCreatedForTest("stale_room_updates_retain_data_and_session_generation_rejects_late_updates: creation result 1")
        firstInvitationRepository.awaitObservationForTest()
        controller.enterRoom()
        firstRepository.awaitObservationForTest()
        val room = RoomState("ABCD", null, emptyList())
        firstRepository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitStateForTest("stale_room_updates_retain_data_and_session_generation_rejects_late_updates: state boundary 2") { (it as? HostingState.LiveRoom)?.synchronization ==
            RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED) }
        firstRepository.publish(RoomSyncState.Active("ABCD", null, Freshness.STALE, LiveConnection.RECONNECTING))

        controller.awaitStateForTest("stale_room_updates_retain_data_and_session_generation_rejects_late_updates: state boundary 3") { (it as? HostingState.LiveRoom)?.synchronization ==
            RoomSyncState.Active("ABCD", room, Freshness.STALE, LiveConnection.RECONNECTING) }
        val stale = controller.roomSyncState as RoomSyncState.Active
        assertEquals(room, stale.room)
        assertEquals(Freshness.STALE, stale.freshness)
        assertEquals(LiveConnection.RECONNECTING, stale.connection)

        val beforeOtherRoom = controller.state
        val lateObservations = java.util.concurrent.CopyOnWriteArrayList<HostingState>()
        val lateSubscription = controller.collectStatesForTest { lateObservations.add(it) }
        subscriptions.add(lateSubscription)
        firstRepository.publish(
            RoomSyncState.Active(
                "WXYZ",
                RoomState("WXYZ", null, emptyList()),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            ),
        )
        val validated = (beforeOtherRoom as HostingState.LiveRoom).synchronization as RoomSyncState.Active
        val nextValid = validated.copy(connection = LiveConnection.CONNECTING)
        firstRepository.publish(nextValid)
        controller.awaitStateForTest("valid room publication after foreign-room rejection") {
            (it as? HostingState.LiveRoom)?.synchronization == nextValid
        }
        assertFalse("foreign-room update must never publish", lateObservations.any {
            (it as? HostingState.LiveRoom)?.synchronization?.roomCode == "WXYZ"
        })
        controller.endRoom()
        controller.awaitSetupForTest("stale_room_updates_retain_data_and_session_generation_rejects_late_updates: joined cleanup 4")
        controller.createRoom()
        controller.awaitCreatedForTest("stale_room_updates_retain_data_and_session_generation_rejects_late_updates: creation result 5")
        val requests = (1..3).map { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "replacement session authenticated HTTP request: timed out; last state=${controller.state}"
        } }
        assertEquals(2, requests.count { it.method == "POST" && it.path == "/rooms" })
        assertEquals(1, requests.count { it.method == "DELETE" && it.path == "/rooms/ABCD" })
        secondInvitationRepository.awaitObservationForTest()
        controller.enterRoom()
        secondRepository.awaitObservationForTest()
        val replacementInitialState = controller.state
        firstRepository.awaitClosedCountForTest(1)

        firstRepository.publish(RoomSyncState.Missing("ABCD"))

        assertEquals(replacementInitialState, controller.state)
        assertTrue(firstInvitationRepository.closed)
        assertTrue(firstRepository.closed)
        assertTrue(secondInvitationRepository.closed)
        assertFalse(secondRepository.closed)
    }

    @Test
    fun missing_foreground_reconciliation_does_not_reopen_commands_or_restart_collection() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        val fetches = AtomicInteger()
        var commands = 0
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = { repository }, roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
            foregroundReconcilerFactory = { { _: String -> fetches.incrementAndGet(); RoomFetchResult.Missing } },
            queueMutationContext = mutationLane.context,
            primaryActionHandler = { commands++ },
        ).also { controllers.add(it) }
        controller.createRoom()
        controller.awaitCreatedForTest("missing_foreground_reconciliation_does_not_reopen_commands_or_restart_collection: creation result 1")
        repository.awaitObservationForTest()
        controller.enterRoom()
        repository.awaitObservationCountForTest(2)
        val room = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "Artist", 60, "fixture")))
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitStateForTest("missing_foreground_reconciliation_does_not_reopen_commands_or_restart_collection: state boundary 2") { (it as? HostingState.LiveRoom)?.isPrimaryActionEnabled == true }
        assertTrue((controller.state as HostingState.LiveRoom).isPrimaryActionEnabled)

        controller.onHostStopped()
        awaitConditionForTest("missing_foreground_reconciliation_does_not_reopen_commands_or_restart_collection: observable boundary 3") { repository.closed }
        controller.onHostStarted()
        controller.awaitStateForTest("missing_foreground_reconciliation_does_not_reopen_commands_or_restart_collection: state boundary 4") { (it as? HostingState.LiveRoom)?.synchronization is RoomSyncState.Missing }
        assertEquals(1, fetches.get())
        assertEquals(RoomSyncState.Missing("ABCD"), controller.roomSyncState)
        assertFalse((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        controller.onStartOrNext()
        controller.endRoom()
        controller.awaitSetupForTest("missing_foreground_reconciliation_does_not_reopen_commands_or_restart_collection: joined cleanup 5")
        assertEquals(0, commands)
        assertTrue(controller.state is HostingState.Setup)
    }

    @Test
    fun thrown_foreground_fetch_keeps_commands_closed_until_a_later_fresh_retry_succeeds() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        val room = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "Artist", 60, "fixture")))
        val fetches = AtomicInteger()
        var commands = 0
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = { repository }, roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
            foregroundReconcilerFactory = { { _: String ->
                val attempt = fetches.incrementAndGet()
                if (attempt == 1) throw IllegalStateException("temporary fetch failure")
                if (attempt == 2) RoomFetchResult.Success(room.copy(code = "OTHER"))
                else RoomFetchResult.Success(room)
            } },
            queueMutationContext = mutationLane.context,
            primaryActionHandler = { commands++ },
        ).also { controllers.add(it) }
        controller.createRoom()
        controller.awaitCreatedForTest("thrown_foreground_fetch_keeps_commands_closed_until_a_later_fresh_retry_succeeds: creation result 1")
        repository.awaitObservationForTest()
        controller.enterRoom()
        repository.awaitObservationCountForTest(2)
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitStateForTest("thrown_foreground_fetch_keeps_commands_closed_until_a_later_fresh_retry_succeeds: state boundary 2") { (it as? HostingState.LiveRoom)?.synchronization ==
            RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED) }
        controller.onHostStopped()
        controller.onHostStarted()
        awaitConditionForTest("failed recovery restarts third collection") { fetches.get() == 1 && repository.observations >= 3 }
        assertEquals(1, fetches.get())
        assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        controller.onStartOrNext()
        assertEquals(0, commands)

        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        awaitConditionForTest("wrong-room recovery restarts fourth collection") { fetches.get() == 2 && repository.observations >= 4 }
        assertEquals(2, fetches.get())
        assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        controller.onStartOrNext()
        assertEquals(0, commands)

        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitStateForTest("thrown_foreground_fetch_keeps_commands_closed_until_a_later_fresh_retry_succeeds: state boundary 3") { (it as? HostingState.LiveRoom)?.foregroundRecoveryPending == false }
        assertEquals(3, fetches.get())
        assertFalse((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        controller.onStartOrNext()
        controller.onInvite()
        controller.awaitStateForTest("fresh retry admits command and keeps invitation available") {
            (it as? HostingState.LiveRoom)?.invitationVisible == true
        }
        assertEquals(1, commands)
        controller.endRoom()
        controller.awaitSetupForTest("thrown_foreground_fetch_keeps_commands_closed_until_a_later_fresh_retry_succeeds: joined cleanup 4")
        assertTrue(repository.closed)
    }

    @Test
    fun recovery_observer_ending_session_cannot_restart_collection_or_reopen_commands() {
        // Legacy name retained: recovery installs its collection before observer-offered End is handled.
        routeCreations("host-secret", "replacement-secret")
        val repository = RecordingRoomRepository()
        val room = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "Artist", 60, "fixture")))
        val commands = AtomicInteger()
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = { repository }, roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
            foregroundReconcilerFactory = { { _: String -> RoomFetchResult.Success(room) } },
            queueMutationContext = mutationLane.context,
            primaryActionHandler = { commands.incrementAndGet(); Unit },
        ).also { controllers.add(it) }
        controller.createRoom()
        controller.awaitCreatedForTest("recovery observer initial invitation")
        repository.awaitObservationForTest()
        controller.enterRoom()
        repository.awaitObservationCountForTest(2)
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitStateForTest("initial fresh room before stop") {
            (it as? HostingState.LiveRoom)?.isPrimaryActionEnabled == true
        }
        controller.onHostStopped()
        controller.awaitStateForTest("stop closes recovery eligibility before observer installation") {
            (it as? HostingState.LiveRoom)?.foregroundRecoveryPending == true
        }
        repository.awaitClosedCountForTest(2)
        val offered = CountDownLatch(1)
        val subscription = controller.collectStatesForTest { state ->
            if (state is HostingState.LiveRoom && !state.foregroundRecoveryPending &&
                state.synchronization is RoomSyncState.Active &&
                state.synchronization.freshness == Freshness.FRESH
            ) {
                try {
                    controller.endRoom()
                    controller.onStartOrNext()
                    assertEquals("recovery observer End must remain queued", state, controller.state)
                } finally { offered.countDown() }
            }
        }
        var primaryFailure: Throwable? = null
        try {
            controller.onHostStarted()
            awaitTracerLatch("fresh recovery observer offers End then command", offered, controller)
            controller.awaitSetupForTest("observer End fully joins resumed collection")
            repository.awaitClosedCountForTest(3)
            assertEquals("running recovery completes collection install before queued End", 3, repository.observations)
            assertTrue(repository.closed)
            assertTrue(controller.state is HostingState.Setup)
            // A real next invitation proves the earlier post-End control has passed the FIFO loop.
            controller.createRoom()
            controller.awaitCreatedForTest("next session admitted after recovery End cleanup")
            repository.awaitObservationCountForTest(4)
            assertEquals("command queued after End must remain ineligible", 0, commands.get())
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanupTracer(primaryFailure, { subscription.close() })
        }
    }

    @Test
    fun stop_cancels_recovery_queued_on_mutation_dispatcher_before_fetch_or_command_admission() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val recoveryDispatcher = QueuedCoroutineDispatcher()
        val repository = RecordingRoomRepository()
        val room = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "Artist", 60, "fixture")))
        val fetches = AtomicInteger()
        val commands = AtomicInteger()
        // Synchronous lifecycle calls and worker callbacks must share a real mutation lane.
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { mutationDispatcher ->
            val controller = HostSessionController(
                OkHttpClient(), initialBackendUrl = server.url("/").toString(),
                initialGuestOrigin = "https://guest.example",
                roomRepositoryFactory = { repository }, roomCollectionScope = roomScope,
                roomCollectionContext = Dispatchers.IO,
                foregroundRecoveryContext = recoveryDispatcher,
                foregroundReconcilerFactory = { { _: String ->
                    fetches.incrementAndGet()
                    RoomFetchResult.Success(room)
                } },
                queueMutationContext = QueueMutationContext(mutationDispatcher),
                primaryActionHandler = { commands.incrementAndGet(); Unit },
            ).also { controllers.add(it) }
            try {
                controller.createRoom()
                controller.awaitCreatedForTest("stop_cancels_recovery_queued_on_mutation_dispatcher_before_fetch_or_command_admission: creation result 1")
                repository.awaitObservationForTest()
                controller.enterRoom()
                repository.awaitObservationCountForTest(2)
                repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
                controller.awaitStateForTest("stop_cancels_recovery_queued_on_mutation_dispatcher_before_fetch_or_command_admission: state boundary 2") { state ->
                    (state as? HostingState.LiveRoom)?.synchronization ==
                        RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED)
                }
                controller.onHostStopped()
                controller.onHostStarted()
                // The first collection may still be joining; wait for an actual queued recovery.
                val cancelledRecovery = recoveryDispatcher.awaitPending(controller)
                controller.onHostStopped()
                awaitConditionForTest("Stop cancelled actual queued recovery job") { cancelledRecovery.job.isCancelled }
                cancelledRecovery.run()
                awaitConditionForTest("cancelled recovery job completed before restart") { cancelledRecovery.job.isCompleted }
                // Later resumptions must not remain parked after the stopped worker is proved cancelled.
                recoveryDispatcher.release()
                controller.onStartOrNext()
                assertEquals("cancelled recovery fetched", 0, fetches.get())
                assertEquals("command admitted while stopped", 0, commands.get())
                assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)

                controller.onHostStarted()
                controller.awaitStateForTest("stop_cancels_recovery_queued_on_mutation_dispatcher_before_fetch_or_command_admission: state boundary 3") { state ->
                    state is HostingState.LiveRoom && !state.foregroundRecoveryPending
                }
                assertEquals("foreground recovery fetches", 1, fetches.get())
                assertFalse((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
                controller.onStartOrNext()
                controller.onInvite()
                controller.awaitStateForTest("recovery admits command and keeps invitation available") {
                    (it as? HostingState.LiveRoom)?.invitationVisible == true
                }
                assertEquals("command admitted after recovery", 1, commands.get())
            } finally {
                controller.endRoom()
                recoveryDispatcher.release()
                controller.awaitSetupForTest("stop_cancels_recovery_queued_on_mutation_dispatcher_before_fetch_or_command_admission: joined cleanup 4")
            }
        }
    }

    @Test
    fun restarting_after_a_cancelled_recovery_waits_for_its_cleanup() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val started = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val release = CountDownLatch(1)
        val restarted = CountDownLatch(1)
        val cleanupFailure = AtomicReference<Throwable?>()
        val fetches = AtomicInteger()
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = roomScope,
            foregroundReconcilerFactory = { { _: String ->
                if (fetches.incrementAndGet() == 1) {
                    try {
                        started.countDown()
                        awaitCancellation()
                    }
                    finally { withContext(NonCancellable + Dispatchers.IO) {
                        cleanup.countDown()
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            cleanupFailure.set(AssertionError("release cancelled recovery cleanup: timed out"))
                        }
                    } }
                } else {
                    restarted.countDown()
                    RoomFetchResult.Failure
                }
            } },
        ).also { controllers.add(it) }
        try {
            controller.createRoom()
            controller.awaitCreatedForTest("restarting_after_a_cancelled_recovery_waits_for_its_cleanup: creation result 1")
            controller.enterRoom()
            controller.onHostStopped()
            controller.onHostStarted()
            awaitTracerLatch("first recovery armed cancellation cleanup", started, controller)
            controller.onHostStopped()
            awaitTracerLatch("cancelled recovery entered cleanup", cleanup, controller)
            controller.onHostStarted()
            assertEquals("recovery overlapped cancelled cleanup", 1, fetches.get())
            release.countDown()
            awaitTracerLatch("recovery restarted after predecessor cleanup", restarted, controller)
            cleanupFailure.get()?.let { throw it }
        } finally {
            release.countDown()
            controller.endRoom()
            controller.awaitSetupForTest("restarting_after_a_cancelled_recovery_waits_for_its_cleanup: joined cleanup 2")
        }
    }

    @Test
    fun failed_settings_save_never_exposes_an_invitation_or_host_token() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            settingsPersistence = object : EndpointSettingsPersistence {
                override fun load() = EndpointSettings(server.url("/").toString(), "https://guest.example")
                override fun save(settings: EndpointSettings): Unit = throw IllegalStateException("host-secret")
                override fun isHttpWarningAcknowledged() = true
                override fun acknowledgeHttpWarning() = Unit
            },
            roomCollectionScope = roomScope,
        ).also { controllers.add(it) }
        controller.createRoom()
        controller.awaitStateForTest("failed_settings_save_never_exposes_an_invitation_or_host_token: state boundary 1") { it is HostingState.Error }
        assertEquals(UserMessage.PERSISTENCE_ERROR, (controller.state as HostingState.Error).message)
        assertFalse(controller.state.toString().contains("host-secret"))
        assertEquals(1, server.requestCount)
    }

    /** qmix#182: a completed POST is not permission to publish before durable settings. */
    @Test
    fun create_waits_for_settings_save_before_publishing_invitation() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val saving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saved = CountDownLatch(1)
        val saveFailure = AtomicReference<Throwable?>()
        val backend = server.url("/").toString().trimEnd('/')
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = backend, initialGuestOrigin = "https://guest.example",
            settingsPersistence = object : EndpointSettingsPersistence {
                override fun load() = EndpointSettings(backend, "https://guest.example")
                override fun save(settings: EndpointSettings) {
                    saving.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "release settings persistence gate: timed out" }
                    saveFailure.set(runCatching {
                        assertEquals(EndpointSettings(backend, "https://guest.example"), settings)
                    }.exceptionOrNull())
                    saved.countDown()
                }
                override fun isHttpWarningAcknowledged() = true
                override fun acknowledgeHttpWarning() = Unit
            },
            roomCollectionScope = roomScope,
        ).also { controllers.add(it) }
        try {
            controller.createRoom()
            awaitTracerLatch("create reached durable settings persistence gate", saving, controller)
            assertEquals(1, server.requestCount)
            assertEquals(HostingState.Pending(backend, "https://guest.example"), controller.state)
        } finally {
            release.countDown()
        }
        controller.awaitCreatedForTest("create_waits_for_settings_save_before_publishing_invitation: creation result 1")
        assertEquals(0L, saved.count)
        saveFailure.get()?.let { throw it }
        assertEquals(HostingState.Invitation(GuestInvite("ABCD", "https://guest.example/r/ABCD")), controller.state)
        controller.endRoom()
        controller.awaitSetupForTest("create_waits_for_settings_save_before_publishing_invitation: joined cleanup 2")
    }

    /** qmix#182: a canceled create must not publish credentials after disk unblocks. */
    @Test
    fun ending_create_during_settings_save_never_publishes_invitation() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val saving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = server.url("/").toString().trimEnd('/')
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = backend, initialGuestOrigin = "https://guest.example",
            settingsPersistence = object : EndpointSettingsPersistence {
                override fun load() = EndpointSettings(backend, "https://guest.example")
                override fun save(settings: EndpointSettings) {
                    saving.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "release settings persistence gate: timed out" }
                }
                override fun isHttpWarningAcknowledged() = true
                override fun acknowledgeHttpWarning() = Unit
            },
            roomCollectionScope = roomScope,
        ).also { controllers.add(it) }
        val observed = java.util.concurrent.CopyOnWriteArrayList<HostingState>()
        val subscription = controller.collectStatesForTest(observer = observed::add)
        subscriptions.add(subscription)
        try {
            controller.createRoom()
            awaitTracerLatch("create reached durable settings persistence gate", saving, controller)
            controller.endRoom()
            controller.awaitStateForTest("End waits for blocked settings worker") { it == HostingState.Ending }
            assertEquals(HostingState.Ending, controller.state)
        } finally {
            release.countDown()
        }
        controller.awaitSetupForTest("ending_create_during_settings_save_never_publishes_invitation: joined cleanup 1")
        assertEquals(1, server.requestCount)
        assertEquals(HostingState.Setup(backend, "https://guest.example"), controller.state)
        assertFalse(observed.any { it is HostingState.Invitation || it is HostingState.LiveRoom })
        assertFalse(observed.toString().contains("host-secret"))
        subscription.close()
    }

    /** qmix#312: Pending observer End is FIFO, and cancellation still blocks invitation publication. */
    @Test
    fun pending_observer_ending_create_prevents_network_request_and_invitation() {
        // Legacy name retained. FIFO permits the current Create handler's POST, then End cancels it.
        val startedCall = AtomicReference<okhttp3.Call?>()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
            override fun callStart(call: okhttp3.Call) {
                calls.incrementAndGet()
                startedCall.set(call)
            }
        }).build()
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val controller = HostSessionController(
            client, initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
        ).also { controllers.add(it) }
        val observed = java.util.concurrent.CopyOnWriteArrayList<HostingState>()
        val endOffered = CountDownLatch(1)
        val subscription = controller.collectStatesForTest { state ->
            observed.add(state)
            if (state is HostingState.Pending) {
                try {
                    controller.endRoom()
                    assertEquals("observer End must not mutate the running Create handler", state, controller.state)
                } finally { endOffered.countDown() }
            }
        }
        var primaryFailure: Throwable? = null
        try {
            controller.createRoom()
            awaitTracerLatch("Pending observer offered End", endOffered, controller)
            controller.awaitSetupForTest("FIFO End cancelled and joined in-flight create")
            assertEquals("current Create handler must reach its HTTP call before queued End", 1, calls.get())
            assertTrue("End must cancel actual HTTP call", checkNotNull(startedCall.get()).isCanceled())
            assertEquals(HostingState.Setup(server.url("/").toString().trimEnd('/'), "https://guest.example"), controller.state)
            assertTrue(observed.any { it is HostingState.Pending })
            assertTrue(observed.any { it == HostingState.Ending })
            assertFalse(observed.any { it is HostingState.Invitation || it is HostingState.LiveRoom })
            assertFalse(observed.toString().contains("host-secret"))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanupTracer(primaryFailure, { subscription.close() })
        }
    }

    @Test
    fun foreground_loss_during_repository_creation_cancels_unstarted_collection_and_recovers() {
        // Legacy name retained: the factory-offered Stop follows the running collection install.
        routeCreations("host-secret")
        val repository = RecordingRoomRepository()
        val room = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "Artist", 60, "fixture")))
        lateinit var controller: HostSessionController
        val creations = AtomicInteger()
        val fetches = AtomicInteger()
        controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = {
                if (creations.incrementAndGet() == 1) controller.onHostStopped()
                repository
            },
            roomCollectionScope = roomScope, roomCollectionContext = Dispatchers.Unconfined,
            foregroundReconcilerFactory = { { _: String -> fetches.incrementAndGet(); RoomFetchResult.Success(room) } },
            queueMutationContext = mutationLane.context,
        ).also { controllers.add(it) }
        controller.createRoom()
        controller.awaitStateForTest("factory Stop marks invitation recovery pending") {
            (it as? HostingState.Invitation)?.foregroundRecoveryPending == true
        }
        repository.awaitClosedCountForTest(1)
        assertEquals("FIFO installed collection before Stop cleanup", 1, repository.observations)
        controller.enterRoom()
        controller.awaitStateForTest("background Enter keeps recovery gate closed") {
            (it as? HostingState.LiveRoom)?.foregroundRecoveryPending == true
        }
        assertEquals(1, creations.get())
        assertEquals("ABCD", repository.roomCode)
        assertTrue(repository.closed)
        assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)

        controller.onHostStarted()
        controller.awaitStateForTest("foreground recovery publishes fresh room") {
            (it as? HostingState.LiveRoom)?.foregroundRecoveryPending == false
        }
        repository.awaitObservationCountForTest(2)
        assertEquals(1, fetches.get())
        assertEquals(2, creations.get())
        assertEquals("ABCD", repository.roomCode)
        assertFalse((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitStateForTest("fresh connected room reopens primary action") {
            (it as? HostingState.LiveRoom)?.isPrimaryActionEnabled == true
        }
        assertTrue((controller.state as HostingState.LiveRoom).isPrimaryActionEnabled)
        controller.endRoom()
        controller.awaitSetupForTest("end joins resumed collection")
        repository.awaitClosedCountForTest(2)
        assertTrue(repository.closed)
    }

    @Test
    fun ending_session_inside_coordinator_factories_discards_handles_before_collection_starts() {
        // Legacy name retained: factory End offers do not preempt Enter; all installed handles must close.
        routeCreations("host-secret", "host-secret")
        val repository = RecordingRoomRepository()
        val queueConstructions = AtomicInteger()
        val playbackConstructions = AtomicInteger()
        val playbackPauses = AtomicInteger()
        val listeners = AtomicInteger()
        val removedListeners = AtomicInteger()
        lateinit var controller: HostSessionController
        val engine = object : PlaybackEngine {
            override val state = PlaybackState()
            override fun prepare(media: PlaybackMedia) = Unit
            override fun play() = Unit
            override fun pause() { playbackPauses.incrementAndGet() }
            override fun seekTo(positionMs: Long) = Unit
            override fun release() = Unit
            override fun addListener(listener: (PlaybackState) -> Unit) { listeners.incrementAndGet() }
            override fun removeListener(listener: (PlaybackState) -> Unit) { removedListeners.incrementAndGet() }
        }
        controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = { repository }, roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                val queue = testQueueCoordinator(
                    sessionScope, credentials.code, credentials.hostToken,
                    TestQueueCommand { _, _, _ -> },
                    TestRoomFetcher { _, _ -> Cancelable { } }, observer,
                    mutationContext = mutationLane.context,
                )
                if (queueConstructions.incrementAndGet() == 1) controller.endRoom()
                queue
            },
            playbackCoordinatorFactory = { _, credentials, observer, advance, sessionScope ->
                val playback = AuthoritativePlaybackCoordinator(
                    credentials.code, "https://example/stream", engine,
                    { RoomFetchResult.Failure }, sessionScope,
                    QueueMutationContext(Dispatchers.Unconfined) { true }, advance, observer,
                )
                playbackConstructions.incrementAndGet()
                controller.endRoom()
                playback
            },
        ).also { controllers.add(it) }
        controller.createRoom()
        controller.awaitCreatedForTest("first factory case invitation")
        repository.awaitObservationCountForTest(1)
        controller.enterRoom()
        controller.awaitSetupForTest("first factory-offered End joins installed resources")
        repository.awaitClosedCountForTest(2)
        assertTrue(controller.state is HostingState.Setup)
        assertEquals("ABCD", repository.roomCode)
        assertEquals("FIFO installs live collection before handling End", 2, repository.observations)
        assertEquals("queue factory End must not skip playback factory", 1, playbackConstructions.get())
        assertEquals(1, playbackPauses.get())
        assertEquals(1, removedListeners.get())

        controller.createRoom()
        controller.awaitCreatedForTest("replacement factory case invitation")
        repository.awaitObservationCountForTest(3)
        controller.enterRoom()
        controller.awaitSetupForTest("second factory-offered End joins installed resources")
        repository.awaitClosedCountForTest(4)
        assertTrue(controller.state is HostingState.Setup)
        assertEquals("ABCD", repository.roomCode)
        assertEquals(4, repository.observations)
        assertEquals(2, queueConstructions.get())
        assertEquals(2, playbackConstructions.get())
        assertEquals(2, playbackPauses.get())
        assertEquals("every installed listener removed exactly once", listeners.get(), removedListeners.get())
        val requests = (1..4).map { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
            "factory teardown authenticated request: timed out; last state=${controller.state}"
        } }
        assertEquals(2, requests.count { it.method == "POST" && it.path == "/rooms" })
        assertEquals(2, requests.count { it.method == "DELETE" && it.path == "/rooms/ABCD" })
        requests.filter { it.method == "DELETE" }.forEach { assertEquals("host-secret", it.getHeader("X-Host-Token")) }
    }
    private class QueuedCoroutineDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        class PendingTask(val job: kotlinx.coroutines.Job, val task: Runnable) : Runnable {
            override fun run() = task.run()
        }
        private val tasks = LinkedBlockingQueue<PendingTask>()
        private var released = false
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            synchronized(tasks) {
                if (!released) {
                    tasks.add(PendingTask(checkNotNull(context[kotlinx.coroutines.Job]), block))
                    return
                }
            }
            Dispatchers.IO.dispatch(context, block)
        }
        fun awaitPending(controller: HostSessionController): PendingTask =
            tasks.poll(5, TimeUnit.SECONDS) ?: throw AssertionError(
                "recovery worker queued on held dispatcher: timed out; last state=${controller.state}",
            )
        fun release() {
            val pending = synchronized(tasks) {
                released = true
                buildList { while (true) add(tasks.poll() ?: break) }
            }
            pending.forEach(Runnable::run)
        }
    }

    private class RecordingRoomRepository : RoomRepository {
        @Volatile var roomCode: String? = null
        @Volatile var closed = false
        @Volatile var observations = 0
        private val firstObservation = CountDownLatch(1)
        private val collections = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.Job>()
        private val states = Channel<RoomSyncState>(Channel.UNLIMITED)

        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            try {
                this@RecordingRoomRepository.roomCode = roomCode
                closed = false
                collections.add(checkNotNull(kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]))
                observations++
                firstObservation.countDown()
                for (state in states) emit(state)
            } finally {
                closed = true
            }
        }

        fun publish(state: RoomSyncState) {
            states.trySend(state)
        }

        fun awaitObservationForTest() {
            if (!firstObservation.await(5, TimeUnit.SECONDS)) {
                throw AssertionError("repository first collection readiness: timed out; observations=$observations, closed=$closed")
            }
        }

        fun awaitObservationCountForTest(count: Int) {
            awaitConditionForTest("repository collection $count armed cancellation cleanup") { observations >= count }
        }

        fun awaitClosedCountForTest(count: Int) {
            awaitConditionForTest("repository collection $count fully completed") {
                collections.size >= count && collections.take(count).all { it.isCompleted }
            }
        }
    }

}
