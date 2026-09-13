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
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
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
    fun invitation_action_transitions_to_room_placeholder() {
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

        assertEquals(HostingState.RoomPlaceholder("ABCD"), controller.state)
    }

    @Test
    fun observer_receives_setup_and_pending_then_stops_after_close() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val executor = QueuedExecutor()
        val controller = HostSessionController(OkHttpClient(), executor = executor)
        val observed = mutableListOf<HostingState>()
        val subscription = controller.observe(observed::add)

        assertEquals(HostingState.Setup("https://qmix.example", "https://qmix.example"), observed.single())
        controller.updateSettings(server.url("/").toString(), "https://guest.example/base?secret=no#fragment")
        assertTrue(controller.createRoom())
        assertEquals(
            HostingState.Pending(server.url("/").toString(), "https://guest.example/base?secret=no#fragment"),
            controller.state,
        )
        controller.updateSettings("https://ignored.example", "https://ignored.example")
        controller.enterRoom()
        assertEquals(
            HostingState.Pending(server.url("/").toString(), "https://guest.example/base?secret=no#fragment"),
            controller.state,
        )
        executor.runNext()
        assertEquals(
            HostingState.Invitation(GuestInvite("ABCD", "https://guest.example/r/ABCD")),
            controller.state,
        )
        controller.enterRoom()
        assertEquals(HostingState.RoomPlaceholder("ABCD"), controller.state)
        controller.enterRoom()
        assertEquals(HostingState.RoomPlaceholder("ABCD"), controller.state)

        subscription.close()
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
