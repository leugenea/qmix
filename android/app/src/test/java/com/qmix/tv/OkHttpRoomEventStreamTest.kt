package com.qmix.tv

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun connects_to_room_events_ignores_comments_and_delivers_named_events() {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(": heartbeat\n\nevent: queue_updated\ndata: {\"ignored\":true}\n\n"),
        )
        val opened = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val types = mutableListOf<String?>()
        val stream = OkHttpRoomEventStreamFactory(OkHttpClient(), server.url("/").toString())
            .connect("AB CD", object : RoomEventListener {
                override fun onOpen() { opened.countDown() }
                override fun onEvent(type: String?) { types += type; changed.countDown() }
                override fun onClosed() = Unit
                override fun onFailure(statusCode: Int?) = Unit
            })

        assertTrue(opened.await(5, TimeUnit.SECONDS))
        assertTrue(changed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("queue_updated"), types)
        val request = server.takeRequest()
        assertEquals("/rooms/AB%20CD/events", request.path)
        assertEquals("text/event-stream", request.headers["Accept"])
        assertNull(request.headers["X-Host-Token"])
        stream.cancel()
    }

    @Test
    fun reports_http_status_for_failed_connections() {
        server.enqueue(MockResponse().setResponseCode(404))
        val failed = CountDownLatch(1)
        var status: Int? = null
        OkHttpRoomEventStreamFactory(OkHttpClient(), server.url("/").toString())
            .connect("ABCD", object : RoomEventListener {
                override fun onOpen() = Unit
                override fun onEvent(type: String?) = Unit
                override fun onClosed() = Unit
                override fun onFailure(statusCode: Int?) { status = statusCode; failed.countDown() }
            })

        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals(404, status)
    }
}
