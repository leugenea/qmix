package com.qmix.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.util.ArrayDeque
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class HostSessionControllerInstrumentationTest {
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

    @Test
    fun observer_settings_pending_invitation_and_live_room_form_one_session() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val executor = QueuedExecutor()
        val controller = HostSessionController(OkHttpClient(), executor = executor)
        val observed = mutableListOf<HostingState>()
        val subscription = controller.observe(observed::add)
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
            executor.runNext()

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
        val executor = QueuedExecutor()
        val controller = HostSessionController(OkHttpClient(), executor = executor)
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
        executor.runNext()
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
        executor.runNext()
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
                executor = Executor { it.run() },
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
            executor = Executor { it.run() },
        )

        assertTrue(controller.createRoom())

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
            executor = Executor { it.run() },
            roomRepositoryFactory = {
                assertEquals(canonicalBackend, it)
                repository
            },
        )
        val observed = mutableListOf<HostingState>()
        val observation = controller.observe(observed::add)

        try {
            assertTrue(controller.createRoom())
            controller.enterRoom()
            val synchronized = RoomSyncState.Active(
                "ABCD",
                RoomState("ABCD", null, emptyList()),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            )
            repository.publish(synchronized)

            assertEquals("ABCD", repository.roomCode)
            assertEquals(synchronized, controller.roomSyncState)
            assertEquals(synchronized, (observed.last() as HostingState.LiveRoom).synchronization)
            controller.endRoom()

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
        val failures = mutableListOf<Throwable>()
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            executor = Executor { it.run() },
            roomRepositoryFactory = { repository },
            primaryActionHandler = { primaryActions++ },
            observerFailureHandler = failures::add,
        )
        val observed = mutableListOf<HostingState>()
        controller.observe(observed::add)
        controller.observe { state ->
            if (state is HostingState.LiveRoom && state.commandPending) {
                error("observer failure")
            }
        }

        assertTrue(controller.createRoom())
        controller.enterRoom()
        val queue = listOf(QueuedTrack("track-1", "https://example/1", "Title", "Artist", 0, "fixture"))
        val room = RoomState("ABCD", null, queue)
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        val ready = controller.state as HostingState.LiveRoom
        assertEquals(LiveRoomPrimaryAction.START, ready.primaryAction)
        assertTrue(ready.isPrimaryActionEnabled)
        assertFalse(ready.toString().contains("host-secret"))

        controller.onStartOrNext()
        controller.setCommandPending(true)
        controller.onStartOrNext()
        assertEquals(1, primaryActions)
        assertFalse((controller.state as HostingState.LiveRoom).isPrimaryActionEnabled)
        assertEquals(1, failures.size)

        controller.setCommandPending(false)
        controller.onInvite()
        assertTrue((controller.state as HostingState.LiveRoom).invitationVisible)
        assertTrue((observed.last() as HostingState.LiveRoom).invitationVisible)
        controller.onInvite()
        assertEquals(LiveRoomBackResult.HANDLED, controller.onBack())
        assertFalse((controller.state as HostingState.LiveRoom).invitationVisible)
        assertFalse((observed.last() as HostingState.LiveRoom).invitationVisible)
        assertEquals(LiveRoomBackResult.EXIT_ACTIVITY, controller.onBack())
        assertTrue(repository.closed)
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
            executor = Executor { it.run() },
            roomRepositoryFactory = { repositories.removeFirst() },
        )
        assertTrue(controller.createRoom())
        controller.enterRoom()
        val room = RoomState("ABCD", null, emptyList())
        firstRepository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
        firstRepository.publish(RoomSyncState.Active("ABCD", null, Freshness.STALE, LiveConnection.RECONNECTING))

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
        assertTrue(controller.createRoom())
        controller.enterRoom()
        val replacementInitialState = controller.state

        firstRepository.publish(RoomSyncState.Missing("ABCD"))

        assertEquals(replacementInitialState, controller.state)
        assertTrue(firstRepository.closed)
        assertFalse(secondRepository.closed)
    }

    private class RecordingRoomRepository : RoomRepository {
        var roomCode: String? = null
        var closed = false
        private var observer: ((RoomSyncState) -> Unit)? = null

        override fun observe(roomCode: String, onUpdate: (RoomSyncState) -> Unit): AutoCloseable {
            this.roomCode = roomCode
            observer = onUpdate
            return AutoCloseable { closed = true }
        }

        fun publish(state: RoomSyncState) {
            observer?.invoke(state)
        }
    }

    private class QueuedExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            commands.add(command)
        }

        fun runNext() {
            commands.removeFirst().run()
        }
    }
}
