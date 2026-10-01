package com.qmix.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.sse.EventSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    /** qmix#213: a half-open SSE response must fail rather than wait forever for bytes. */
    @Test
    fun stalls_after_event_and_reports_network_failure_within_read_timeout() = runBlocking {
        val event = "event: queue_updated\ndata: {}\n\n"
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(event)
                // Leave the response body incomplete while keeping the socket open.
                .setHeader("Content-Length", event.toByteArray().size + 1_024),
        )

        val events = withTimeout(5_000L) {
            OkHttpRoomEventStreamFactory(
                OkHttpClient(),
                server.url("/").toString(),
                readTimeoutMillis = 250L,
            ).observe("ABCD").take(3).toList()
        }

        assertEquals(
            listOf(
                RoomEventStreamEvent.Opened,
                RoomEventStreamEvent.Event("queue_updated"),
                RoomEventStreamEvent.Failure(null),
            ),
            events,
        )
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

    @Test
    fun injected_event_source_delivers_callbacks_and_is_cancelled_after_close() = runBlocking {
        val source = RecordingEventSource()
        lateinit var requested: Request
        val events = withTimeout(5_000L) {
            OkHttpRoomEventStreamFactory(
                OkHttpClient(),
                server.url("/").toString(),
                connect = { request, listener ->
                    requested = request
                    val response = Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .build()
                    listener.onOpen(source, response)
                    listener.onEvent(source, null, null, "ignored")
                    listener.onClosed(source)
                    source
                },
            ).observe("AB CD").toList()
        }

        assertEquals(
            listOf(
                RoomEventStreamEvent.Opened,
                RoomEventStreamEvent.Event(null),
                RoomEventStreamEvent.Closed,
            ),
            events,
        )
        assertEquals("/rooms/AB%20CD/events", requested.url.encodedPath)
        assertEquals("text/event-stream", requested.header("Accept"))
        assertEquals(true, source.cancelled)
    }

    @Test
    fun injected_event_source_reports_network_failure_without_http_status() = runBlocking {
        val source = RecordingEventSource()
        val event = withTimeout(5_000L) {
            OkHttpRoomEventStreamFactory(
                OkHttpClient(),
                server.url("/").toString(),
                connect = { _, listener ->
                    listener.onFailure(source, IllegalStateException("offline"), null)
                    source
                },
            ).observe("ABCD").first()
        }

        assertEquals(RoomEventStreamEvent.Failure(null), event)
        assertEquals(true, source.cancelled)
    }

    /** qmix#308: preserve the direct default64 prefix and natural source cleanup. */
    @Test
    fun direct_callback_handoff_preserves_first_sixty_four_and_source_cleanup() = runTest {
        assertCallbackHandoff(downstreamLargeBuffer = false)
    }

    /** qmix#308: caller buffering must not widen the owned callback handoff. */
    @Test
    fun downstream_large_buffer_cannot_enlarge_owned_callback_handoff() = runTest {
        assertCallbackHandoff(downstreamLargeBuffer = true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.assertCallbackHandoff(downstreamLargeBuffer: Boolean) {
        val rawDefaultBuffer = System.getProperty("kotlinx.coroutines.channels.defaultBuffer")
        // Pinned coroutines 1.9.0 uses 64 only when the JVM property is absent.
        // A malformed explicit value remains null, never silently falls back to 64.
        val effectiveDefaultBuffer = if (rawDefaultBuffer == null) 64L else rawDefaultBuffer.toLongOrNull()
        val effectiveSource = if (rawDefaultBuffer == null) "pinned 1.9.0 fallback" else "explicit JVM property"
        println(
            "qmix#308 defaultBuffer raw=[$rawDefaultBuffer], effective=$effectiveDefaultBuffer, " +
                "source=$effectiveSource, downstreamLargeBuffer=$downstreamLargeBuffer",
        )
        assertEquals("Nonqualifying baseline: effective JVM defaultBuffer must be 64", 64L, effectiveDefaultBuffer)

        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = RecordingEventSource()
        var requested: Request? = null
        var sourceToReturn: EventSource? = null
        val burstReturned = CompletableDeferred<Unit>()
        val sourceReturnTailReached = CompletableDeferred<Unit>()
        val collectorEntered = CompletableDeferred<Unit>()
        val collectorReturnTailReached = CompletableDeferred<Unit>()
        val naturalCollectReturned = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val events = mutableListOf<RoomEventStreamEvent>()
        val eventTypes = (1..65).map { "event-${it.toString().padStart(2, '0')}" }
        var collectionJob: Job? = null
        var primaryFailure: Throwable? = null
        // Cleanup is protected before even allocating the LAZY collection Job.
        try {
            val factory = OkHttpRoomEventStreamFactory(
                OkHttpClient(),
                server.url("/").toString(),
                connect = { request, listener ->
                    requested = request
                    val productionListener = listener
                    val response = Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .build()
                    // The first receive is pending; this single-dispatcher burst never yields.
                    productionListener.onOpen(source, response)
                    eventTypes.forEach { type ->
                        productionListener.onEvent(source, "ignored", type, "ignored")
                    }
                    productionListener.onClosed(source)
                    // These are callback/return-tail receipts, not admission acknowledgements.
                    burstReturned.complete(Unit)
                    sourceToReturn = source
                    sourceReturnTailReached.complete(Unit)
                    source
                },
            )
            val observed = factory.observe("AB CD")
            val publicFlow = if (downstreamLargeBuffer) observed.buffer(128) else observed
            val job = launch(dispatcher, start = CoroutineStart.LAZY) {
                publicFlow.collect { event ->
                    events.add(event)
                    if (event == RoomEventStreamEvent.Opened) {
                        collectorEntered.complete(Unit)
                        releaseCollector.await()
                        collectorReturnTailReached.complete(Unit)
                    }
                }
                naturalCollectReturned.complete(Unit)
            }
            collectionJob = job
            assertNull("observe and LAZY collection must remain cold", requested)
            withContext(CoroutineName("qmix#308 natural collection hang guard")) {
                withTimeout(5_000L) {
                    job.start()
                    runCurrent()
                    collectorEntered.await()
                    assertTrue("inline callback burst returned", burstReturned.isCompleted)
                    assertTrue("exact source return tail reached", sourceReturnTailReached.isCompleted)
                    assertEquals(listOf(RoomEventStreamEvent.Opened), events)
                    releaseCollector.complete(Unit)
                    runCurrent()
                    collectorReturnTailReached.await()
                    naturalCollectReturned.await()
                    job.join()
                }
            }
            assertTrue("whole collection joined naturally", job.isCompleted)
            assertFalse("successful collection was not cancelled", job.isCancelled)
            assertSame("the exact source is returned by injected connect", source, sourceToReturn)
            assertTrue("natural whole join includes fake cancel return", source.cancelled)
            assertEquals("/rooms/AB%20CD/events", requested?.url?.encodedPath)
            assertEquals("text/event-stream", requested?.header("Accept"))
            assertNull(requested?.header("X-Host-Token"))
            // Only this naturally completed public list proves admission, never callback receipts.
            assertEquals(
                "qmix#308 owned callback handoff must preserve exactly Opened and the first 64 events",
                listOf(RoomEventStreamEvent.Opened) +
                    (1..64).map { RoomEventStreamEvent.Event("event-${it.toString().padStart(2, '0')}") },
                events,
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseCollector.complete(Unit)
            collectionJob?.cancel()
            try {
                withContext(NonCancellable + CoroutineName("qmix#308 exact collection cleanup hang guard")) {
                    withTimeout(5_000L) { collectionJob?.join() }
                }
            } catch (cleanupFailure: Throwable) {
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

    private class RecordingEventSource : EventSource {
        var cancelled = false

        override fun request(): Request = Request.Builder().url("https://unused.example").build()

        override fun cancel() {
            cancelled = true
        }
    }
}
