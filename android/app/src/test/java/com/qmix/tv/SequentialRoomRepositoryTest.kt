package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SequentialRoomRepositoryTest {
    @Test
    fun initial_refresh_publishes_the_first_successful_room_state() {
        val fetcher = FakeRoomStateFetcher()
        val events = FakeRoomEventStreamFactory()
        val scheduler = FakeRoomSyncScheduler()
        val observed = mutableListOf<RoomSyncState>()
        val repository = SequentialRoomRepository(fetcher, events, scheduler, DirectExecutor)

        val subscription = repository.observe("ABCD", observed::add)

        assertEquals(1, fetcher.requests.size)
        assertEquals(RoomSyncState.Active("ABCD", null, Freshness.LOADING, LiveConnection.CONNECTING), observed.single())

        val room = roomState("ABCD", "one")
        fetcher.requests.single().complete(RoomFetchResult.Success(room))

        assertEquals(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTING), observed.last())
        subscription.close()
    }

    @Test
    fun only_room_change_events_trigger_rest_reconciliation() {
        val fetcher = FakeRoomStateFetcher()
        val events = FakeRoomEventStreamFactory()
        val repository = SequentialRoomRepository(fetcher, events, FakeRoomSyncScheduler(), DirectExecutor)
        val subscription = repository.observe("ABCD") { }
        fetcher.requests.single().complete(RoomFetchResult.Success(roomState("ABCD", "initial")))

        listOf("heartbeat", null, "unknown").forEach(events::emit)
        assertEquals(1, fetcher.requests.size)

        listOf("queue_snapshot", "queue_updated", "track_changed", "player_state").forEachIndexed { index, type ->
            events.emit(type)
            assertEquals(index + 2, fetcher.requests.size)
            fetcher.requests.last().complete(RoomFetchResult.Success(roomState("ABCD", type)))
        }
        subscription.close()
    }

    @Test
    fun periodic_refresh_recovers_state_every_fifteen_seconds() {
        val fetcher = FakeRoomStateFetcher()
        val scheduler = FakeRoomSyncScheduler()
        val observed = mutableListOf<RoomSyncState>()
        val repository = SequentialRoomRepository(fetcher, FakeRoomEventStreamFactory(), scheduler, DirectExecutor)
        val subscription = repository.observe("ABCD", observed::add)
        fetcher.requests.single().complete(RoomFetchResult.Success(roomState("ABCD", "initial")))

        assertEquals(listOf(15_000L), scheduler.delays)
        scheduler.runNext()
        assertEquals(2, fetcher.requests.size)
        val recovered = roomState("ABCD", "recovered")
        fetcher.requests.last().complete(RoomFetchResult.Success(recovered))

        assertEquals(recovered, (observed.last() as RoomSyncState.Active).room)
        assertEquals(listOf(15_000L, 15_000L), scheduler.delays)
        subscription.close()
    }

    @Test
    fun events_during_a_get_coalesce_into_one_immediate_follow_up() {
        val fetcher = FakeRoomStateFetcher()
        val events = FakeRoomEventStreamFactory()
        val repository = SequentialRoomRepository(fetcher, events, FakeRoomSyncScheduler(), DirectExecutor)
        val subscription = repository.observe("ABCD") { }

        events.emit("queue_updated")
        events.emit("track_changed")
        events.emit("player_state")
        assertEquals(1, fetcher.requests.size)

        fetcher.requests.single().complete(RoomFetchResult.Success(roomState("ABCD", "first")))
        assertEquals(2, fetcher.requests.size)
        fetcher.requests.last().complete(RoomFetchResult.Success(roomState("ABCD", "second")))
        assertEquals(2, fetcher.requests.size)
        subscription.close()
    }

    @Test
    fun network_failure_preserves_the_last_room_and_marks_it_stale() {
        val fetcher = FakeRoomStateFetcher()
        val events = FakeRoomEventStreamFactory()
        val observed = mutableListOf<RoomSyncState>()
        val repository = SequentialRoomRepository(fetcher, events, FakeRoomSyncScheduler(), DirectExecutor)
        val subscription = repository.observe("ABCD", observed::add)
        val room = roomState("ABCD", "last-known")
        fetcher.requests.single().complete(RoomFetchResult.Success(room))

        events.emit("queue_updated")
        fetcher.requests.last().complete(RoomFetchResult.Failure)

        assertEquals(
            RoomSyncState.Active("ABCD", room, Freshness.STALE, LiveConnection.CONNECTING),
            observed.last(),
        )
        subscription.close()
    }

    @Test
    fun room_not_found_is_terminal_and_cancels_live_work() {
        val fetcher = FakeRoomStateFetcher()
        val events = FakeRoomEventStreamFactory()
        val scheduler = FakeRoomSyncScheduler()
        val observed = mutableListOf<RoomSyncState>()
        val repository = SequentialRoomRepository(fetcher, events, scheduler, DirectExecutor)
        repository.observe("ABCD", observed::add)
        fetcher.requests.single().complete(RoomFetchResult.Success(roomState("ABCD", "initial")))

        events.emit("queue_updated")
        fetcher.requests.last().complete(RoomFetchResult.Missing)

        assertEquals(RoomSyncState.Missing("ABCD"), observed.last())
        assertEquals(true, events.connectionCanceled)
        events.emit("track_changed")
        scheduler.runNext()
        assertEquals(2, fetcher.requests.size)
    }

    @Test
    fun closing_cancels_every_resource_and_rejects_late_callbacks() {
        val fetcher = FakeRoomStateFetcher()
        val events = FakeRoomEventStreamFactory()
        val scheduler = FakeRoomSyncScheduler()
        val observed = mutableListOf<RoomSyncState>()
        val repository = SequentialRoomRepository(fetcher, events, scheduler, DirectExecutor)
        val subscription = repository.observe("ABCD", observed::add)
        val request = fetcher.requests.single()
        val observedBeforeClose = observed.size

        subscription.close()
        assertEquals(true, request.canceled)
        assertEquals(true, events.connectionCanceled)
        request.complete(RoomFetchResult.Success(roomState("ABCD", "late")))
        events.emit("queue_updated")
        scheduler.runNext()

        assertEquals(observedBeforeClose, observed.size)
        assertEquals(1, fetcher.requests.size)
    }

    @Test
    fun a_synchronous_fetch_completion_cannot_block_the_next_refresh() {
        val returnedHandles = mutableListOf<RecordingCancelable>()
        var fetchCount = 0
        val fetcher = RoomStateFetcher { roomCode, callback ->
            fetchCount++
            callback(RoomFetchResult.Success(roomState(roomCode, "state-$fetchCount")))
            RecordingCancelable().also(returnedHandles::add)
        }
        val events = FakeRoomEventStreamFactory()
        val observed = mutableListOf<RoomSyncState>()
        val repository = SequentialRoomRepository(
            fetcher,
            events,
            FakeRoomSyncScheduler(),
            DirectExecutor,
        )

        repository.observe("ABCD", observed::add)
        events.connections.single().emit("queue_updated")

        assertEquals(2, returnedHandles.size)
        assertEquals("state-2", (observed.last() as RoomSyncState.Active).room?.current?.trackId)
    }

    @Test(timeout = 5_000L)
    fun close_waits_for_an_in_flight_observer_then_prevents_startup_work() {
        val fetcher = FakeRoomStateFetcher()
        val events = FakeRoomEventStreamFactory()
        val scheduler = FakeRoomSyncScheduler()
        val dispatcher = QueuedExecutor()
        val observerEntered = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val repository = SequentialRoomRepository(fetcher, events, scheduler, dispatcher)
        val subscription = repository.observe("ABCD") {
            observerEntered.countDown()
            releaseObserver.await()
        }
        val starter = Thread(dispatcher::runNext)
        starter.start()
        observerEntered.await()
        val closeStarted = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closer = Thread {
            closeStarted.countDown()
            subscription.close()
            closeReturned.countDown()
        }
        closer.start()

        closeStarted.await()
        assertEquals(false, closeReturned.await(100, TimeUnit.MILLISECONDS))
        releaseObserver.countDown()
        assertEquals(true, closeReturned.await(2, TimeUnit.SECONDS))
        starter.join()
        closer.join()

        assertEquals(0, fetcher.requests.size)
        assertEquals(0, events.connections.size)
        assertEquals(emptyList<Long>(), scheduler.delays)
    }

    @Test(timeout = 5_000L)
    fun every_concurrent_close_waits_for_resource_cancellation() {
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val fetcher = RoomStateFetcher { _, _ ->
            Cancelable {
                cancelEntered.countDown()
                releaseCancel.await()
            }
        }
        val repository = SequentialRoomRepository(
            fetcher,
            FakeRoomEventStreamFactory(),
            FakeRoomSyncScheduler(),
            DirectExecutor,
        )
        val subscription = repository.observe("ABCD") { }
        val firstReturned = CountDownLatch(1)
        val secondReturned = CountDownLatch(1)
        Thread { subscription.close(); firstReturned.countDown() }.start()
        cancelEntered.await()
        Thread { subscription.close(); secondReturned.countDown() }.start()

        assertEquals(false, secondReturned.await(100, TimeUnit.MILLISECONDS))
        releaseCancel.countDown()

        assertEquals(true, firstReturned.await(2, TimeUnit.SECONDS))
        assertEquals(true, secondReturned.await(2, TimeUnit.SECONDS))
    }

    @Test(timeout = 5_000L)
    fun observer_can_close_the_subscription_reentrantly() {
        val fetcher = FakeRoomStateFetcher()
        val events = FakeRoomEventStreamFactory()
        val repository = SequentialRoomRepository(fetcher, events, FakeRoomSyncScheduler(), DirectExecutor)
        lateinit var subscription: AutoCloseable
        subscription = repository.observe("ABCD") { state ->
            if (state is RoomSyncState.Active && state.freshness == Freshness.FRESH) subscription.close()
        }

        fetcher.requests.single().complete(RoomFetchResult.Success(roomState("ABCD", "initial")))

        assertEquals(true, events.connectionCanceled)
    }

    @Test
    fun close_cancels_resources_before_a_blocked_dispatcher_can_run() {
        val fetcher = FakeRoomStateFetcher()
        val events = FakeRoomEventStreamFactory()
        val dispatcher = QueuedExecutor()
        val repository = SequentialRoomRepository(fetcher, events, FakeRoomSyncScheduler(), dispatcher)
        val subscription = repository.observe("ABCD") { }
        dispatcher.runNext()

        subscription.close()

        assertEquals(true, fetcher.requests.single().canceled)
        assertEquals(true, events.connectionCanceled)
        assertEquals(0, dispatcher.pendingCount)
    }

    @Test
    fun reconnect_uses_backoff_forces_refresh_and_ignores_the_old_connection() {
        val fetcher = FakeRoomStateFetcher()
        val events = FakeRoomEventStreamFactory()
        val scheduler = FakeRoomSyncScheduler()
        val observed = mutableListOf<RoomSyncState>()
        val repository = SequentialRoomRepository(
            fetcher,
            events,
            scheduler,
            DirectExecutor,
            ReconnectBackoff(randomFraction = { 0.0 }),
        )
        val subscription = repository.observe("ABCD", observed::add)
        fetcher.requests.single().complete(RoomFetchResult.Success(roomState("ABCD", "initial")))
        val oldConnection = events.connections.single()
        oldConnection.open()
        assertEquals(1, fetcher.requests.size)

        oldConnection.fail(503)
        assertEquals(listOf(15_000L, 500L), scheduler.delays)
        scheduler.runDelay(500L)
        assertEquals(2, events.connections.size)
        events.connections.last().open()
        assertEquals(2, fetcher.requests.size)
        val newest = roomState("ABCD", "newest")
        fetcher.requests.last().complete(RoomFetchResult.Success(newest))

        oldConnection.emit("queue_updated")
        assertEquals(2, fetcher.requests.size)
        assertEquals(newest, (observed.last() as RoomSyncState.Active).room)
        subscription.close()
    }

    @Test
    fun failed_sse_connection_logs_boundary_and_reconnect_schedule() {
        val events = FakeRoomEventStreamFactory()
        val sink = RecordingLogSink()
        val logger = QMixLogger(sink, QMixLogLevel.DEBUG) { null }
            .component(QMixLogComponent.ROOM_SYNC_SSE_RECONNECT)
        val repository = SequentialRoomRepository(
            FakeRoomStateFetcher(),
            events,
            FakeRoomSyncScheduler(),
            DirectExecutor,
            ReconnectBackoff(randomFraction = { 0.0 }),
            logger,
        )
        val subscription = repository.observe("secret-room-code") { }

        events.connections.single().fail(503)

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
        assertEquals(false, sink.records.toString().contains("secret-room-code"))
        subscription.close()
    }

    @Test
    fun repeated_short_lived_sse_connections_emit_one_default_warning() {
        val events = FakeRoomEventStreamFactory()
        val scheduler = FakeRoomSyncScheduler()
        val sink = RecordingLogSink()
        val logger = QMixLogger(sink, QMixLogLevel.WARN) { null }
            .component(QMixLogComponent.ROOM_SYNC_SSE_RECONNECT)
        val repository = SequentialRoomRepository(
            FakeRoomStateFetcher(),
            events,
            scheduler,
            DirectExecutor,
            ReconnectBackoff(randomFraction = { 0.0 }),
            logger,
        )
        val subscription = repository.observe("ABCD") { }

        repeat(3) { attempt ->
            events.connections.last().open()
            events.connections.last().fail(503)
            if (attempt < 2) scheduler.runDelay(500L)
        }

        assertEquals(
            listOf(QMixLogOperation.SSE_CONNECTION),
            sink.records.map(QMixLogRecord::operation),
        )
        subscription.close()
    }

    @Test
    fun close_invalidates_an_already_fired_retry_timer_callback() {
        val events = FakeRoomEventStreamFactory()
        val scheduler = FakeRoomSyncScheduler()
        val dispatcher = QueuedExecutor()
        val repository = SequentialRoomRepository(
            FakeRoomStateFetcher(),
            events,
            scheduler,
            dispatcher,
            ReconnectBackoff(randomFraction = { 0.0 }),
        )
        val subscription = repository.observe("ABCD") { }
        dispatcher.runNext()
        events.connections.single().fail(503)
        dispatcher.runNext()
        scheduler.runDelay(500L)

        subscription.close()
        dispatcher.runNext()

        assertEquals(1, events.connections.size)
    }

    private fun roomState(code: String, trackId: String) = RoomState(
        code = code,
        current = CurrentTrack(trackId, 0, "playing", "Title", "Artist"),
        queue = emptyList(),
    )
}

