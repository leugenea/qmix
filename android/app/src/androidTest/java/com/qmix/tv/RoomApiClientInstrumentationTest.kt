package com.qmix.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomApiClientInstrumentationTest {
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: MockWebServer
    private lateinit var api: RoomApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = RoomApiClient(OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() {
        adapterScope.cancel()
        server.shutdown()
    }

    @Test
    fun create_get_and_skip_use_the_wire_contract_without_leaking_credentials() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val credentials = runBlocking { api.createRoom() }
        assertEquals("ABCD", credentials.code)
        assertEquals("host-secret", credentials.hostToken)
        assertEquals("/r/ABCD", credentials.relativeGuestUrl)
        assertFalse(credentials.toString().contains("host-secret"))
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("/rooms", request.path)
            assertEquals(0L, request.bodySize)
            assertNull(request.headers["X-Host-Token"])
        }

        server.enqueue(
            MockResponse().setBody(
                """{"code":"ABCD","current":{"track_id":"now","pos_sec":12,"state":"playing","title":"Now","artist":""},"queue":[{"id":"next","url":"https://music.example/next","title":"Next","artist":"Other","duration_sec":180,"resolved_by":"test"}]}""",
            ),
        )
        val room = runBlocking { api.getRoom("AB CD") }
        assertEquals("ABCD", room.code)
        val current = requireNotNull(room.current)
        assertEquals("now", current.trackId)
        assertEquals(12, current.positionSeconds)
        assertEquals("playing", current.state)
        assertEquals("Now", current.title)
        assertEquals("", current.artist)
        val queued = room.queue.single()
        assertEquals("next", queued.id)
        assertEquals("https://music.example/next", queued.url)
        assertEquals("Next", queued.title)
        assertEquals("Other", queued.artist)
        assertEquals(180, queued.durationSeconds)
        assertEquals("test", queued.resolvedBy)
        assertEquals(CurrentTrack("now", 12, "playing", "Now", ""), current)
        assertEquals(
            listOf(QueuedTrack("next", "https://music.example/next", "Next", "Other", 180, "test")),
            room.queue,
        )
        server.takeRequest().also { request ->
            assertEquals("GET", request.method)
            assertEquals("/rooms/AB%20CD", request.path)
            assertNull(request.headers["X-Host-Token"])
        }

        server.enqueue(
            MockResponse().setBody(
                """{"current":{"track_id":"next","pos_sec":0,"state":"paused","title":"Next","artist":"Artist"}}""",
            ),
        )
        assertEquals(CurrentTrack("next", 0, "paused", "Next", "Artist"), runBlocking { api.skip("AB CD", "host-secret") })
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("/rooms/AB%20CD/skip", request.path)
            assertEquals("host-secret", request.headers["X-Host-Token"])
            assertEquals(0L, request.bodySize)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun nullable_current_and_integral_long_values_are_accepted() {
        server.enqueue(
            MockResponse().setBody(
                """{"code":"ABCD","current":null,"queue":[{"id":"next","url":"u","title":"Next","artist":"","duration_sec":2147483647,"resolved_by":""}]}""",
            ),
        )

        val room = runBlocking { api.getRoom("ABCD") }

        assertNull(room.current)
        assertEquals(Int.MAX_VALUE, room.queue.single().durationSeconds)
    }

    @Test
    fun every_http_status_family_has_a_safe_human_message() {
        listOf(
            403 to UserMessage.HOST_ACCESS_DENIED,
            404 to UserMessage.ROOM_NOT_FOUND,
            503 to UserMessage.SERVER_UNAVAILABLE,
            418 to UserMessage.REQUEST_REJECTED,
        ).forEach { (status, expected) ->
            server.enqueue(MockResponse().setResponseCode(status))
            val error = assertThrows(RoomApiException::class.java) { runBlocking { api.getRoom("ABCD") } }
            assertEquals(expected, error.userMessage)
        }
    }

    @Test
    fun malformed_json_and_wrong_dto_shapes_are_rejected() {
        val getBodies = listOf(
            "not json",
            "[]",
            """{"code":"ABCD","queue":[]}""",
            """{"code":"ABCD","current":"playing","queue":[]}""",
            """{"code":"ABCD","current":null}""",
            """{"code":"ABCD","current":null,"queue":["bad"]}""",
            """{"code":"","current":null,"queue":[]}""",
            """{"code":1234,"current":null,"queue":[]}""",
            """{"code":"ABCD","current":{"track_id":"now","pos_sec":"12","state":"playing","title":"Now","artist":"Artist"},"queue":[]}""",
            """{"code":"ABCD","current":{"track_id":"now","pos_sec":2147483648,"state":"playing","title":"Now","artist":"Artist"},"queue":[]}""",
            """{"code":"ABCD","current":{"track_id":"","pos_sec":1,"state":"playing","title":"Now","artist":"Artist"},"queue":[]}""",
            """{"code":"ABCD","current":null,"queue":[{"id":"next","url":"u","title":"Next","artist":"A","duration_sec":"180","resolved_by":"test"}]}""",
            """{"code":"ABCD","current":null,"queue":[{"id":"next","url":"","title":"Next","artist":"A","duration_sec":180,"resolved_by":"test"}]}""",
        )
        getBodies.forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertInvalid { runBlocking { api.getRoom("ABCD") } }
        }

        listOf(
            """{"code":1234,"host_token":"secret","url":"/r/ABCD"}""",
            """{"code":"ABCD","host_token":"","url":"/r/ABCD"}""",
        ).forEach { body ->
            server.enqueue(MockResponse().setResponseCode(201).setBody(body))
            assertInvalid { runBlocking { api.createRoom() } }
        }

        listOf(
            "{}",
            """{"current":"bad"}""",
            """{"current":{"track_id":"x","pos_sec":0,"state":"playing","title":"X"}}""",
        ).forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertInvalid { runBlocking { api.skip("ABCD", "secret") } }
        }
    }

    @Test
    fun timeout_disconnect_and_redirect_are_not_retried_or_forwarded() {
        server.enqueue(MockResponse().setBodyDelay(1, TimeUnit.SECONDS).setBody("{}"))
        api = RoomApiClient(
            OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build(),
            server.url("/").toString(),
        )
        assertEquals(
            UserMessage.SERVER_TIMEOUT,
            assertThrows(RoomApiException::class.java) { runBlocking { api.getRoom("ABCD") } }.userMessage,
        )

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(
            UserMessage.SERVER_UNREACHABLE,
            assertThrows(RoomApiException::class.java) { runBlocking { api.getRoom("ABCD") } }.userMessage,
        )

        val otherOrigin = MockWebServer()
        otherOrigin.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(307)
                    .addHeader("Location", otherOrigin.url("/capture")),
            )
            assertEquals(
                UserMessage.REQUEST_REJECTED,
                assertThrows(RoomApiException::class.java) { runBlocking { api.skip("ABCD", "host-secret") } }.userMessage,
            )
            assertEquals(0, otherOrigin.requestCount)
        } finally {
            otherOrigin.shutdown()
        }
    }

    @Test
    fun asynchronous_room_fetch_classifies_success_missing_decode_and_transport_failures() {
        fun fetch(response: MockResponse): RoomFetchResult {
            server.enqueue(response)
            val completed = CountDownLatch(1)
            var result: RoomFetchResult? = null
            api.roomFetcher(adapterScope).fetch("ABCD") {
                result = it
                completed.countDown()
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            return requireNotNull(result)
        }

        assertEquals(
            RoomFetchResult.Success(RoomState("ABCD", null, emptyList())),
            fetch(MockResponse().setBody("""{"code":"ABCD","current":null,"queue":[]}""")),
        )
        assertEquals(RoomFetchResult.Missing, fetch(MockResponse().setResponseCode(404)))
        assertEquals(RoomFetchResult.Failure, fetch(MockResponse().setBody("not json")))
        assertEquals(
            RoomFetchResult.Failure,
            fetch(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)),
        )
    }

    @Test
    fun player_reports_use_the_exact_authenticated_wire_contract_and_classify_every_response() {
        val cases = listOf(
            Triple(200, PlayerReportState.PLAYING, PlayerReportResult.ACCEPTED),
            Triple(403, PlayerReportState.PAUSED, PlayerReportResult.FORBIDDEN),
            Triple(404, PlayerReportState.ERROR, PlayerReportResult.MISSING),
            Triple(409, PlayerReportState.ENDED, PlayerReportResult.CONFLICT),
            Triple(429, PlayerReportState.PLAYING, PlayerReportResult.FAILED),
        )

        cases.forEachIndexed { index, (status, state, expected) ->
            server.enqueue(MockResponse().setResponseCode(status))
            val actual = report(PlayerReport("track-$index", state, index + 7))

            assertEquals(expected, actual)
            server.takeRequest().also { request ->
                assertEquals("PATCH", request.method)
                assertEquals("/rooms/AB%20CD/player", request.path)
                assertEquals("host-secret", request.headers["X-Host-Token"])
                assertEquals(
                    """{"track_id":"track-$index","state":"${state.wireValue}","pos_sec":${index + 7}}""",
                    request.body.readUtf8(),
                )
            }
        }
        assertEquals(cases.size, server.requestCount)
    }

    @Test
    fun player_report_transport_failure_completes_once_without_retry() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(
            PlayerReportResult.FAILED,
            report(PlayerReport("track", PlayerReportState.PLAYING, 11)),
        )
        assertEquals(1, server.requestCount)
    }

    private fun report(playerReport: PlayerReport): PlayerReportResult {
        return runBlocking { api.reportPlayer("AB CD", "host-secret", playerReport) }
    }

    private fun assertInvalid(block: () -> Unit) {
        val error = assertThrows(RoomApiException::class.java, block)
        assertEquals(UserMessage.INVALID_RESPONSE, error.userMessage)
    }
}
