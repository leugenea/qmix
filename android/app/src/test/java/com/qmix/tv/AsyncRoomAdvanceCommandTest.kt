package com.qmix.tv

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AsyncRoomAdvanceCommandTest {
    private lateinit var server: MockWebServer
    private lateinit var executor: QueuedExecutor

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        executor = QueuedExecutor()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun successful_skip_is_reported_as_success() {
        server.enqueue(
            MockResponse().setBody(
                """{"current":{"track_id":"next","pos_sec":0,"state":"playing","title":"Next","artist":"Artist"}}""",
            ),
        )
        val observed = mutableListOf<QueueAdvanceCommandResult>()
        val command = AsyncRoomAdvanceCommand(api(), executor)

        command.skip("ABCD", "host-secret", observed::add)
        assertEquals(emptyList<QueueAdvanceCommandResult>(), observed)
        executor.runNext()

        assertEquals(listOf(QueueAdvanceCommandResult.Success), observed)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun network_failure_is_indeterminate() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val observed = mutableListOf<QueueAdvanceCommandResult>()
        val command = AsyncRoomAdvanceCommand(api(), executor)

        command.skip("ABCD", "host-secret", observed::add)
        executor.runNext()

        assertEquals(listOf(QueueAdvanceCommandResult.Indeterminate), observed)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun http_rejection_is_determinate() {
        server.enqueue(MockResponse().setResponseCode(403))
        val observed = mutableListOf<QueueAdvanceCommandResult>()
        val command = AsyncRoomAdvanceCommand(api(), executor)

        command.skip("ABCD", "host-secret", observed::add)
        executor.runNext()

        assertEquals(listOf(QueueAdvanceCommandResult.Rejected), observed)
    }

    @Test
    fun malformed_success_response_is_indeterminate_because_post_may_have_committed() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        val observed = mutableListOf<QueueAdvanceCommandResult>()
        val command = AsyncRoomAdvanceCommand(api(), executor)

        command.skip("ABCD", "host-secret", observed::add)
        executor.runNext()

        assertEquals(listOf(QueueAdvanceCommandResult.Indeterminate), observed)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun malformed_server_issued_host_token_still_completes_the_command() {
        val observed = mutableListOf<QueueAdvanceCommandResult>()
        val command = AsyncRoomAdvanceCommand(api(), executor)

        command.skip("ABCD", "malformed\nheader", observed::add)
        executor.runNext()

        assertEquals(listOf(QueueAdvanceCommandResult.Rejected), observed)
        assertEquals(0, server.requestCount)
    }

    private fun api() = RoomApiClient(
        OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build(),
        server.url("/").toString(),
    )

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }
}