private object DirectExecutor : java.util.concurrent.Executor {
    override fun execute(command: Runnable) = command.run()
}

private class QueuedExecutor : java.util.concurrent.Executor {
    private val commands = ArrayDeque<Runnable>()
    val pendingCount: Int
        get() = commands.size

    override fun execute(command: Runnable) {
        commands += command
    }

    fun runNext() {
        commands.removeFirst().run()
    }
}

private class RecordingCancelable : Cancelable {
    var canceled = false

    override fun cancel() {
        canceled = true
    }
}

private class FakeRoomStateFetcher : RoomStateFetcher {
    val requests = mutableListOf<Request>()

    override fun fetch(roomCode: String, callback: (RoomFetchResult) -> Unit): Cancelable {
        val request = Request(roomCode, callback)
        requests += request
        return request
    }

    class Request(
        val roomCode: String,
        private val callback: (RoomFetchResult) -> Unit,
    ) : Cancelable {
        var canceled = false
            private set

        override fun cancel() {
            canceled = true
        }

        fun complete(result: RoomFetchResult) = callback(result)
    }
}

private class FakeRoomEventStreamFactory : RoomEventStreamFactory {
    val connections = mutableListOf<Connection>()
    val listener: RoomEventListener?
        get() = connections.lastOrNull()?.listener
    val connectionCanceled: Boolean
        get() = connections.lastOrNull()?.canceled == true

