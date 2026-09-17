package com.qmix.tv

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QueueAdvancementInstrumentationTest {
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
    fun production_application_constructs_queue_advancement_dependencies_for_a_live_room() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"code":"ABCD","current":null,"queue":[]}""",
            ),
        )
        val application = ApplicationProvider.getApplicationContext<QMixApplication>()
        val controller = application.hostSession
        controller.endRoom()
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val invited = CountDownLatch(1)
        val subscription = controller.observe { if (it is HostingState.Invitation) invited.countDown() }
        try {
            assertTrue(controller.createRoom())
            assertTrue(invited.await(5, TimeUnit.SECONDS))

            controller.enterRoom()

            assertTrue(controller.state is HostingState.LiveRoom)
        } finally {
            subscription.close()
            controller.endRoom()
        }
    }

    @Test
    fun asynchronous_http_command_reports_success_rejection_and_decode_uncertainty() {
        val command = AsyncRoomAdvanceCommand(
            RoomApiClient(OkHttpClient(), server.url("/").toString()),
            Executor { it.run() },
        )
        val observed = mutableListOf<QueueAdvanceCommandResult>()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"current":{"track_id":"next","pos_sec":0,"state":"playing","title":"Next","artist":"Artist"}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        command.skip("ABCD", "host-secret", observed::add)
        command.skip("ABCD", "host-secret", observed::add)
        command.skip("ABCD", "host-secret", observed::add)
        command.skip("ABCD", "malformed\nheader", observed::add)

        assertEquals(
            listOf(
                QueueAdvanceCommandResult.Success,
                QueueAdvanceCommandResult.Rejected,
                QueueAdvanceCommandResult.Indeterminate,
                QueueAdvanceCommandResult.Rejected,
            ),
            observed,
        )
        assertEquals(3, server.requestCount)
    }

    @Test
    fun coordinator_covers_dispatch_failure_missing_room_and_close_during_notification() {
        val room = RoomState(
            code = "ABCD",
            current = CurrentTrack("current", 0, "playing", "Current", "Artist"),
            queue = listOf(QueuedTrack("next", "https://example/next", "Next", "Artist", 60, "fixture")),
        )
        var fetchCallback: ((RoomFetchResult) -> Unit)? = null
        val dispatchFailure = QueueAdvancementCoordinator(
            roomCode = "ABCD",
            hostToken = "host-secret",
            command = QueueAdvanceCommand { _, _, _ -> throw IllegalStateException("dispatch") },
            reconciler = RoomStateFetcher { _, callback ->
                fetchCallback = callback
                Cancelable { }
            },
        )
        dispatchFailure.onAuthoritativeRoom(room)

        assertTrue(dispatchFailure.requestExplicitAdvance())
        assertTrue(dispatchFailure.state.pending)
        fetchCallback?.invoke(RoomFetchResult.Missing)
        assertFalse(dispatchFailure.state.pending)
        assertEquals(QueueAdvanceOutcome.REJECTED, dispatchFailure.state.lastOutcome)

        var dispatched = false
        lateinit var closing: QueueAdvancementCoordinator
        closing = QueueAdvancementCoordinator(
            roomCode = "ABCD",
            hostToken = "host-secret",
            command = QueueAdvanceCommand { _, _, _ -> dispatched = true },
            reconciler = RoomStateFetcher { _, _ -> Cancelable { } },
            observer = { if (it.pending) closing.close() },
        )
        closing.onAuthoritativeRoom(room)

        assertFalse(closing.requestExplicitAdvance())
        assertFalse(dispatched)
    }

    @Test
    fun host_controller_routes_explicit_and_ended_actions_through_its_live_coordinator() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""",
            ),
        )
        val repository = RecordingRepository()
        val commands = mutableListOf<(QueueAdvanceCommandResult) -> Unit>()
        val controller = HostSessionController(
            httpClient = OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            executor = Executor { it.run() },
            roomRepositoryFactory = { repository },
            queueCoordinatorFactory = { _, credentials, observer ->
                QueueAdvancementCoordinator(
                    credentials.code,
                    credentials.hostToken,
                    QueueAdvanceCommand { _, _, callback -> commands += callback },
                    RoomStateFetcher { _, _ -> Cancelable { } },
                    observer,
                )
            },
        )
        assertTrue(controller.createRoom())
        controller.enterRoom()
        repository.publish(
            RoomSyncState.Active(
                "ABCD",
                RoomState(
                    "ABCD",
                    CurrentTrack("current", 0, "playing", "Current", "Artist"),
                    listOf(QueuedTrack("next", "https://example/next", "Next", "Artist", 60, "fixture")),
                ),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            ),
        )

        controller.onStartOrNext()
        assertFalse(controller.onPlaybackEnded("current"))
        assertEquals(1, commands.size)
        controller.endRoom()
        commands.single()(QueueAdvanceCommandResult.Indeterminate)
        controller.onStartOrNext()
        assertEquals(1, commands.size)
    }

    private class RecordingRepository : RoomRepository {
        private var observer: ((RoomSyncState) -> Unit)? = null

        override fun observe(roomCode: String, onUpdate: (RoomSyncState) -> Unit): AutoCloseable {
            observer = onUpdate
            return AutoCloseable { observer = null }
        }

        fun publish(state: RoomSyncState) {
            observer?.invoke(state)
        }
    }
}
