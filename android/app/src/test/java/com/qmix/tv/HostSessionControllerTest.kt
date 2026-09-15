package com.qmix.tv

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSessionControllerTest {
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
            "Could not reach the server.",
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
        val controller = HostSessionController(OkHttpClient())
        val setup = HostingState.Setup(server.url("/").toString(), "https://guest.example")
        controller.updateSettings(setup.backendUrl, setup.guestOrigin)
        val invited = CountDownLatch(1)
        controller.observe { if (it is HostingState.Invitation) invited.countDown() }

        controller.createRoom()
        server.takeRequest(5, TimeUnit.SECONDS)
        controller.endRoom()

        assertEquals(false, invited.await(1, TimeUnit.SECONDS))
        assertEquals(setup, controller.state)
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
        assertEquals(HostingState.Setup(server.url("/").toString(), "https://guest.example"), controller.state)
        repository.publish(synchronized.copy(freshness = Freshness.STALE))
        assertEquals(null, controller.roomSyncState)
    }

    private class RecordingRoomRepository : RoomRepository {
        var observedCode: String? = null
        var closed = false
        private var observer: ((RoomSyncState) -> Unit)? = null

        override fun observe(roomCode: String, onUpdate: (RoomSyncState) -> Unit): AutoCloseable {
            observedCode = roomCode
            observer = onUpdate
            return AutoCloseable { closed = true }
        }

        fun publish(state: RoomSyncState) {
            observer?.invoke(state)
        }
    }
}
