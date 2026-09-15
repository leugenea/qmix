package com.qmix.tv

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
            assertEquals(HostingState.Setup("https://qmix.example", "https://qmix.example"), observed.single())
            controller.updateSettings(server.url("/").toString(), "https://guest.example/base?secret=no#fragment")
            assertTrue(controller.createRoom())
            assertEquals(HostingState.Pending(server.url("/").toString(), "https://guest.example/base?secret=no#fragment"), controller.state)

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
            HostingState.Error("Enter valid absolute http(s) URLs.", "not a url", "also not a url"),
            controller.state,
        )

        server.enqueue(MockResponse().setResponseCode(503))
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        assertTrue(controller.createRoom())
        executor.runNext()
        assertEquals(
            HostingState.Error(
                "The server is temporarily unavailable.",
                server.url("/").toString(),
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
                "Enter valid absolute http(s) URLs.",
                server.url("/").toString(),
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
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = backend,
            initialGuestOrigin = "https://guest.example",
            executor = Executor { it.run() },
            roomRepositoryFactory = {
                assertEquals(backend, it)
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
            assertEquals(HostingState.Setup(backend, "https://guest.example"), controller.state)
            repository.publish(synchronized.copy(freshness = Freshness.STALE))
            assertNull(controller.roomSyncState)
        } finally {
            observation.close()
        }
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