    override fun connect(roomCode: String, listener: RoomEventListener): Cancelable {
        val connection = Connection(listener)
        connections += connection
        return connection
    }

    fun emit(type: String?) {
        connections.lastOrNull()?.emit(type)
    }

    class Connection(val listener: RoomEventListener) : Cancelable {
        var canceled = false
            private set

        override fun cancel() {
            canceled = true
        }

        fun open() = listener.onOpen()
        fun emit(type: String?) = listener.onEvent(type)
        fun fail(statusCode: Int?) = listener.onFailure(statusCode)
    }
}

private class FakeRoomSyncScheduler : RoomSyncScheduler {
    val delays = mutableListOf<Long>()
    private val tasks = ArrayDeque<Scheduled>()

    override fun schedule(delayMillis: Long, action: () -> Unit): Cancelable {
        val scheduled = Scheduled(delayMillis, action)
        delays += delayMillis
        tasks += scheduled
        return scheduled
    }

    fun runNext() {
        tasks.removeFirst().run()
    }

    fun runDelay(delayMillis: Long) {
        val scheduled = tasks.first { it.delayMillis == delayMillis }
        tasks.remove(scheduled)
        scheduled.run()
    }

    private class Scheduled(val delayMillis: Long, private val action: () -> Unit) : Cancelable {
        private var canceled = false

        override fun cancel() {
            canceled = true
        }

        fun run() {
            if (!canceled) action()
        }
    }
}
