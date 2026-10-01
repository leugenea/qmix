package com.qmix.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    /** qmix#308: receipts surround actual inline repository command sends, not fixture inputs. */
    @Test
    fun held_connected_collector_accepts_four_invalidations_and_preserves_one_coalesced_refresh() = runTest {
        Fixture(this).assertHeldInvalidationAdmissions(includeFifth = false)
    }

    @Test
    fun fifth_event_command_is_held_until_the_four_slot_consumer_releases() = runTest {
        Fixture(this).assertHeldInvalidationAdmissions(includeFifth = true)
    }

    /** qmix#308: an immediate event producer must not strand bootstrap before the receive loop. */
    @Test
    fun inline_event_startup_burst_cannot_prevent_initial_get_and_collection_cleanup() = runTest {
        val fixture = Fixture(this)
        val burstReached = CompletableDeferred<Unit>()
        val burstReturned = CompletableDeferred<Unit>()
        val releaseSource = CompletableDeferred<Unit>()
        fixture.events.script = { connection ->
            // No gate or queued test input precedes these five command-producing Opened events.
            repeat(4) { emit(RoomEventStreamEvent.Opened) }
            connection.emitWithReceipt(this, RoomEventStreamEvent.Opened, burstReached, burstReturned)
            releaseSource.await()
            awaitCancellation()
        }
        val room = roomState("ABCD", "inline-startup")
        var collection: Job? = null
        // Unconditional releases are registered before UNDISPATCHED collection can enter a callback.
        try {
            // UNDISPATCHED avoids an enclosing Unconfined event loop deferring the event launch.
            collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
                fixture.repository.observe("ABCD").toList(fixture.observed)
            }
            assertTrue("fifth startup emit reached before undispatched launch returns", burstReached.isCompleted)
            awaitRefreshPhase("inline fifth Opened reached", fixture::commandLastState) { burstReached.await() }
            awaitRefreshPhase("initial GET after immediate startup", fixture::commandLastState) { fixture.fetcher.initialEntered.await() }
            awaitRefreshPhase("inline fifth Opened returned", fixture::commandLastState) { burstReturned.await() }
            runCurrent()
            // GET entry is required, but its ordering relative to the Opened callbacks is not constrained.
            assertEquals("one bootstrap GET; ${fixture.commandLastState()}", 1, fixture.fetcher.requests.size)
            assertEquals(
                listOf(active()) + List(5) { active(connection = LiveConnection.CONNECTED) },
                fixture.observed,
            )
            fixture.fetcher.complete(0, RoomFetchResult.Success(room))
            awaitRefreshPhase("startup refresh whole terminal", fixture::commandLastState) {
                fixture.fetcher.requests.single().producer.join()
            }
            runCurrent()
            assertEquals(
                listOf(active()) + List(5) { active(connection = LiveConnection.CONNECTED) } +
                    active(room, Freshness.FRESH, LiveConnection.CONNECTED), fixture.observed,
            )
            assertEquals(listOf("ABCD"), fixture.fetcher.requests.map(Request::roomCode))
            assertEquals("no startup successor; ${fixture.commandLastState()}", 1, fixture.fetcher.requests.size)
            assertEquals("no periodic deadline crossed", 0L, testScheduler.currentTime)
        } finally {
            collection?.cancel()
            releaseSource.complete(Unit)
            collection?.let { fixture.joinCommandCollection(it) }
        }
    }

    /** qmix#308 S1: the approved bootstrap GET is acquired even before inline terminal SSE. */
    @Test
    fun inline_sse_404_retires_the_owner_approved_initial_get() = runTest {
        val fixture = Fixture(this)
        val missingReached = CompletableDeferred<Unit>()
        fixture.events.script = { emit(RoomEventStreamEvent.Failure(404)) }
        val supervisor = SupervisorJob()
        val owner = CoroutineScope(supervisor + UnconfinedTestDispatcher(testScheduler))
        var collection: Job? = null
        // Cleanup is installed before inline callbacks; the active runTest driver owns observation.
        try {
            val terminal = owner.async(start = CoroutineStart.UNDISPATCHED) {
                fixture.repository.observe("ABCD").collect { state ->
                    fixture.observed += state
                    if (state is RoomSyncState.Missing) missingReached.complete(Unit)
                }
            }
            collection = terminal
            awaitRefreshPhase("inline SSE404 Missing published", fixture::commandLastState) { missingReached.await() }
            awaitRefreshPhase("inline SSE404 natural whole collection terminal", fixture::commandLastState) { terminal.join() }
            terminal.await()
            assertFalse("Missing completes successfully", terminal.isCancelled)
            assertEquals(listOf(active(), RoomSyncState.Missing("ABCD")), fixture.observed)
            fixture.assertInitialGetRetiredAfterStartup(terminal, "inline SSE404 Missing")
        } finally {
            owner.cancel()
            collection?.cancel()
            withContext(NonCancellable) {
                try {
                    collection?.let { fixture.joinCommandCollection(it) }
                } finally {
                    awaitRefreshPhase("inline SSE404 supervisor whole terminal", fixture::commandLastState) { supervisor.join() }
                }
            }
        }
    }

    @Test
    fun inline_connected_collector_failure_retires_the_initial_get_and_preserves_the_primary_exception() = runTest {
        val fixture = Fixture(this)
        val connectedReached = CompletableDeferred<Unit>()
        val primary = RoomApiException(UserMessage.SERVER_UNAVAILABLE)
        fixture.events.script = {
            emit(RoomEventStreamEvent.Opened)
            awaitCancellation()
        }
        val supervisor = SupervisorJob()
        val owner = CoroutineScope(supervisor + UnconfinedTestDispatcher(testScheduler))
        var collection: Job? = null
        try {
            val terminal = owner.async(start = CoroutineStart.UNDISPATCHED) {
                fixture.repository.observe("ABCD").collect { state ->
                    fixture.observed += state
                    if (state == active(connection = LiveConnection.CONNECTED)) {
                        connectedReached.complete(Unit)
                        throw primary
                    }
                }
            }
            collection = terminal
            awaitRefreshPhase("inline CONNECTED throwing collector reached", fixture::commandLastState) { connectedReached.await() }
            awaitRefreshPhase("inline CONNECTED failure natural whole collection terminal", fixture::commandLastState) { terminal.join() }
            val failure = try {
                terminal.await()
                null
            } catch (caught: Throwable) {
                caught
            }
            assertSame("collector primary survives owned teardown", primary, failure)
            assertEquals(listOf(active(), active(connection = LiveConnection.CONNECTED)), fixture.observed)
            fixture.assertInitialGetRetiredAfterStartup(terminal, "inline CONNECTED collector failure")
        } finally {
            owner.cancel()
            collection?.cancel()
            withContext(NonCancellable) {
                try {
                    collection?.let { fixture.joinCommandCollection(it) }
                } finally {
                    awaitRefreshPhase("inline CONNECTED failure supervisor whole terminal", fixture::commandLastState) { supervisor.join() }
                }
            }
        }
    }

    @Test
    fun inline_connected_collector_cancellation_retires_the_initial_get() = runTest {
        val fixture = Fixture(this)
        val connectedReached = CompletableDeferred<Unit>()
        val cancellation = CancellationException("inline CONNECTED collector cancelled")
        fixture.events.script = {
            emit(RoomEventStreamEvent.Opened)
            awaitCancellation()
        }
        val supervisor = SupervisorJob()
        val owner = CoroutineScope(supervisor + UnconfinedTestDispatcher(testScheduler))
        var collection: Job? = null
        try {
            val terminal = owner.async(start = CoroutineStart.UNDISPATCHED) {
                fixture.repository.observe("ABCD").collect { state ->
                    fixture.observed += state
                    if (state == active(connection = LiveConnection.CONNECTED)) {
                        connectedReached.complete(Unit)
                        currentCoroutineContext().cancel(cancellation)
                        awaitCancellation()
                    }
                }
            }
            collection = terminal
            awaitRefreshPhase("inline CONNECTED cancelling collector reached", fixture::commandLastState) { connectedReached.await() }
            awaitRefreshPhase("inline CONNECTED cancellation natural whole collection terminal", fixture::commandLastState) { terminal.join() }
            val failure = try {
                terminal.await()
                null
            } catch (caught: CancellationException) {
                caught
            }
            assertTrue("collector cancellation, not successful completion", failure is CancellationException)
            assertEquals("named collector cancellation survives teardown", cancellation.message, failure?.message)
            assertTrue("whole collection cancelled", terminal.isCancelled)
            assertEquals(listOf(active(), active(connection = LiveConnection.CONNECTED)), fixture.observed)
            fixture.assertInitialGetRetiredAfterStartup(terminal, "inline CONNECTED collector cancellation")
        } finally {
            owner.cancel()
            collection?.cancel()
            withContext(NonCancellable) {
                try {
                    collection?.let { fixture.joinCommandCollection(it) }
                } finally {
                    awaitRefreshPhase("inline CONNECTED cancellation supervisor whole terminal", fixture::commandLastState) { supervisor.join() }
                }
            }
        }
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

    /** qmix#308: a delivered result does not retire the exact refresh Job's subtree. */
    @Test
    fun refresh_completed_handoff_waits_for_the_whole_producer_before_one_coalesced_successor() = runTest {
        val fetcher = SubtreeHeldFetcher()
        val events = TestEventStreams()
        val observed = mutableListOf<RoomSyncState>()
        val first = roomState("ABCD", "before-cleanup")
        val current = roomState("ABCD", "after-cleanup")
        val repository = SequentialRoomRepository(
            fetchRoom = fetcher::fetch,
            eventStreams = events,
            backoff = ReconnectBackoff(randomFraction = { 0.0 }),
        )
        fun lastState() = "${fetcher.lastState()}, emissions=$observed, connections=${events.connections.size}"
        val collection = backgroundScope.launch(StandardTestDispatcher(testScheduler), start = CoroutineStart.LAZY) {
            repository.observe("ABCD").toList(observed)
        }
        // Register every release before starting any owned work or making an assertion.
        try {
            collection.start()
            runCurrent()
            assertEquals("initial GET; last state=${lastState()}", 1, fetcher.requests.size)
            assertEquals("event collection ready; last state=${lastState()}", 1, events.connections.size)
            assertEquals("cold loading; last state=${lastState()}", listOf(active()), observed)

            events.latest.emit(RoomEventStreamEvent.Opened)
            runCurrent()
            assertEquals(
                "opened while first GET is held; last state=${lastState()}",
                active(connection = LiveConnection.CONNECTED), observed.last(),
            )
            listOf("queue_snapshot", "queue_updated", "track_changed", "player_state").forEach {
                events.latest.emit(RoomEventStreamEvent.Event(it))
            }
            // This is a real periodic trigger, not a negative wall-clock correctness window.
            advanceTimeBy(15_000L)
            runCurrent()
            assertEquals("all invalidations coalesce during GET; last state=${lastState()}", 1, fetcher.requests.size)

            fetcher.complete(0, RoomFetchResult.Success(first))
            awaitRefreshPhase("original child entered cancellation cleanup", ::lastState) {
                fetcher.cleanupEntered.await()
            }
            // Drain all current-time work, including RefreshCompleted, without advancing any deadline.
            // A fix may join before or after emitting first; neither ordering is assumed here.
            runCurrent()
            val originalProducer = fetcher.producers.first()
            assertEquals(
                "held cleanup belongs to the exact original producer; last state=${lastState()}",
                listOf(fetcher.heldChild), originalProducer.children.toList(),
            )
            assertFalse("exact original Job still owns held child; last state=${lastState()}", originalProducer.isCompleted)
            assertEquals("no successor before full subtree terminal; last state=${lastState()}", 1, fetcher.requests.size)

            fetcher.releaseCleanup.complete(Unit)
            awaitRefreshPhase("exact original refresh full terminal", ::lastState) { originalProducer.join() }
            runCurrent()
            assertTrue("original refresh positively joined; last state=${lastState()}", originalProducer.isCompleted)
            assertEquals("exactly one coalesced successor; last state=${lastState()}", 2, fetcher.requests.size)
            assertEquals("successor saw full predecessor terminal; last state=${lastState()}", listOf(true), fetcher.predecessorTerminal)
            assertEquals("same room for both GETs; last state=${lastState()}", listOf("ABCD", "ABCD"), fetcher.requests.map(Request::roomCode))

            fetcher.complete(1, RoomFetchResult.Success(current))
            awaitRefreshPhase("successor refresh full terminal", ::lastState) { fetcher.producers.last().join() }
            runCurrent()
            assertEquals("no extra successor after draining accepted signals; last state=${lastState()}", 2, fetcher.requests.size)
            assertEquals(
                "authoritative snapshots remain ordered and current; last state=${lastState()}",
                listOf(
                    active(), active(connection = LiveConnection.CONNECTED),
                    active(first, Freshness.FRESH, LiveConnection.CONNECTED),
                    active(current, Freshness.FRESH, LiveConnection.CONNECTED),
                ), observed,
            )
        } finally {
            fetcher.releaseCleanup.complete(Unit)
            collection.cancel()
            withContext(NonCancellable) {
                awaitRefreshPhase("collection cleanup after unconditional release", ::lastState) { collection.join() }
            }
        }
        assertTrue("event stream retired after collection join; last state=${lastState()}", events.latest.cancelled)
        assertTrue("every real refresh Job is terminal; last state=${lastState()}", fetcher.producers.all(Job::isCompleted))
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

    /** qmix#213: a transport timeout has no HTTP status and uses the usual backoff. */
    @Test
    fun network_failure_after_open_reconnects_after_backoff() = runTest {
        val fixture = Fixture(this)
        fixture.start()
        fixture.events.latest.emit(RoomEventStreamEvent.Opened)
        runCurrent()
        assertEquals(LiveConnection.CONNECTED, (fixture.observed.last() as RoomSyncState.Active).connection)

        fixture.events.latest.emit(RoomEventStreamEvent.Failure(null))
        runCurrent()
        assertEquals(LiveConnection.RECONNECTING, (fixture.observed.last() as RoomSyncState.Active).connection)
        advanceTimeBy(499L)
        runCurrent()
        assertEquals(1, fixture.events.connections.size)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, fixture.events.connections.size)
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

    /** qmix#308: child-free GET Missing crosses saturated Command4 before natural terminal cleanup. */
    @Test
    fun child_free_missing_refresh_send_waits_for_command4_and_completes_collection_naturally() = runTest {
        assertSaturatedChildFreeMissingRefresh()
    }

    private suspend fun TestScope.assertSaturatedChildFreeMissingRefresh() {
        val fixture = Fixture(this)
        val collectorEntered = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val collectorReturnTailReached = CompletableDeferred<Unit>()
        val releaseInvalidations = CompletableDeferred<Unit>()
        val scriptStopped = CompletableDeferred<Unit>()
        val missingEntered = CompletableDeferred<Unit>()
        val releaseMissing = CompletableDeferred<Unit>()
        val missingReturnTailReached = CompletableDeferred<Unit>()
        val naturalCollectReturned = CompletableDeferred<Unit>()
        var collectionJob: Job? = null
        var primaryFailure: Throwable? = null
        // Cleanup protects allocation as well as every subsequent assertion and wait.
        try {
            fixture.events.script = { connection ->
                emit(RoomEventStreamEvent.Opened)
                releaseInvalidations.await()
                // Existing UNLIMITED fixture mailbox is allocated but inert; these emits are inline.
                connection.emitFourInvalidations(this)
                scriptStopped.complete(Unit)
                awaitCancellation()
            }
            val job = launch(StandardTestDispatcher(testScheduler), start = CoroutineStart.LAZY) {
                fixture.repository.observe("ABCD").collect { state ->
                    fixture.observed += state
                    if (state == active(connection = LiveConnection.CONNECTED)) {
                        collectorEntered.complete(Unit)
                        releaseCollector.await()
                        collectorReturnTailReached.complete(Unit)
                    }
                    if (state == RoomSyncState.Missing("ABCD")) {
                        missingEntered.complete(Unit)
                        releaseMissing.await()
                        missingReturnTailReached.complete(Unit)
                    }
                }
                naturalCollectReturned.complete(Unit)
            }
            collectionJob = job
            job.start()
            awaitRefreshPhase("saturated Missing CONNECTED collector entered", fixture::commandLastState) { collectorEntered.await() }
            awaitRefreshPhase("saturated Missing initial GET entered", fixture::commandLastState) { fixture.fetcher.initialEntered.await() }
            runCurrent()
            assertEquals(listOf(active(), active(connection = LiveConnection.CONNECTED)), fixture.observed)
            val request = fixture.fetcher.requests.single()
            val connection = fixture.events.connections.single()
            val initialProducer = request.producer
            val eventProducer = connection.producer
            val owner = requireNotNull(initialProducer.parent)
            assertSame("original event and refresh are exact siblings", owner, eventProducer.parent)
            val periodic = owner.children.single { it !== eventProducer && it !== initialProducer }
            assertEquals(setOf(eventProducer, initialProducer, periodic), owner.children.toSet())
            assertTrue("original event role active", eventProducer.isActive)
            assertTrue("original refresh role active", initialProducer.isActive)
            assertTrue("sole third role active before first periodic deadline", periodic.isActive)
            assertFalse("initial GET result gated before admissions", request.result.isCompleted)
            assertFalse("source gated before four inline invalidations", releaseInvalidations.isCompleted)
            assertFalse("CONNECTED callback remains held", releaseCollector.isCompleted)
            assertEquals("bootstrap received; no periodic deadline crossed", 0L, testScheduler.currentTime)

            releaseInvalidations.complete(Unit)
            connection.awaitFourReturns(fixture::commandLastState)
            awaitRefreshPhase("four real inline invalidations stopped", fixture::commandLastState) { scriptStopped.await() }
            runCurrent()
            assertFalse("GET result cannot occupy one of the four admitted slots", request.result.isCompleted)
            assertFalse("CONNECTED consumer still held after all four actual send returns", collectorReturnTailReached.isCompleted)
            assertFalse("CONNECTED release remains closed", releaseCollector.isCompleted)
            assertEquals(listOf(active(), active(connection = LiveConnection.CONNECTED)), fixture.observed)
            assertEquals("no timer payload in the four-slot boundary", 0L, testScheduler.currentTime)

            request.result.complete(RoomFetchResult.Missing)
            awaitRefreshPhase("exact initial fetch cleanup entered", fixture::commandLastState) { request.cleanupEntered.await() }
            awaitRefreshPhase("exact initial fetch return tail reached", fixture::commandLastState) { request.cleanupReturned.await() }
            runCurrent()
            // Tail receipts alone are not admission or terminal. Source order plus ready-work drain,
            // full Command4, completed result and this active child-free Job locate its sole send.
            assertTrue("initial Missing result released", request.result.isCompleted)
            assertTrue("source release cannot hold refresh", releaseInvalidations.isCompleted)
            assertTrue("source parked after four actual send returns", scriptStopped.isCompleted)
            assertTrue("exact child-free refresh held at result send", initialProducer.isActive)
            assertFalse("fetch return tail is not producer terminal", initialProducer.isCompleted)
            assertTrue("no child cleanup can impersonate refresh send hold", initialProducer.children.none())
            assertFalse("successful fetch was not cancelled", request.cancelled)
            assertFalse("CONNECTED release still closed at held send", releaseCollector.isCompleted)
            assertFalse("CONNECTED callback has not returned", collectorReturnTailReached.isCompleted)
            assertFalse("Missing is not yet public", missingEntered.isCompleted)
            assertFalse("source cleanup has not entered", connection.cleanupEntered.isCompleted)
            assertEquals(listOf(active(), active(connection = LiveConnection.CONNECTED)), fixture.observed)
            assertEquals("held send uses current-time quiescence only", 0L, testScheduler.currentTime)

            releaseCollector.complete(Unit)
            awaitRefreshPhase("CONNECTED collector return tail reached", fixture::commandLastState) { collectorReturnTailReached.await() }
            awaitRefreshPhase("original child-free refresh joined after consumer release", fixture::commandLastState) { initialProducer.join() }
            awaitRefreshPhase("admitted authoritative Missing collector entered", fixture::commandLastState) { missingEntered.await() }
            runCurrent()
            assertTrue("original refresh completed before public Missing", initialProducer.isCompleted)
            assertFalse("original refresh completed successfully", initialProducer.isCancelled)
            assertEquals(
                listOf(active(), active(connection = LiveConnection.CONNECTED), RoomSyncState.Missing("ABCD")),
                fixture.observed,
            )
            assertFalse("Missing collector release remains closed", releaseMissing.isCompleted)
            assertFalse("Missing callback still held", missingReturnTailReached.isCompleted)
            assertTrue("event remains live under held Missing", eventProducer.isActive)
            assertTrue("periodic remains live under held Missing", periodic.isActive)
            assertFalse("source cleanup waits for Missing return", connection.cleanupEntered.isCompleted)
            assertFalse("source cleanup has not returned", connection.cleanupReturned.isCompleted)
            assertFalse("whole public collect has not returned", naturalCollectReturned.isCompleted)
            assertFalse("whole collection still owns held Missing", job.isCompleted)
            assertEquals("terminal backpressure has not advanced a deadline", 0L, testScheduler.currentTime)

            releaseMissing.complete(Unit)
            awaitRefreshPhase("Missing collector return tail reached", fixture::commandLastState) { missingReturnTailReached.await() }
            awaitRefreshPhase("exact original source cleanup entered", fixture::commandLastState) { connection.cleanupEntered.await() }
            awaitRefreshPhase("exact original source cleanup returned", fixture::commandLastState) { connection.cleanupReturned.await() }
            awaitRefreshPhase("whole public collection returned naturally", fixture::commandLastState) { naturalCollectReturned.await() }
            awaitRefreshPhase("exact whole collection joined naturally", fixture::commandLastState) { job.join() }
            awaitRefreshPhase("exact original event role joined", fixture::commandLastState) { eventProducer.join() }
            awaitRefreshPhase("exact original periodic role joined", fixture::commandLastState) { periodic.join() }
            awaitRefreshPhase("exact original refresh role joined", fixture::commandLastState) { initialProducer.join() }
            assertTrue("successful whole collection completed", job.isCompleted)
            assertFalse("natural completion was not forced cancellation", job.isCancelled)
            assertTrue("actual repository owner terminal", owner.isCompleted)
            assertFalse("actual repository owner completed successfully", owner.isCancelled)
            assertTrue("actual repository owner has no children", owner.children.none())
            assertTrue("original event role terminal", eventProducer.isCompleted)
            assertTrue("original periodic role terminal", periodic.isCompleted)
            assertTrue("original refresh role terminal", initialProducer.isCompleted)
            assertTrue("exact source cancelled only by terminal teardown", connection.cancelled)
            assertFalse("no successor after successful whole-owner completion", fixture.fetcher.successorEntered.isCompleted)
            assertEquals(listOf("ABCD"), fixture.fetcher.requests.map(Request::roomCode))
            assertEquals(listOf("ABCD"), fixture.events.connections.map { it.roomCode })
            assertEquals(
                listOf(active(), active(connection = LiveConnection.CONNECTED), RoomSyncState.Missing("ABCD")),
                fixture.observed,
            )
            assertEquals("entire natural terminal history stays at time zero", 0L, testScheduler.currentTime)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseCollector.complete(Unit)
            releaseMissing.complete(Unit)
            releaseInvalidations.complete(Unit)
            try {
                val job = collectionJob
                if (job != null) {
                    if (!job.isCompleted) job.cancel()
                    withContext(NonCancellable) {
                        awaitRefreshPhase("exact collection defensive cleanup joined", fixture::commandLastState) { job.join() }
                    }
                }
            } catch (cleanupFailure: Throwable) {
                val failure = primaryFailure
                if (failure == null) throw cleanupFailure
                if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
        }
    }

    /** qmix#308: finite SSE404 holds child-free Missing send while the original GET stays gated. */
    @Test
    fun child_free_sse_404_missing_send_waits_for_command4_and_retires_the_held_get_naturally() = runTest {
        assertSaturatedSse404Missing()
    }

    private suspend fun TestScope.assertSaturatedSse404Missing() {
        val fixture = Fixture(this)
        val collectorEntered = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val collectorReturnTailReached = CompletableDeferred<Unit>()
        val releaseInvalidations = CompletableDeferred<Unit>()
        val failureReturned = CompletableDeferred<Unit>()
        val missingEntered = CompletableDeferred<Unit>()
        val releaseMissing = CompletableDeferred<Unit>()
        val missingReturnTailReached = CompletableDeferred<Unit>()
        val naturalCollectReturned = CompletableDeferred<Unit>()
        var collectionJob: Job? = null
        var primaryFailure: Throwable? = null
        // Register releases and failure cleanup before allocating even the lazy collection.
        try {
            fixture.events.script = { connection ->
                emit(RoomEventStreamEvent.Opened)
                releaseInvalidations.await()
                // Bypass the inherited, inert UNLIMITED mailbox: four actual inline send returns.
                connection.emitFourInvalidations(this)
                emit(RoomEventStreamEvent.Failure(404))
                failureReturned.complete(Unit)
                // Normal return immediately enters the source's ordinary, ungated finally.
            }
            val job = launch(StandardTestDispatcher(testScheduler), start = CoroutineStart.LAZY) {
                fixture.repository.observe("ABCD").collect { state ->
                    fixture.observed += state
                    if (state == active(connection = LiveConnection.CONNECTED)) {
                        collectorEntered.complete(Unit)
                        releaseCollector.await()
                        collectorReturnTailReached.complete(Unit)
                    }
                    if (state == RoomSyncState.Missing("ABCD")) {
                        missingEntered.complete(Unit)
                        releaseMissing.await()
                        missingReturnTailReached.complete(Unit)
                    }
                }
                naturalCollectReturned.complete(Unit)
            }
            collectionJob = job
            job.start()
            awaitRefreshPhase("SSE404 CONNECTED hold entered", fixture::commandLastState) { collectorEntered.await() }
            awaitRefreshPhase("SSE404 held initial GET entered", fixture::commandLastState) { fixture.fetcher.initialEntered.await() }
            runCurrent()
            val request = fixture.fetcher.requests.single()
            val connection = fixture.events.connections.single()
            val eventProducer = connection.producer
            val initialProducer = request.producer
            // Save the actual shared parent and all three identities before any role detaches.
            val owner = requireNotNull(eventProducer.parent)
            assertSame("original GET and event share the repository owner", owner, initialProducer.parent)
            val periodic = owner.children.single { it !== eventProducer && it !== initialProducer }
            assertEquals(setOf(eventProducer, initialProducer, periodic), owner.children.toSet())
            assertTrue("original event role active before source release", eventProducer.isActive)
            assertTrue("original GET role positively entered and held", initialProducer.isActive)
            assertTrue("original periodic role live before its deadline", periodic.isActive)
            assertFalse("source release still closed", releaseInvalidations.isCompleted)
            assertFalse("initial GET has no result", request.result.isCompleted)
            assertFalse("CONNECTED release still closed", releaseCollector.isCompleted)
            assertEquals(listOf(active(), active(connection = LiveConnection.CONNECTED)), fixture.observed)
            assertEquals("bootstrap and Opened consumed before saturation", 0L, testScheduler.currentTime)

            releaseInvalidations.complete(Unit)
            connection.awaitFourReturns(fixture::commandLastState)
            awaitRefreshPhase("inline Failure404 returned, not Missing admission", fixture::commandLastState) { failureReturned.await() }
            awaitRefreshPhase("finite SSE404 source finally entered", fixture::commandLastState) { connection.cleanupEntered.await() }
            awaitRefreshPhase("finite SSE404 source return tail reached", fixture::commandLastState) { connection.cleanupReturned.await() }
            runCurrent()
            // Failure only assigns status404. With source gates/children excluded and Command4 full,
            // collectEvents' next operation at RoomRepository.kt:205 is the held Missing send.
            // Source-tail reach (including its unconditional cancelled flag) is not Job retirement.
            assertTrue("the sole source release is open", releaseInvalidations.isCompleted)
            assertTrue("exact event role holds the source-assisted terminal send", eventProducer.isActive)
            assertFalse("source return tail is not whole event completion", eventProducer.isCompleted)
            assertTrue("no child cleanup can impersonate event send hold", eventProducer.children.none())
            assertFalse("CONNECTED gate remains closed after four send returns", releaseCollector.isCompleted)
            assertFalse("CONNECTED callback has not returned", collectorReturnTailReached.isCompleted)
            assertFalse("Missing has not been published", missingEntered.isCompleted)
            assertTrue("GET still waits independently for its result", initialProducer.isActive)
            assertFalse("GET result never released to fill Command4", request.result.isCompleted)
            assertFalse("GET cancellation has not begun", request.cancelled)
            assertFalse("GET cleanup has not entered", request.cleanupEntered.isCompleted)
            assertFalse("GET cleanup has not returned", request.cleanupReturned.isCompleted)
            assertEquals(listOf(active(), active(connection = LiveConnection.CONNECTED)), fixture.observed)
            assertEquals("no timer command contributes to saturation", 0L, testScheduler.currentTime)

            releaseCollector.complete(Unit)
            awaitRefreshPhase("SSE404 CONNECTED callback return tail", fixture::commandLastState) { collectorReturnTailReached.await() }
            awaitRefreshPhase("authoritative SSE404 Missing callback entered", fixture::commandLastState) { missingEntered.await() }
            awaitRefreshPhase("exact finite event role joined successfully", fixture::commandLastState) { eventProducer.join() }
            runCurrent()
            // Missing entry and sender termination may occur in either order; both are now positive.
            assertTrue("original event whole terminal reached", eventProducer.isCompleted)
            assertFalse("finite source and event role completed without cancellation", eventProducer.isCancelled)
            assertFalse("Missing release still closed", releaseMissing.isCompleted)
            assertFalse("Missing callback has not returned", missingReturnTailReached.isCompleted)
            assertTrue("held Missing leaves the original GET live", initialProducer.isActive)
            assertTrue("held Missing leaves the original timer live", periodic.isActive)
            assertFalse("GET result remains incomplete under public Missing", request.result.isCompleted)
            assertFalse("held Missing has not cancelled GET", request.cancelled)
            assertFalse("held Missing has not entered GET cleanup", request.cleanupEntered.isCompleted)
            assertFalse("held Missing has not reached GET cleanup tail", request.cleanupReturned.isCompleted)
            assertFalse("whole collect has not returned", naturalCollectReturned.isCompleted)
            assertFalse("collection still owns the Missing hold", job.isCompleted)
            assertEquals(
                listOf(active(), active(connection = LiveConnection.CONNECTED), RoomSyncState.Missing("ABCD")),
                fixture.observed,
            )
            assertEquals("public terminal backpressure remains at time zero", 0L, testScheduler.currentTime)

            releaseMissing.complete(Unit)
            awaitRefreshPhase("SSE404 Missing callback return tail", fixture::commandLastState) { missingReturnTailReached.await() }
            awaitRefreshPhase("held GET cancellation cleanup entered", fixture::commandLastState) { request.cleanupEntered.await() }
            awaitRefreshPhase("held GET cancellation cleanup returned", fixture::commandLastState) { request.cleanupReturned.await() }
            awaitRefreshPhase("SSE404 public collect returned naturally", fixture::commandLastState) { naturalCollectReturned.await() }
            awaitRefreshPhase("exact SSE404 collection joined naturally", fixture::commandLastState) { job.join() }
            awaitRefreshPhase("original SSE404 event whole join", fixture::commandLastState) { eventProducer.join() }
            awaitRefreshPhase("original held GET whole join", fixture::commandLastState) { initialProducer.join() }
            awaitRefreshPhase("original periodic whole join", fixture::commandLastState) { periodic.join() }
            // Reuse unchanged common GET/source cleanup, incomplete-result and single-room checks
            // only after the positive natural whole join, never the result-fabricating cleanup helper.
            fixture.assertInitialGetRetiredAfterStartup(job, "saturated SSE404 natural retirement")
            assertFalse("whole collection succeeded without defensive cancellation", job.isCancelled)
            assertTrue("captured repository owner completed", owner.isCompleted)
            assertFalse("captured repository owner succeeded", owner.isCancelled)
            assertTrue("captured repository owner has no children", owner.children.none())
            assertFalse("exact event role remains successfully terminal", eventProducer.isCancelled)
            assertTrue("held initial GET was cancelled by Missing retirement", initialProducer.isCancelled)
            assertTrue("original periodic role completed", periodic.isCompleted)
            assertTrue("original periodic role was cancelled", periodic.isCancelled)
            assertFalse("no successor after successful whole-owner completion", fixture.fetcher.successorEntered.isCompleted)
            assertEquals(
                listOf(active(), active(connection = LiveConnection.CONNECTED), RoomSyncState.Missing("ABCD")),
                fixture.observed,
            )
            assertEquals("finite SSE404 history never advances virtual time", 0L, testScheduler.currentTime)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseCollector.complete(Unit)
            releaseMissing.complete(Unit)
            releaseInvalidations.complete(Unit)
            try {
                val job = collectionJob
                if (job != null) {
                    if (!job.isCompleted) job.cancel()
                    withContext(NonCancellable) {
                        awaitRefreshPhase("exact SSE404 collection defensive join", fixture::commandLastState) { job.join() }
                    }
                }
            } catch (cleanupFailure: Throwable) {
                val failure = primaryFailure
                if (failure == null) throw cleanupFailure
                if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
        }
    }

    private class SubtreeHeldFetcher {
        private val fetcher = TestFetcher()
        val requests get() = fetcher.requests
        val producers = mutableListOf<Job>()
        val predecessorTerminal = mutableListOf<Boolean>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        lateinit var heldChild: Job
            private set

        suspend fun fetch(roomCode: String): RoomFetchResult {
            val context = currentCoroutineContext()
            producers.lastOrNull()?.let { predecessorTerminal += it.isCompleted }
            producers += context.job
            // Attach to the actual repository refresh, not an independent fake owner or observer.
            val child = if (producers.size == 1) CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitRefreshPhase("refresh child cancellation", ::lastState, 60_000L) { awaitCancellation() }
                } finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        awaitRefreshPhase("held refresh child release", ::lastState) { releaseCleanup.await() }
                    }
                }
            }.also { heldChild = it } else null
            return try {
                awaitRefreshPhase("controlled GET result", ::lastState, 60_000L) { fetcher.fetch(roomCode) }
            } finally {
                child?.cancel()
            }
        }

        fun complete(index: Int, result: RoomFetchResult) = fetcher.complete(index, result)

        fun lastState() = "requests=${requests.size}, producerTerminal=${producers.map(Job::isCompleted)}, " +
            "predecessorTerminal=$predecessorTerminal, cleanupEntered=${cleanupEntered.isCompleted}, released=${releaseCleanup.isCompleted}"
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

        suspend fun assertHeldInvalidationAdmissions(includeFifth: Boolean) {
            val collectorEntered = CompletableDeferred<Unit>()
            val releaseCollector = CompletableDeferred<Unit>()
            val collectorReturned = CompletableDeferred<Unit>()
            val releaseInvalidations = CompletableDeferred<Unit>()
            val scriptStopped = CompletableDeferred<Unit>()
            val releaseSource = CompletableDeferred<Unit>()
            events.script = { connection ->
                emit(RoomEventStreamEvent.Opened)
                releaseInvalidations.await()
                connection.emitFourInvalidations(this)
                if (includeFifth) {
                    connection.emitWithReceipt(
                        this, RoomEventStreamEvent.Event("queue_updated"),
                        connection.fifthReached, connection.fifthReturned,
                    )
                }
                scriptStopped.complete(Unit)
                // This gate follows the last real emit; it cannot impersonate a held command send.
                releaseSource.await()
                awaitCancellation()
            }
            val collection = scope.backgroundScope.launch(StandardTestDispatcher(scope.testScheduler), start = CoroutineStart.LAZY) {
                repository.observe(roomCode).collect { state ->
                    observed += state
                    if (state == active(connection = LiveConnection.CONNECTED)) {
                        collectorEntered.complete(Unit)
                        withContext(NonCancellable) { releaseCollector.await() }
                        collectorReturned.complete(Unit)
                    }
                }
            }
            // All unconditional releases below are installed before this lazy collection starts.
            try {
                collection.start()
                awaitRefreshPhase("CONNECTED/LOADING collector hold entered", ::commandLastState) { collectorEntered.await() }
                awaitRefreshPhase("held initial GET entered", ::commandLastState) { fetcher.initialEntered.await() }
                scope.runCurrent()
                assertEquals(listOf(active(), active(connection = LiveConnection.CONNECTED)), observed)
                assertEquals("initial GET held before admissions; ${commandLastState()}", 1, fetcher.requests.size)
                assertFalse("no GET result enters the command queue", fetcher.requests.single().result.isCompleted)
                assertEquals("no timer/bootstrap/result payload in admission boundary", 0L, scope.testScheduler.currentTime)
                val connection = events.latest
                val initialProducer = fetcher.requests.single().producer
                val owner = requireNotNull(connection.producer.parent)
                assertEquals("event and refresh share actual repository owner", owner, initialProducer.parent)
                val periodic = owner.children.single { it !== connection.producer && it !== initialProducer }
                assertEquals(setOf(connection.producer, initialProducer, periodic), owner.children.toSet())

                releaseInvalidations.complete(Unit)
                connection.awaitFourReturns(::commandLastState)
                if (includeFifth) {
                    awaitRefreshPhase("fifth actual emit reached", ::commandLastState) { connection.fifthReached.await() }
                    // No time advance or correctness timeout: all ready work in this same context is drained.
                    scope.runCurrent()
                    assertFalse("fifth actual emit must remain held by Command4; ${commandLastState()}", connection.fifthReturned.isCompleted)
                } else {
                    awaitRefreshPhase("four-event source stopped", ::commandLastState) { scriptStopped.await() }
                }
                assertEquals("still before the first periodic deadline", 0L, scope.testScheduler.currentTime)
                releaseCollector.complete(Unit)
                awaitRefreshPhase("held collector returned", ::commandLastState) { collectorReturned.await() }
                if (includeFifth) {
                    awaitRefreshPhase("fifth actual emit returned after consumer release", ::commandLastState) { connection.fifthReturned.await() }
                }
                awaitRefreshPhase("finite script stopped before any sixth emit", ::commandLastState) { scriptStopped.await() }
                scope.runCurrent()
                assertEquals("all accepted invalidations coalesce behind held GET", 1, fetcher.requests.size)
                assertCoalescedSnapshots()
                assertTrue("exact periodic role still owned until teardown", periodic.isActive)
            } finally {
                collection.cancel()
                releaseCollector.complete(Unit)
                releaseInvalidations.complete(Unit)
                releaseSource.complete(Unit)
                joinCommandCollection(collection)
            }
        }

        private suspend fun assertCoalescedSnapshots() {
            val first = roomState(roomCode, "first-command-snapshot")
            val second = roomState(roomCode, "coalesced-command-snapshot")
            fetcher.complete(0, RoomFetchResult.Success(first))
            awaitRefreshPhase("initial refresh whole terminal", ::commandLastState) { fetcher.requests.first().producer.join() }
            awaitRefreshPhase("exactly one successor GET entered", ::commandLastState) { fetcher.successorEntered.await() }
            scope.runCurrent()
            assertEquals("exactly one coalesced successor", 2, fetcher.requests.size)
            assertEquals(active(first, Freshness.FRESH, LiveConnection.CONNECTED), observed.last())
            fetcher.complete(1, RoomFetchResult.Success(second))
            awaitRefreshPhase("successor refresh whole terminal", ::commandLastState) { fetcher.requests.last().producer.join() }
            scope.runCurrent()
            assertEquals(listOf(roomCode, roomCode), fetcher.requests.map(Request::roomCode))
            assertEquals("no third GET after draining commands", 2, fetcher.requests.size)
            assertEquals(
                listOf(
                    active(), active(connection = LiveConnection.CONNECTED),
                    active(first, Freshness.FRESH, LiveConnection.CONNECTED),
                    active(second, Freshness.FRESH, LiveConnection.CONNECTED),
                ), observed,
            )
            assertEquals("no periodic work mixed with event admission", 0L, scope.testScheduler.currentTime)
        }

        suspend fun assertInitialGetRetiredAfterStartup(collection: Job, boundary: String) {
            // Reobserve the natural terminal already joined for the explicit outcome assertion.
            awaitRefreshPhase("$boundary natural whole collection joined", ::commandLastState) { collection.join() }
            assertEquals("$boundary owner-approved initial GET count; ${commandLastState()}", 1, fetcher.requests.size)
            awaitRefreshPhase("$boundary actual initial GET body entered", ::commandLastState) { fetcher.initialEntered.await() }
            val request = fetcher.requests.single()
            awaitRefreshPhase("$boundary exact refresh producer whole terminal", ::commandLastState) { request.producer.join() }
            assertTrue("$boundary held GET cancelled before any test result release", request.cancelled)
            assertTrue("$boundary fetch cleanup entered", request.cleanupEntered.isCompleted)
            assertTrue("$boundary fetch cleanup returned", request.cleanupReturned.isCompleted)
            assertFalse("$boundary GET result still incomplete", request.result.isCompleted)
            assertTrue("$boundary exact refresh Job completed", request.producer.isCompleted)
            assertEquals("$boundary sole acquired GET room", listOf("ABCD"), fetcher.requests.map(Request::roomCode))
            assertEquals("$boundary sole event connection room", listOf("ABCD"), events.connections.map { it.roomCode })
            val connection = events.connections.single()
            awaitRefreshPhase("$boundary exact event producer whole terminal", ::commandLastState) { connection.producer.join() }
            assertTrue("$boundary event cleanup entered", connection.cleanupEntered.isCompleted)
            assertTrue("$boundary event cleanup returned", connection.cleanupReturned.isCompleted)
            assertTrue("$boundary exact event Job completed", connection.producer.isCompleted)
            assertTrue("$boundary whole collection completed", collection.isCompleted)
            assertEquals("$boundary no periodic deadline crossed", 0L, scope.testScheduler.currentTime)
        }

        suspend fun joinCommandCollection(collection: Job) {
            collection.cancel()
            fetcher.requests.forEach { it.result.complete(RoomFetchResult.Failure) }
            withContext(NonCancellable) {
                events.connections.forEach { connection ->
                    awaitRefreshPhase("event cleanup entered", ::commandLastState) { connection.cleanupEntered.await() }
                }
                awaitRefreshPhase("whole collection cleanup terminal", ::commandLastState) { collection.join() }
                events.connections.forEach { connection ->
                    awaitRefreshPhase("exact event producer terminal", ::commandLastState) { connection.producer.join() }
                    assertTrue("event cleanup returned, not merely entered", connection.cleanupReturned.isCompleted)
                    assertTrue("exact event Job completed", connection.producer.isCompleted)
                }
                fetcher.requests.forEach { request ->
                    awaitRefreshPhase("exact refresh producer terminal", ::commandLastState) { request.producer.join() }
                    assertTrue("fetch finally entered", request.cleanupEntered.isCompleted)
                    assertTrue("fetch finally returned", request.cleanupReturned.isCompleted)
                    assertTrue("exact refresh Job completed", request.producer.isCompleted)
                }
                assertTrue("whole collection positively joined", collection.isCompleted)
            }
        }

        fun commandLastState() = "emissions=$observed, requests=${fetcher.requests.size}, " +
            "refreshTerminal=${fetcher.requests.map { it.producer.isCompleted }}, connections=${events.connections.size}, " +
            "receipts=${events.connections.lastOrNull()?.receiptState()}"
    }

    private class TestFetcher {
        val requests = mutableListOf<Request>()
        val initialEntered = CompletableDeferred<Unit>()
        val successorEntered = CompletableDeferred<Unit>()

        suspend fun fetch(roomCode: String): RoomFetchResult {
            val request = Request(roomCode, producer = currentCoroutineContext().job)
            requests += request
            if (requests.size == 1) initialEntered.complete(Unit)
            if (requests.size == 2) successorEntered.complete(Unit)
            return try {
                request.result.await()
            } finally {
                request.cleanupEntered.complete(Unit)
                if (!request.result.isCompleted) request.cancelled = true
                request.cleanupReturned.complete(Unit)
            }
        }

        fun complete(index: Int, result: RoomFetchResult) {
            requests[index].result.complete(result)
        }
    }

    private data class Request(
        val roomCode: String,
        val producer: Job,
        val result: CompletableDeferred<RoomFetchResult> = CompletableDeferred(),
        var cancelled: Boolean = false,
        val cleanupEntered: CompletableDeferred<Unit> = CompletableDeferred(),
        val cleanupReturned: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private class TestEventStreams(
        private val cleanupGate: CompletableDeferred<Unit>? = null,
    ) : RoomEventStreamFactory {
        val connections = mutableListOf<Connection>()
        val latest: Connection
            get() = connections.last()
        var script: (suspend FlowCollector<RoomEventStreamEvent>.(Connection) -> Unit)? = null

        override fun observe(roomCode: String): Flow<RoomEventStreamEvent> = flow {
            val connection = Connection(roomCode, currentCoroutineContext().job)
            connections += connection
            try {
                val inlineScript = script
                if (inlineScript != null) {
                    inlineScript.invoke(this, connection)
                    return@flow
                }
                while (true) {
                    val event = connection.events.receive()
                    emit(event)
                    if (event is RoomEventStreamEvent.Failure || event == RoomEventStreamEvent.Closed) return@flow
                }
            } finally {
                connection.cancelled = true
                connection.cleanupEntered.complete(Unit)
                cleanupGate?.let { withContext(NonCancellable) { it.await() } }
                connection.cleanupReturned.complete(Unit)
            }
        }

        class Connection(val roomCode: String, val producer: Job) {
            val events = Channel<RoomEventStreamEvent>(Channel.UNLIMITED)
            var cancelled = false
            val cleanupEntered = CompletableDeferred<Unit>()
            val cleanupReturned = CompletableDeferred<Unit>()
            val firstReached = CompletableDeferred<Unit>()
            val firstReturned = CompletableDeferred<Unit>()
            val secondReached = CompletableDeferred<Unit>()
            val secondReturned = CompletableDeferred<Unit>()
            val thirdReached = CompletableDeferred<Unit>()
            val thirdReturned = CompletableDeferred<Unit>()
            val fourthReached = CompletableDeferred<Unit>()
            val fourthReturned = CompletableDeferred<Unit>()
            val fifthReached = CompletableDeferred<Unit>()
            val fifthReturned = CompletableDeferred<Unit>()

            fun emit(event: RoomEventStreamEvent) {
                events.trySend(event)
            }

            suspend fun emitWithReceipt(
                collector: FlowCollector<RoomEventStreamEvent>,
                event: RoomEventStreamEvent,
                reached: CompletableDeferred<Unit>,
                returned: CompletableDeferred<Unit>,
            ) {
                reached.complete(Unit)
                collector.emit(event)
                returned.complete(Unit)
            }

            suspend fun emitFourInvalidations(collector: FlowCollector<RoomEventStreamEvent>) {
                emitWithReceipt(collector, RoomEventStreamEvent.Event("queue_snapshot"), firstReached, firstReturned)
                emitWithReceipt(collector, RoomEventStreamEvent.Event("queue_updated"), secondReached, secondReturned)
                emitWithReceipt(collector, RoomEventStreamEvent.Event("track_changed"), thirdReached, thirdReturned)
                emitWithReceipt(collector, RoomEventStreamEvent.Event("player_state"), fourthReached, fourthReturned)
            }

            suspend fun awaitFourReturns(lastState: () -> String) {
                awaitRefreshPhase("first actual invalidation emit returned", lastState) { firstReturned.await() }
                awaitRefreshPhase("second actual invalidation emit returned", lastState) { secondReturned.await() }
                awaitRefreshPhase("third actual invalidation emit returned", lastState) { thirdReturned.await() }
                awaitRefreshPhase("fourth actual invalidation emit returned", lastState) { fourthReturned.await() }
            }

            fun receiptState() = "fourReturned=${firstReturned.isCompleted}/${secondReturned.isCompleted}/" +
                "${thirdReturned.isCompleted}/${fourthReturned.isCompleted}, fifthReached=${fifthReached.isCompleted}, " +
                "fifthReturned=${fifthReturned.isCompleted}, cleanupEntered=${cleanupEntered.isCompleted}, " +
                "cleanupReturned=${cleanupReturned.isCompleted}, eventTerminal=${producer.isCompleted}"
        }
    }

    companion object {
        private suspend fun <T> awaitRefreshPhase(
            step: String,
            lastState: () -> String,
            timeoutMillis: Long = 5_000L,
            action: suspend () -> T,
        ): T = try {
            withTimeout(timeoutMillis) { action() }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("$step: timeout; last state=${lastState()}", timeout)
        }

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
