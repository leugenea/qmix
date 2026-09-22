package com.qmix.tv

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OkHttpRoomEventStreamTest {
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
    fun connects_to_room_events_ignores_comments_and_delivers_named_events() = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(": heartbeat\n\nevent: queue_updated\ndata: {\"ignored\":true}\n\n"),
        )

        val events = withTimeout(5_000L) {
            OkHttpRoomEventStreamFactory(OkHttpClient(), server.url("/").toString())
                .observe("AB CD")
                .take(2)
                .toList()
        }

        assertEquals(
            listOf(RoomEventStreamEvent.Opened, RoomEventStreamEvent.Event("queue_updated")),
            events,
        )
        val request = server.takeRequest()
        assertEquals("/rooms/AB%20CD/events", request.path)
        assertEquals("text/event-stream", request.headers["Accept"])
        assertNull(request.headers["X-Host-Token"])
    }

    @Test
    fun reports_http_status_for_failed_connections() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val event = withTimeout(5_000L) {
            OkHttpRoomEventStreamFactory(OkHttpClient(), server.url("/").toString())
                .observe("ABCD")
                .first()
        }

        assertEquals(RoomEventStreamEvent.Failure(404), event)
    }
}
