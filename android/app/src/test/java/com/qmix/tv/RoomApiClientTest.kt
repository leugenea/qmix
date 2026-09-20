package com.qmix.tv

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomApiClientTest {
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
        server.shutdown()
    }

    @Test
    fun createRoom_posts_once_and_decodes_contract_without_host_header() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""")
                .addHeader("Content-Type", "application/json")
                ,
        )

        val room = api.createRoom()

        assertEquals(RoomCredentials("ABCD", "host-secret", "/r/ABCD"), room)
        assertFalse(room.toString().contains("host-secret"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/rooms", request.path)
        assertEquals(0L, request.bodySize)
        assertNull(request.headers["X-Host-Token"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun getRoom_decodes_current_and_queue_without_sending_host_token() {
        server.enqueue(
            MockResponse().setBody(
                """{"code":"ABCD","current":{"track_id":"now","pos_sec":12,"state":"playing","title":"Now","artist":"Artist"},"queue":[{"id":"next","url":"https://music.example/next","title":"Next","artist":"Other","duration_sec":180,"resolved_by":"test"}]}""",
            ),
        )

        val room = api.getRoom("ABCD")

        assertEquals("ABCD", room.code)
        assertEquals(CurrentTrack("now", 12, "playing", "Now", "Artist"), room.current)
        assertEquals(
            listOf(QueuedTrack("next", "https://music.example/next", "Next", "Other", 180, "test")),
            room.queue,
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/rooms/ABCD", request.path)
        assertNull(request.headers["X-Host-Token"])
    }

    @Test
    fun skip_posts_with_host_token_and_decodes_current_track() {
        server.enqueue(
            MockResponse().setBody(
                """{"current":{"track_id":"next","pos_sec":0,"state":"playing","title":"Next","artist":"Artist"}}""",
            ),
        )

        val current = api.skip("AB CD", "host-secret")

        assertEquals(CurrentTrack("next", 0, "playing", "Next", "Artist"), current)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/rooms/AB%20CD/skip", request.path)
        assertEquals("host-secret", request.headers["X-Host-Token"])
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun create_failure_logs_sanitized_api_boundary_record() {
        val sink = RecordingLogSink()
        val apiLogger = QMixLogger(sink, QMixLogLevel.DEBUG) { null }
            .component(QMixLogComponent.ROOM_API_CREATION)
        api = RoomApiClient(OkHttpClient(), server.url("/?token=do-not-log").toString(), apiLogger)
        server.enqueue(MockResponse().setResponseCode(503).setBody("host_token=do-not-log"))

        assertThrows(RoomApiException::class.java) { api.createRoom() }

        assertEquals(
            listOf(
                QMixLogRecord(
                    QMixLogLevel.ERROR,
                    QMixLogComponent.ROOM_API_CREATION,
                    QMixLogOperation.CREATE_ROOM,
                    QMixLogCause.HTTP_STATUS,
                ),
            ),
            sink.records,
        )
        assertFalse(sink.records.toString().contains("do-not-log"))
    }

    @Test
    fun create_network_failure_logs_network_cause() {
        val sink = RecordingLogSink()
        val apiLogger = QMixLogger(sink, QMixLogLevel.WARN) { null }
            .component(QMixLogComponent.ROOM_API_CREATION)
        api = RoomApiClient(OkHttpClient(), server.url("/").toString(), apiLogger)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertThrows(RoomApiException::class.java) { api.createRoom() }

        assertEquals(QMixLogCause.NETWORK, sink.records.single().cause)
    }

    @Test
    fun status_errors_have_human_messages() {
        listOf(
            403 to UserMessage.HOST_ACCESS_DENIED,
            404 to UserMessage.ROOM_NOT_FOUND,
            503 to UserMessage.SERVER_UNAVAILABLE,
        ).forEach { (status, expected) ->
            server.enqueue(MockResponse().setResponseCode(status))
            val error = assertThrows(RoomApiException::class.java) { api.getRoom("ABCD") }
            assertEquals(expected, error.userMessage)
        }
    }

    @Test
    fun invalid_json_has_human_message() {
        server.enqueue(MockResponse().setBody("not json"))

        val error = assertThrows(RoomApiException::class.java) { api.getRoom("ABCD") }

        assertEquals(UserMessage.INVALID_RESPONSE, error.userMessage)
    }

    @Test
    fun getRoom_requires_current_to_be_an_explicit_null_or_object() {
        listOf(
            """{"code":"ABCD","queue":[]}""",
            """{"code":"ABCD","current":"playing","queue":[]}""",
        ).forEach { body ->
            server.enqueue(MockResponse().setBody(body))

            val error = assertThrows(RoomApiException::class.java) { api.getRoom("ABCD") }

            assertEquals(UserMessage.INVALID_RESPONSE, error.userMessage)
        }

        server.enqueue(MockResponse().setBody("""{"code":"ABCD","current":null,"queue":[]}"""))

        assertNull(api.getRoom("ABCD").current)
    }

    @Test
    fun valid_json_with_wrong_dto_types_has_human_message() {
        server.enqueue(
            MockResponse().setBody(
                """{"code":"ABCD","current":{"track_id":"now","pos_sec":"twelve","state":"playing","title":"Now","artist":"Artist"},"queue":[]}""",
            ),
        )

        val positionError = assertThrows(RoomApiException::class.java) { api.getRoom("ABCD") }

        assertEquals(UserMessage.INVALID_RESPONSE, positionError.userMessage)

        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":1234,"host_token":"host-secret","url":"/r/ABCD"}"""),
        )

        val stringError = assertThrows(RoomApiException::class.java) { api.createRoom() }

        assertEquals(UserMessage.INVALID_RESPONSE, stringError.userMessage)
    }

    @Test
    fun timeout_has_human_message() {
        server.enqueue(MockResponse().setBodyDelay(2, TimeUnit.SECONDS).setBody("{}"))
        api = RoomApiClient(
            OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS).build(),
            server.url("/").toString(),
        )

        val error = assertThrows(RoomApiException::class.java) { api.getRoom("ABCD") }

        assertEquals(UserMessage.SERVER_TIMEOUT, error.userMessage)
    }

    @Test
    fun host_token_is_not_forwarded_to_a_redirected_origin() {
        val otherOrigin = MockWebServer()
        otherOrigin.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .addHeader("Location", otherOrigin.url("/capture")),
            )
            otherOrigin.enqueue(
                MockResponse().setBody(
                    """{"current":{"track_id":"x","pos_sec":0,"state":"playing","title":"X","artist":""}}""",
                ),
            )

            assertThrows(RoomApiException::class.java) { api.skip("ABCD", "host-secret") }

            assertEquals(0, otherOrigin.requestCount)
        } finally {
            otherOrigin.shutdown()
        }
    }
}
