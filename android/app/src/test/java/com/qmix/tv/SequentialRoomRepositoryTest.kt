package com.qmix.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SequentialRoomRepositoryTest {
    /** qmix#179: each cold collection starts with one owned GET. */
    @Test
    fun initial_refresh_emits_loading_then_the_first_room_snapshot() = runTest {
        val fixture = Fixture(this)

        fixture.start()
        assertEquals(listOf("ABCD"), fixture.fetcher.requests.map(Request::roomCode))
        assertEquals(listOf(active()), fixture.observed)

        val room = roomState("ABCD", "one")
        fixture.fetcher.complete(0, RoomFetchResult.Success(room))
        runCurrent()

        assertEquals(active(room, Freshness.FRESH), fixture.observed.last())
    }

    @Test
    fun initial_sse_open_marks_connected_without_starting_a_duplicate_refresh() = runTest {
        val fixture = Fixture(this)
        fixture.start()

        fixture.events.latest.emit(RoomEventStreamEvent.Opened)
        runCurrent()

        assertEquals(active(connection = LiveConnection.CONNECTED), fixture.observed.last())
        assertEquals(1, fixture.fetcher.requests.size)

        val room = roomState("ABCD", "connected")
        fixture.fetcher.complete(0, RoomFetchResult.Success(room))
        runCurrent()

        assertEquals(active(room, Freshness.FRESH, LiveConnection.CONNECTED), fixture.observed.last())
    }

    @Test
    fun connected_delivery_applies_collector_backpressure_before_the_get_result() = runTest {
        val fixture = Fixture(this)
        val collectorEntered = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val observed = mutableListOf<RoomSyncState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.repository.observe("ABCD").collect { state ->
                observed += state
                val active = state as? RoomSyncState.Active
                if (active?.connection == LiveConnection.CONNECTED && active.freshness == Freshness.LOADING) {
                    collectorEntered.complete(Unit)
                    withContext(NonCancellable) { releaseCollector.await() }
                }
            }
        }
        runCurrent()

        fixture.events.latest.emit(RoomEventStreamEvent.Opened)
        runCurrent()
        assertTrue(collectorEntered.isCompleted)

        val room = roomState("ABCD", "fresh-after-open")
        fixture.fetcher.complete(0, RoomFetchResult.Success(room))
        runCurrent()
        assertEquals(Freshness.LOADING, (observed.last() as RoomSyncState.Active).freshness)

        releaseCollector.complete(Unit)
        runCurrent()
        assertEquals(active(room, Freshness.FRESH, LiveConnection.CONNECTED), observed.last())
    }

    /** qmix#179: periodic recovery is virtual-time owned by the collection. */
    @Test
    fun periodic_refresh_runs_every_fifteen_seconds() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.fetcher.complete(0, RoomFetchResult.Success(roomState("ABCD", "initial")))
        runCurrent()

        advanceTimeBy(14_999L)
        runCurrent()
        assertEquals(1, fixture.fetcher.requests.size)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, fixture.fetcher.requests.size)
        fixture.fetcher.complete(1, RoomFetchResult.Success(roomState("ABCD", "periodic")))
        runCurrent()

        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(3, fixture.fetcher.requests.size)
    }

    @Test
    fun periodic_refresh_during_an_active_get_coalesces_into_one_follow_up() = runTest {
        val fixture = Fixture(this)
        fixture.start()

        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(1, fixture.fetcher.requests.size)

        fixture.fetcher.complete(0, RoomFetchResult.Success(roomState("ABCD", "first")))
        runCurrent()
        assertEquals(2, fixture.fetcher.requests.size)

        fixture.fetcher.complete(1, RoomFetchResult.Success(roomState("ABCD", "second")))
        runCurrent()
        assertEquals(2, fixture.fetcher.requests.size)
    }

    @Test
    fun invalidations_during_get_coalesce_into_exactly_one_follow_up() = runTest {
        val fixture = Fixture(this)
        fixture.start()

        fixture.events.latest.emit(RoomEventStreamEvent.Event("queue_updated"))
        fixture.events.latest.emit(RoomEventStreamEvent.Event("track_changed"))
        fixture.events.latest.emit(RoomEventStreamEvent.Event("player_state"))
        fixture.events.latest.emit(RoomEventStreamEvent.Event("heartbeat"))
        runCurrent()
        assertEquals(1, fixture.fetcher.requests.size)

        fixture.fetcher.complete(0, RoomFetchResult.Success(roomState("ABCD", "first")))
        runCurrent()
        assertEquals(2, fixture.fetcher.requests.size)

        fixture.fetcher.complete(1, RoomFetchResult.Success(roomState("ABCD", "second")))
        runCurrent()
        assertEquals(2, fixture.fetcher.requests.size)
    }

    @Test
    fun completed_get_remains_owned_until_its_completion_command_is_consumed() = runTest {
        val fetcher = TestFetcher()
        val events = TestEventStreams()
        val repository = SequentialRoomRepository(
            fetchRoom = fetcher::fetch,
            eventStreams = events,
            backoff = ReconnectBackoff(randomFraction = { 0.0 }),
        )
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            repository.observe("ABCD").collect { }
        }
        runCurrent()
        assertEquals(1, fetcher.requests.size)

        events.latest.emit(RoomEventStreamEvent.Event("queue_updated"))
        events.latest.emit(RoomEventStreamEvent.Event("track_changed"))
        fetcher.complete(0, RoomFetchResult.Success(roomState("ABCD", "first")))
        runCurrent()

        assertEquals(2, fetcher.requests.size)
    }

    @Test
    fun every_completed_refresh_emits_even_when_the_state_is_equal() = runTest {
        val fixture = Fixture(this)
        val room = roomState("ABCD", "same")
        fixture.start()
        fixture.fetcher.complete(0, RoomFetchResult.Success(room))
        runCurrent()

        fixture.events.latest.emit(RoomEventStreamEvent.Event("queue_snapshot"))
        runCurrent()
        fixture.fetcher.complete(1, RoomFetchResult.Success(room))
        runCurrent()

        assertEquals(2, fixture.observed.count { it == active(room, Freshness.FRESH) })
    }

    /** qmix#179: reconnect delay and refresh are deterministic under runTest. */
    @Test
    fun reconnect_during_get_waits_for_backoff_then_queues_one_refresh() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        val firstConnection = fixture.events.latest

        firstConnection.emit(RoomEventStreamEvent.Failure(503))
        runCurrent()
        assertEquals(LiveConnection.RECONNECTING, (fixture.observed.last() as RoomSyncState.Active).connection)
        assertEquals(1, fixture.events.connections.size)

        advanceTimeBy(499L)
        runCurrent()
        assertEquals(1, fixture.events.connections.size)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, fixture.events.connections.size)

        fixture.events.latest.emit(RoomEventStreamEvent.Opened)
        runCurrent()
        assertEquals(1, fixture.fetcher.requests.size)

        fixture.fetcher.complete(0, RoomFetchResult.Success(roomState("ABCD", "before-reconnect")))
        runCurrent()
        assertEquals(2, fixture.fetcher.requests.size)
        fixture.fetcher.complete(1, RoomFetchResult.Success(roomState("ABCD", "after-reconnect")))
        runCurrent()
        assertEquals(2, fixture.fetcher.requests.size)
    }

    @Test
    fun reopened_sse_after_a_completed_get_starts_an_immediate_refresh() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.fetcher.complete(0, RoomFetchResult.Success(roomState("ABCD", "initial")))
        runCurrent()

        fixture.events.latest.emit(RoomEventStreamEvent.Failure(503))
        runCurrent()
        advanceTimeBy(500L)
        runCurrent()
        fixture.events.latest.emit(RoomEventStreamEvent.Opened)
        runCurrent()

        assertEquals(2, fixture.fetcher.requests.size)
        assertEquals(LiveConnection.CONNECTED, (fixture.observed.last() as RoomSyncState.Active).connection)
    }

    @Test
    fun clean_sse_close_is_treated_as_a_network_reconnect() = runTest {
        val fixture = Fixture(this)
        fixture.start()

        fixture.events.latest.emit(RoomEventStreamEvent.Closed)
        runCurrent()

        assertEquals(LiveConnection.RECONNECTING, (fixture.observed.last() as RoomSyncState.Active).connection)
        assertEquals(1, fixture.events.connections.size)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(2, fixture.events.connections.size)
    }

    @Test
    fun fetch_exception_marks_the_room_stale_and_later_refresh_can_recover() = runTest {
        var attempts = 0
        val events = TestEventStreams()
        val room = roomState("ABCD", "recovered")
        val repository = SequentialRoomRepository(
            fetchRoom = {
                attempts++
                if (attempts == 1) throw IllegalStateException("offline")
                RoomFetchResult.Success(room)
            },
            eventStreams = events,
            backoff = ReconnectBackoff(randomFraction = { 0.0 }),
        )
        val observed = mutableListOf<RoomSyncState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observe("ABCD").toList(observed)
        }
        runCurrent()

        assertEquals(active(freshness = Freshness.STALE), observed.last())
        events.latest.emit(RoomEventStreamEvent.Event("queue_updated"))
        runCurrent()

        assertEquals(2, attempts)
        assertEquals(active(room, Freshness.FRESH), observed.last())
    }

    @Test
    fun event_stream_exception_logs_network_failure_and_reconnects() = runTest {
        val sink = RecordingLogSink()
        val logger = QMixLogger(sink, QMixLogLevel.DEBUG) { null }
            .component(QMixLogComponent.ROOM_SYNC_SSE_RECONNECT)
        var connections = 0
        val repository = SequentialRoomRepository(
            fetchRoom = { RoomFetchResult.Success(roomState(it, "initial")) },
            eventStreams = RoomEventStreamFactory {
                flow {
                    connections++
                    if (connections == 1) throw IllegalStateException("socket failure")
                    awaitCancellation()
                }
            },
            backoff = ReconnectBackoff(randomFraction = { 0.0 }),
            logger = logger,
        )
        val observed = mutableListOf<RoomSyncState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observe("ABCD").toList(observed)
        }
        runCurrent()

        assertEquals(LiveConnection.RECONNECTING, (observed.last() as RoomSyncState.Active).connection)
        assertEquals(QMixLogCause.NETWORK, sink.records.first().cause)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(2, connections)
    }

    @Test
    fun reconnect_backoff_is_capped_and_uses_virtual_time() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        val expectedDelays = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L)

        expectedDelays.forEachIndexed { index, delay ->
            fixture.events.latest.emit(RoomEventStreamEvent.Failure(503))
            runCurrent()
            advanceTimeBy(delay - 1L)
            runCurrent()
            assertEquals(index + 1, fixture.events.connections.size)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(index + 2, fixture.events.connections.size)
        }
    }

    @Test
    fun network_failure_preserves_the_last_room_and_marks_it_stale() = runTest {
        val fixture = Fixture(this)
        val room = roomState("ABCD", "last-known")
        fixture.start()
        fixture.fetcher.complete(0, RoomFetchResult.Success(room))
        runCurrent()

        fixture.events.latest.emit(RoomEventStreamEvent.Event("queue_updated"))
        runCurrent()
        fixture.fetcher.complete(1, RoomFetchResult.Failure)
        runCurrent()

        assertEquals(active(room, Freshness.STALE), fixture.observed.last())
    }

    @Test
    fun stale_delivery_applies_collector_backpressure_before_processing_the_next_refresh() = runTest {
        val fixture = Fixture(this)
        val collectorEntered = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val observed = mutableListOf<RoomSyncState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.repository.observe("ABCD").collect { state ->
                observed += state
                if ((state as? RoomSyncState.Active)?.freshness == Freshness.STALE) {
                    collectorEntered.complete(Unit)
                    withContext(NonCancellable) { releaseCollector.await() }
                }
            }
        }
        runCurrent()

        fixture.fetcher.complete(0, RoomFetchResult.Failure)
        runCurrent()
        assertTrue(collectorEntered.isCompleted)

        fixture.events.latest.emit(RoomEventStreamEvent.Event("queue_updated"))
        runCurrent()
        assertEquals(1, fixture.fetcher.requests.size)

        releaseCollector.complete(Unit)
        runCurrent()
        assertEquals(2, fixture.fetcher.requests.size)
        assertEquals(Freshness.STALE, (observed.last() as RoomSyncState.Active).freshness)
    }

    @Test
    fun get_404_is_terminal_and_cancels_sse_and_periodic_work() = runTest {
        val fixture = Fixture(this)
        fixture.start()

        fixture.fetcher.complete(0, RoomFetchResult.Missing)
        runCurrent()

        assertEquals(RoomSyncState.Missing("ABCD"), fixture.observed.last())
        assertTrue(fixture.events.latest.cancelled)
        val requestsAtMissing = fixture.fetcher.requests.size
        advanceTimeBy(60_000L)
        fixture.events.latest.emit(RoomEventStreamEvent.Event("track_changed"))
        runCurrent()
        assertEquals(requestsAtMissing, fixture.fetcher.requests.size)
    }

    @Test
    fun get_missing_waits_for_collector_backpressure_before_terminal_cleanup() = runTest {
        val fixture = Fixture(this)
        val collectorEntered = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.repository.observe("ABCD").collect { state ->
                if (state is RoomSyncState.Missing) {
                    collectorEntered.complete(Unit)
                    withContext(NonCancellable) { releaseCollector.await() }
                }
            }
        }
        runCurrent()

        fixture.fetcher.complete(0, RoomFetchResult.Missing)
        runCurrent()
        assertTrue(collectorEntered.isCompleted)
        assertFalse(job.isCompleted)
        assertFalse(fixture.events.latest.cancelled)

        releaseCollector.complete(Unit)
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(fixture.events.latest.cancelled)
    }

    @Test
    fun missing_result_discards_a_refresh_that_was_queued_during_the_get() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.events.latest.emit(RoomEventStreamEvent.Event("queue_updated"))
        runCurrent()

        fixture.fetcher.complete(0, RoomFetchResult.Missing)
        runCurrent()

        assertEquals(RoomSyncState.Missing("ABCD"), fixture.observed.last())
        assertEquals(1, fixture.fetcher.requests.size)
        assertTrue(fixture.events.latest.cancelled)
    }

    @Test
    fun sse_404_is_terminal_and_cancels_an_active_get() = runTest {
        val fixture = Fixture(this)
        fixture.start()

        fixture.events.latest.emit(RoomEventStreamEvent.Failure(404))
        runCurrent()

        assertEquals(RoomSyncState.Missing("ABCD"), fixture.observed.last())
        assertTrue(fixture.fetcher.requests.single().cancelled)
        assertTrue(fixture.events.latest.cancelled)
    }

    @Test
    fun sse_missing_waits_for_collector_backpressure_before_cancelling_the_get() = runTest {
        val fixture = Fixture(this)
        val collectorEntered = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.repository.observe("ABCD").collect { state ->
                if (state is RoomSyncState.Missing) {
                    collectorEntered.complete(Unit)
                    withContext(NonCancellable) { releaseCollector.await() }
                }
            }
        }
        runCurrent()

        fixture.events.latest.emit(RoomEventStreamEvent.Failure(404))
        runCurrent()
        assertTrue(collectorEntered.isCompleted)
        assertFalse(job.isCompleted)
        assertFalse(fixture.fetcher.requests.single().cancelled)

        releaseCollector.complete(Unit)
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(fixture.fetcher.requests.single().cancelled)
    }

    @Test
    fun cancellation_waits_for_active_collector_code_and_rejects_late_callbacks() = runTest {
        val fixture = Fixture(this)
        val collectorEntered = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val observed = mutableListOf<RoomSyncState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.repository.observe("ABCD")
                .onEach { state ->
                    observed += state
                    if ((state as? RoomSyncState.Active)?.freshness == Freshness.FRESH) {
                        collectorEntered.complete(Unit)
                        withContext(NonCancellable) { releaseCollector.await() }
                    }
                }
                .collect { }
        }
        runCurrent()
        fixture.fetcher.complete(0, RoomFetchResult.Success(roomState("ABCD", "active")))
        runCurrent()
        assertTrue(collectorEntered.isCompleted)

        var joined = false
        job.cancel()
        backgroundScope.launch { job.join(); joined = true }
        runCurrent()
        assertFalse(joined)

        releaseCollector.complete(Unit)
        runCurrent()
        assertTrue(joined)
        val observedAfterJoin = observed.size
        fixture.events.latest.emit(RoomEventStreamEvent.Event("queue_updated"))
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(observedAfterJoin, observed.size)
        assertEquals(1, fixture.fetcher.requests.size)
    }

    @Test
    fun concurrent_cancellers_both_wait_for_owned_cleanup() = runTest {
        val cleanupGate = CompletableDeferred<Unit>()
        val fixture = Fixture(this, eventCleanupGate = cleanupGate)
        val job = fixture.start()
        var firstJoined = false
        var secondJoined = false

        backgroundScope.launch { job.cancelAndJoin(); firstJoined = true }
        backgroundScope.launch { job.cancelAndJoin(); secondJoined = true }
        runCurrent()
        assertFalse(firstJoined)
        assertFalse(secondJoined)

        cleanupGate.complete(Unit)
        runCurrent()
        assertTrue(firstJoined)
        assertTrue(secondJoined)
    }

    @Test
    fun collector_can_cancel_reentrantly_from_an_emission() = runTest {
        val fixture = Fixture(this)
        val observed = mutableListOf<RoomSyncState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.repository.observe("ABCD").take(2).toList(observed)
        }
        runCurrent()

        fixture.fetcher.complete(0, RoomFetchResult.Success(roomState("ABCD", "fresh")))
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals(2, observed.size)
        assertTrue(fixture.events.latest.cancelled)
    }

    @Test
    fun cancelling_collection_cancels_both_the_active_get_and_event_stream() = runTest {
        val fixture = Fixture(this)
        val job = fixture.start()

        job.cancelAndJoin()

        assertTrue(fixture.fetcher.requests.single().cancelled)
        assertTrue(fixture.events.latest.cancelled)
    }

    @Test
    fun collector_failure_propagates_after_cancelling_all_owned_work() = runTest {
        val fixture = Fixture(this)
        val owner = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val collection = owner.async {
            fixture.repository.observe("ABCD").collect { state ->
                if ((state as? RoomSyncState.Active)?.connection == LiveConnection.CONNECTED) {
                    throw IllegalStateException("collector failed")
                }
            }
        }
        runCurrent()

        fixture.events.latest.emit(RoomEventStreamEvent.Opened)
        runCurrent()

        val failure = try {
            collection.await()
            null
        } catch (caught: Throwable) {
            caught
        }
        owner.cancel()
        assertEquals("collector failed", failure?.message)
        assertTrue(fixture.fetcher.requests.single().cancelled)
        assertTrue(fixture.events.latest.cancelled)
    }

    @Test
    fun failed_sse_connection_logs_sanitized_boundary_and_reconnect_schedule() = runTest {
        val sink = RecordingLogSink()
        val logger = QMixLogger(sink, QMixLogLevel.DEBUG) { null }
            .component(QMixLogComponent.ROOM_SYNC_SSE_RECONNECT)
        val fixture = Fixture(this, logger = logger, roomCode = "secret-room-code")
        fixture.start()

        fixture.events.latest.emit(RoomEventStreamEvent.Failure(503))
        runCurrent()

        assertEquals(
            listOf(
                QMixLogRecord(
                    QMixLogLevel.WARN,
                    QMixLogComponent.ROOM_SYNC_SSE_RECONNECT,
                    QMixLogOperation.SSE_CONNECTION,
                    QMixLogCause.HTTP_STATUS,
                ),
                QMixLogRecord(
                    QMixLogLevel.INFO,
                    QMixLogComponent.ROOM_SYNC_SSE_RECONNECT,
                    QMixLogOperation.RECONNECT,
                ),
            ),
            sink.records,
        )
        assertFalse(sink.records.toString().contains("secret-room-code"))
    }

    @Test
    fun repeated_short_lived_sse_connections_emit_one_default_warning() = runTest {
        val sink = RecordingLogSink()
        val logger = QMixLogger(sink, QMixLogLevel.WARN) { null }
            .component(QMixLogComponent.ROOM_SYNC_SSE_RECONNECT)
        val fixture = Fixture(this, logger = logger)
        fixture.start()

        repeat(3) {
            fixture.events.latest.emit(RoomEventStreamEvent.Failure(503))
            runCurrent()
            if (it < 2) {
                advanceTimeBy(500L shl it)
                runCurrent()
            }
        }

        assertEquals(listOf(QMixLogOperation.SSE_CONNECTION), sink.records.map(QMixLogRecord::operation))
    }

    private class Fixture(
        private val scope: TestScope,
        eventCleanupGate: CompletableDeferred<Unit>? = null,
        logger: QMixComponentLogger = QMixComponentLogger.noOp(QMixLogComponent.ROOM_SYNC_SSE_RECONNECT),
        private val roomCode: String = "ABCD",
    ) {
        val fetcher = TestFetcher()
        val events = TestEventStreams(eventCleanupGate)
        val repository = SequentialRoomRepository(
            fetchRoom = fetcher::fetch,
            eventStreams = events,
            backoff = ReconnectBackoff(randomFraction = { 0.0 }),
            logger = logger,
        )
        val observed = mutableListOf<RoomSyncState>()

        fun start() = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            repository.observe(roomCode).toList(observed)
        }.also { scope.runCurrent() }
    }

    private class TestFetcher {
        val requests = mutableListOf<Request>()

        suspend fun fetch(roomCode: String): RoomFetchResult {
            val request = Request(roomCode)
            requests += request
            return try {
                request.result.await()
            } finally {
                if (!request.result.isCompleted) request.cancelled = true
            }
        }

        fun complete(index: Int, result: RoomFetchResult) {
            requests[index].result.complete(result)
        }
    }

    private data class Request(
        val roomCode: String,
        val result: CompletableDeferred<RoomFetchResult> = CompletableDeferred(),
        var cancelled: Boolean = false,
    )

    private class TestEventStreams(
        private val cleanupGate: CompletableDeferred<Unit>? = null,
    ) : RoomEventStreamFactory {
        val connections = mutableListOf<Connection>()
        val latest: Connection
            get() = connections.last()

        override fun observe(roomCode: String): Flow<RoomEventStreamEvent> = flow {
            val connection = Connection(roomCode)
            connections += connection
            try {
                while (true) {
                    val event = connection.events.receive()
                    emit(event)
                    if (event is RoomEventStreamEvent.Failure || event == RoomEventStreamEvent.Closed) return@flow
                }
            } finally {
                connection.cancelled = true
                cleanupGate?.let { withContext(NonCancellable) { it.await() } }
            }
        }

        class Connection(val roomCode: String) {
            val events = Channel<RoomEventStreamEvent>(Channel.UNLIMITED)
            var cancelled = false

            fun emit(event: RoomEventStreamEvent) {
                events.trySend(event)
            }
        }
    }

    companion object {
        private fun roomState(code: String, trackId: String) = RoomState(
            code = code,
            current = CurrentTrack(trackId, 0, "playing", "Title", "Artist"),
            queue = emptyList(),
        )

        private fun active(
            room: RoomState? = null,
            freshness: Freshness = Freshness.LOADING,
            connection: LiveConnection = LiveConnection.CONNECTING,
            code: String = "ABCD",
        ) = RoomSyncState.Active(code, room, freshness, connection)
    }
}
