package com.qmix.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayDeque
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostSessionControllerInstrumentationTest {
    private val roomScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        roomScope.cancel()
        server.shutdown()
    }

    @Test
    fun observer_settings_pending_invitation_and_live_room_form_one_session() {
        server.enqueue(
            MockResponse().setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS).setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val controller = HostSessionController(OkHttpClient())
        val observed = mutableListOf<HostingState>()
        val subscription = controller.collectStatesForTest(observed::add)
        try {
            assertEquals(HostingState.Setup("", ""), observed.single())
            controller.updateSettings(server.url("/").toString(), "https://guest.example")
            assertTrue(controller.createRoom())
            assertEquals(
                HostingState.Pending(server.url("/").toString().trimEnd('/'), "https://guest.example"),
                controller.state,
            )

            controller.updateSettings("https://ignored.example", "https://ignored.example")
            assertFalse(controller.createRoom())
            controller.enterRoom()
            controller.awaitCreatedForTest()

            assertEquals(
                HostingState.Invitation(GuestInvite("ABCD", "https://guest.example/r/ABCD")),
                controller.state,
            )
            assertFalse(controller.state.toString().contains("host-secret"))
            controller.enterRoom()
            assertEquals(
                HostingState.LiveRoom(
                    GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                    RoomSyncState.Active("ABCD", null, Freshness.LOADING, LiveConnection.CONNECTING),
                ),
                controller.state,
            )
            controller.enterRoom()
            assertEquals(1, server.requestCount)
        } finally {
            subscription.close()
        }

        val observedBeforeUpdate = observed.size
        controller.updateSettings("https://unused.example", "https://unused.example")
        assertEquals(observedBeforeUpdate, observed.size)
    }

    @Test
    fun invalid_settings_and_server_failure_can_be_corrected_and_retried() {
        val controller = HostSessionController(OkHttpClient())
        controller.updateSettings("not a url", "also not a url")

        assertFalse(controller.createRoom())
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
        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
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
        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
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
            )
            controller.updateSettings(backend, guestOrigin)

            assertTrue(controller.createRoom())
            assertEquals(HostingState.HttpWarning(backend, guestOrigin), controller.state)
            assertEquals(0, server.requestCount)

            controller.cancelHttpWarning()

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

        )

        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
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
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = backend,
            initialGuestOrigin = "https://guest.example",

            roomRepositoryFactory = {
                assertEquals(canonicalBackend, it)
                repository
            },
            roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
        )
        val observed = mutableListOf<HostingState>()
        val observation = controller.collectStatesForTest(observed::add)

        try {
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
            controller.enterRoom()
            val synchronized = RoomSyncState.Active(
                "ABCD",
                RoomState("ABCD", null, emptyList()),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            )
            repository.publish(synchronized)

            controller.awaitStateForTest { (it as? HostingState.LiveRoom)?.synchronization == synchronized }
            assertEquals("ABCD", repository.roomCode)
            assertEquals(synchronized, controller.roomSyncState)
            assertEquals(synchronized, (observed.last() as HostingState.LiveRoom).synchronization)
            controller.endRoom()
            controller.awaitSetupForTest()

            assertTrue(repository.closed)
            assertNull(controller.roomSyncState)
            assertEquals(HostingState.Setup(canonicalBackend, "https://guest.example"), controller.state)
            repository.publish(synchronized.copy(freshness = Freshness.STALE))
            assertNull(controller.roomSyncState)
        } finally {
            observation.close()
        }
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

        )
        val observed = mutableListOf<HostingState>()
        controller.collectStatesForTest(observed::add)
        controller.collectStatesForTest { state ->
            if (state is HostingState.LiveRoom && state.commandPending) {
                error("observer failure")
            }
        }

        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
        val queue = listOf(QueuedTrack("track-1", "https://example/1", "Title", "Artist", 0, "fixture"))
        val room = RoomState("ABCD", null, queue)
        val synchronized = RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED)
        repository.publish(synchronized)
        controller.awaitStateForTest { (it as? HostingState.LiveRoom)?.synchronization == synchronized }
        val ready = controller.state as HostingState.LiveRoom
        assertEquals(LiveRoomPrimaryAction.START, ready.primaryAction)
        assertTrue(ready.isPrimaryActionEnabled)
        assertFalse(ready.toString().contains("host-secret"))

        controller.onStartOrNext()
        controller.setCommandPending(true)
        controller.onStartOrNext()
        assertEquals(1, primaryActions)
        assertFalse((controller.state as HostingState.LiveRoom).isPrimaryActionEnabled)
        assertTrue((observed.last() as HostingState.LiveRoom).commandPending)

        controller.setCommandPending(false)
        controller.onInvite()
        assertTrue((controller.state as HostingState.LiveRoom).invitationVisible)
        assertTrue((observed.last() as HostingState.LiveRoom).invitationVisible)
        controller.onInvite()
        assertEquals(LiveRoomBackResult.HANDLED, controller.onBack())
        assertFalse((controller.state as HostingState.LiveRoom).invitationVisible)
        assertFalse((observed.last() as HostingState.LiveRoom).invitationVisible)
        assertEquals(LiveRoomBackResult.EXIT_ACTIVITY, controller.onBack())
        controller.awaitSetupForTest()
        assertTrue(repository.closed)
        controller.awaitSetupForTest()
        assertTrue(controller.state is HostingState.Setup)
        assertTrue(observed.last() is HostingState.Setup)
        assertEquals(LiveRoomBackResult.IGNORED, controller.onBack())
        assertEquals(listOf(LiveRoomPrimaryAction.START, LiveRoomPrimaryAction.NEXT), LiveRoomPrimaryAction.entries)
        assertEquals(
            listOf(LiveRoomBackResult.HANDLED, LiveRoomBackResult.EXIT_ACTIVITY, LiveRoomBackResult.IGNORED),
            LiveRoomBackResult.entries,
        )
    }

    @Test
    fun stale_room_updates_retain_data_and_session_generation_rejects_late_updates() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"replacement-secret","url":"/r/ABCD"}"""),
        )
        val firstRepository = RecordingRoomRepository()
        val secondRepository = RecordingRoomRepository()
        val repositories = ArrayDeque(listOf(firstRepository, secondRepository))
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",

            roomRepositoryFactory = { repositories.removeFirst() },
            roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
        )
        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
        val room = RoomState("ABCD", null, emptyList())
        firstRepository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitStateForTest { (it as? HostingState.LiveRoom)?.synchronization ==
            RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED) }
        firstRepository.publish(RoomSyncState.Active("ABCD", null, Freshness.STALE, LiveConnection.RECONNECTING))

        controller.awaitStateForTest { (it as? HostingState.LiveRoom)?.synchronization ==
            RoomSyncState.Active("ABCD", room, Freshness.STALE, LiveConnection.RECONNECTING) }
        val stale = controller.roomSyncState as RoomSyncState.Active
        assertEquals(room, stale.room)
        assertEquals(Freshness.STALE, stale.freshness)
        assertEquals(LiveConnection.RECONNECTING, stale.connection)

        val beforeOtherRoom = controller.state
        firstRepository.publish(
            RoomSyncState.Active(
                "WXYZ",
                RoomState("WXYZ", null, emptyList()),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            ),
        )
        assertEquals(beforeOtherRoom, controller.state)
        controller.endRoom()
        controller.awaitSetupForTest()
        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
        val replacementInitialState = controller.state

        firstRepository.publish(RoomSyncState.Missing("ABCD"))

        assertEquals(replacementInitialState, controller.state)
        assertTrue(firstRepository.closed)
        assertFalse(secondRepository.closed)
    }

    @Test
    fun missing_foreground_reconciliation_does_not_reopen_commands_or_restart_collection() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        var fetches = 0
        var commands = 0
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = { repository }, roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
            foregroundReconcilerFactory = { { _: String -> fetches++; RoomFetchResult.Missing } },
            queueMutationContext = QueueMutationContext(Dispatchers.Unconfined) { true },
            primaryActionHandler = { commands++ },
        )
        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
        val room = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "Artist", 60, "fixture")))
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitStateForTest { (it as? HostingState.LiveRoom)?.isPrimaryActionEnabled == true }
        assertTrue((controller.state as HostingState.LiveRoom).isPrimaryActionEnabled)

        controller.onHostStopped()
        awaitConditionForTest { repository.closed }
        controller.onHostStarted()
        controller.awaitStateForTest { (it as? HostingState.LiveRoom)?.synchronization is RoomSyncState.Missing }
        assertEquals(1, fetches)
        assertEquals(RoomSyncState.Missing("ABCD"), controller.roomSyncState)
        assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        controller.onStartOrNext()
        assertEquals(0, commands)
        controller.endRoom()
        controller.awaitSetupForTest()
        controller.awaitSetupForTest()
        assertTrue(controller.state is HostingState.Setup)
    }

    @Test
    fun thrown_foreground_fetch_keeps_commands_closed_until_a_later_fresh_retry_succeeds() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        val room = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "Artist", 60, "fixture")))
        var fetches = 0
        var commands = 0
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = { repository }, roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
            foregroundReconcilerFactory = { { _: String ->
                fetches++
                if (fetches == 1) throw IllegalStateException("temporary fetch failure")
                if (fetches == 2) RoomFetchResult.Success(room.copy(code = "OTHER"))
                else RoomFetchResult.Success(room)
            } },
            queueMutationContext = QueueMutationContext(Dispatchers.Unconfined) { true },
            primaryActionHandler = { commands++ },
        )
        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitStateForTest { (it as? HostingState.LiveRoom)?.synchronization ==
            RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED) }
        controller.onHostStopped()
        controller.onHostStarted()
        awaitConditionForTest { fetches == 1 && repository.observations >= 2 }
        assertEquals(1, fetches)
        assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        controller.onStartOrNext()
        assertEquals(0, commands)

        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        awaitConditionForTest { fetches == 2 && repository.observations >= 3 }
        assertEquals(2, fetches)
        assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        controller.onStartOrNext()
        assertEquals(0, commands)

        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitStateForTest { (it as? HostingState.LiveRoom)?.foregroundRecoveryPending == false }
        assertEquals(3, fetches)
        assertFalse((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        controller.onStartOrNext()
        assertEquals(1, commands)
        controller.endRoom()
        assertTrue(repository.closed)
    }

    @Test
    fun recovery_observer_ending_session_cannot_restart_collection_or_reopen_commands() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        val room = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "Artist", 60, "fixture")))
        var commands = 0
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = { repository }, roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
            foregroundReconcilerFactory = { { _: String -> RoomFetchResult.Success(room) } },
            queueMutationContext = QueueMutationContext(Dispatchers.Unconfined) { true },
            primaryActionHandler = { commands++ },
        )
        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.onHostStopped()
        controller.collectStatesForTest { state ->
            if (state is HostingState.LiveRoom && !state.foregroundRecoveryPending &&
                state.synchronization is RoomSyncState.Active &&
                state.synchronization.freshness == Freshness.FRESH
            ) {
                controller.endRoom()
            }
        }

        controller.onHostStarted()
        controller.onStartOrNext()
        assertEquals(0, commands)
        assertTrue(repository.closed)
        controller.awaitSetupForTest()
        assertTrue(controller.state is HostingState.Setup)
    }

    @Test
    fun stop_cancels_recovery_queued_on_mutation_dispatcher_before_fetch_or_command_admission() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val dispatcher = QueuedCoroutineDispatcher()
        val repository = RecordingRoomRepository()
        val room = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "Artist", 60, "fixture")))
        var fetches = 0
        var commands = 0
        val controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = { repository }, roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
            foregroundReconcilerFactory = { { _: String -> fetches++; RoomFetchResult.Success(room) } },
            queueMutationContext = QueueMutationContext(dispatcher) { true },
            primaryActionHandler = { commands++ },
        )
        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        awaitConditionForTest {
            dispatcher.runPending()
            (controller.state as? HostingState.LiveRoom)?.synchronization ==
                RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED)
        }
        controller.onHostStopped()
        controller.onHostStarted()
        controller.onHostStopped()
        dispatcher.runPending()
        controller.onStartOrNext()
        assertEquals(0, fetches)
        assertEquals(0, commands)
        assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)

        controller.onHostStarted()
        awaitConditionForTest {
            dispatcher.runPending()
            fetches == 1 && (controller.state as HostingState.LiveRoom).foregroundRecoveryPending == false
        }
        assertEquals(1, fetches)
        assertFalse((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        controller.onStartOrNext()
        assertEquals(1, commands)
        controller.endRoom()
    }

    @Test
    fun foreground_loss_during_repository_creation_cancels_unstarted_collection_and_recovers() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        val repository = RecordingRoomRepository()
        val room = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "Artist", 60, "fixture")))
        lateinit var controller: HostSessionController
        var creations = 0
        var fetches = 0
        controller = HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = {
                if (++creations == 1) controller.onHostStopped()
                repository
            },
            roomCollectionScope = roomScope, roomCollectionContext = Dispatchers.Unconfined,
            foregroundReconcilerFactory = { { _: String -> fetches++; RoomFetchResult.Success(room) } },
            queueMutationContext = QueueMutationContext(Dispatchers.Unconfined) { true },
        )
        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
        assertEquals(1, creations)
        assertNull(repository.roomCode)
        assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)

        controller.onHostStarted()
        assertEquals(1, fetches)
        assertEquals(2, creations)
        assertEquals("ABCD", repository.roomCode)
        assertFalse((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        assertTrue((controller.state as HostingState.LiveRoom).isPrimaryActionEnabled)
        controller.endRoom()
        assertTrue(repository.closed)
    }

    @Test
    fun ending_session_inside_coordinator_factories_discards_handles_before_collection_starts() {
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        }
        val repository = RecordingRoomRepository()
        var queueConstructions = 0
        var playbackConstructions = 0
        var playbackPauses = 0
        lateinit var controller: HostSessionController
        val engine = object : PlaybackEngine {
            override val state = PlaybackState()
            override fun prepare(media: PlaybackMedia) = Unit
            override fun play() = Unit
            override fun pause() { playbackPauses++ }
            override fun seekTo(positionMs: Long) = Unit
            override fun release() = Unit
            override fun addListener(listener: (PlaybackState) -> Unit) = Unit
            override fun removeListener(listener: (PlaybackState) -> Unit) = Unit
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
                )
                if (++queueConstructions == 1) controller.endRoom()
                queue
            },
            playbackCoordinatorFactory = { _, credentials, observer, advance, sessionScope ->
                val playback = AuthoritativePlaybackCoordinator(
                    credentials.code, "https://example/stream", engine,
                    { RoomFetchResult.Failure }, sessionScope,
                    QueueMutationContext(Dispatchers.Unconfined) { true }, advance, observer,
                )
                playbackConstructions++
                controller.endRoom()
                playback
            },
        )
        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
        controller.awaitSetupForTest()
        assertTrue(controller.state is HostingState.Setup)
        assertNull(repository.roomCode)
        assertEquals(0, playbackConstructions)

        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
        controller.awaitSetupForTest()
        assertTrue(controller.state is HostingState.Setup)
        assertNull(repository.roomCode)
        assertEquals(2, queueConstructions)
        assertEquals(1, playbackConstructions)
        assertEquals(1, playbackPauses)
    }

    private class QueuedCoroutineDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }
        fun runPending() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class RecordingRoomRepository : RoomRepository {
        var roomCode: String? = null
        @Volatile var closed = false
        @Volatile var observations = 0
        private val states = Channel<RoomSyncState>(Channel.UNLIMITED)

        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            this@RecordingRoomRepository.roomCode = roomCode
            observations++
            try {
                for (state in states) emit(state)
            } finally {
                closed = true
            }
        }

        fun publish(state: RoomSyncState) {
            states.trySend(state)
        }
    }

}
