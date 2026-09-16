package com.qmix.tv

import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

fun interface Cancelable {
    fun cancel()
}

enum class Freshness { LOADING, FRESH, STALE }

enum class LiveConnection { CONNECTING, CONNECTED, RECONNECTING }

sealed interface RoomSyncState {
    val roomCode: String

    data class Active(
        override val roomCode: String,
        val room: RoomState?,
        val freshness: Freshness,
        val connection: LiveConnection,
    ) : RoomSyncState

    data class Missing(override val roomCode: String) : RoomSyncState
}

sealed interface RoomFetchResult {
    data class Success(val room: RoomState) : RoomFetchResult
    data object Missing : RoomFetchResult
    data object Failure : RoomFetchResult
}

fun interface RoomStateFetcher {
    fun fetch(roomCode: String, callback: (RoomFetchResult) -> Unit): Cancelable
}

interface RoomEventListener {
    fun onOpen()
    fun onEvent(type: String?)
    fun onClosed()
    fun onFailure(statusCode: Int?)
}

fun interface RoomEventStreamFactory {
    fun connect(roomCode: String, listener: RoomEventListener): Cancelable
}

fun interface RoomSyncScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): Cancelable
}

fun interface RoomRepository {
    fun observe(roomCode: String, onUpdate: (RoomSyncState) -> Unit): AutoCloseable
}

class SequentialRoomRepository(
    private val fetcher: RoomStateFetcher,
    private val eventStreams: RoomEventStreamFactory,
    private val scheduler: RoomSyncScheduler,
    dispatcher: Executor,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val logger: QMixComponentLogger =
        QMixComponentLogger.noOp(QMixLogComponent.ROOM_SYNC_SSE_RECONNECT),
) : RoomRepository {
    private val dispatcher = SerialExecutor(dispatcher)
    private companion object {
        const val PERIODIC_REFRESH_MILLIS = 15_000L
        val CHANGE_EVENTS = setOf("queue_snapshot", "queue_updated", "track_changed", "player_state")
    }

    override fun observe(roomCode: String, onUpdate: (RoomSyncState) -> Unit): AutoCloseable {
        val session = Session(roomCode, onUpdate)
        dispatcher.execute { session.start() }
        return AutoCloseable { session.close() }
    }

    private inner class Session(
        private val roomCode: String,
        private val observer: (RoomSyncState) -> Unit,
    ) {
        private val lifecycleLock = ReentrantLock()
        private val quiescent = lifecycleLock.newCondition()
        private val operationDepth = ThreadLocal<Int>()
        private var activeOperations = 0
        private var cancellationComplete = false
        private var terminationThread: Thread? = null

        @Volatile
        private var closed = false
        private var room: RoomState? = null
        private var freshness = Freshness.LOADING
        private var connection = LiveConnection.CONNECTING

        @Volatile
        private var request: Cancelable? = null
        @Volatile
        private var requestGeneration = 0L
        private var refreshPending = false

        @Volatile
        private var stream: Cancelable? = null
        private var connectionGeneration = 0L
        private var connectionAttempt = 0
        private var sseFailureWarningEmitted = false

        @Volatile
        private var retry: Cancelable? = null
        private var retryGeneration = 0L
        private var retryAttempt = 0

        @Volatile
        private var periodic: Cancelable? = null

        fun start() {
            if (closed) return
            publishState()
            refresh()
            connectEvents()
            schedulePeriodicRefresh()
        }

        private fun connectEvents() {
            runIfOpen {
                connectionGeneration++
                val generation = connectionGeneration
                val refreshOnOpen = connectionAttempt++ > 0
                val connected = eventStreams.connect(roomCode, object : RoomEventListener {
                    override fun onOpen() {
                        dispatcher.execute {
                            if (!isCurrentConnection(generation)) return@execute
                            retryAttempt = 0
                            connection = LiveConnection.CONNECTED
                            publishState()
                            if (refreshOnOpen) requestRefresh()
                        }
                    }

                    override fun onEvent(type: String?) {
                        if (type in CHANGE_EVENTS) {
                            dispatcher.execute {
                                if (isCurrentConnection(generation)) requestRefresh()
                            }
                        }
                    }

                    override fun onClosed() = connectionEnded(generation, null)

                    override fun onFailure(statusCode: Int?) = connectionEnded(generation, statusCode)
                })
                installStream(generation, connected)
            }
        }

        private fun connectionEnded(generation: Long, statusCode: Int?) {
            dispatcher.execute {
                if (!isCurrentConnection(generation)) return@execute
                val cause = if (statusCode == null) QMixLogCause.NETWORK else QMixLogCause.HTTP_STATUS
                if (sseFailureWarningEmitted) {
                    logger.debug(QMixLogOperation.SSE_CONNECTION, cause)
                } else {
                    sseFailureWarningEmitted = true
                    logger.warn(QMixLogOperation.SSE_CONNECTION, cause)
                }
                if (statusCode == 404) {
                    publishMissing()
                    terminate()
                    return@execute
                }
                connectionGeneration++
                stream = null
                connection = LiveConnection.RECONNECTING
                publishState()
                if (retry != null) return@execute
                val delay = backoff.delayMillis(retryAttempt++)
                logger.info(QMixLogOperation.RECONNECT)
                scheduleRetry(delay)
            }
        }

        private fun scheduleRetry(delayMillis: Long) {
            runIfOpen {
                val generation = ++retryGeneration
                val scheduled = scheduler.schedule(delayMillis) {
                    dispatcher.execute {
                        if (closed || retry == null || generation != retryGeneration) return@execute
                        retryGeneration++
                        retry = null
                        connectEvents()
                    }
                }
                installRetry(generation, scheduled)
            }
        }

        private fun isCurrentConnection(generation: Long): Boolean =
            !closed && generation == connectionGeneration

        private fun schedulePeriodicRefresh() {
            runIfOpen {
                val scheduled = scheduler.schedule(PERIODIC_REFRESH_MILLIS) {
                    dispatcher.execute {
                        if (closed) return@execute
                        schedulePeriodicRefresh()
                        requestRefresh()
                    }
                }
                installPeriodic(scheduled)
            }
        }

        private fun requestRefresh() {
            if (closed) return
            if (request != null) {
                refreshPending = true
                return
            }
            refresh()
        }

        private fun refresh() {
            runIfOpen {
                val generation = ++requestGeneration
                val started = fetcher.fetch(roomCode) { result ->
                    dispatcher.execute {
                        if (closed || generation != requestGeneration) return@execute
                        requestGeneration++
                        request = null
                        when (result) {
                            is RoomFetchResult.Success -> {
                                room = result.room
                                freshness = Freshness.FRESH
                                publishState()
                            }
                            RoomFetchResult.Missing -> {
                                publishMissing()
                                terminate()
                            }
                            RoomFetchResult.Failure -> {
                                freshness = Freshness.STALE
                                publishState()
                            }
                        }
                        if (!closed && refreshPending) {
                            refreshPending = false
                            refresh()
                        }
                    }
                }
                installRequest(generation, started)
            }
        }

        fun close() {
            terminate()
        }

        private fun installRequest(generation: Long, handle: Cancelable) {
            val cancel = lifecycleLock.withLock {
                if (closed || generation != requestGeneration) true else {
                    request = handle
                    false
                }
            }
            if (cancel) handle.cancel()
        }

        private fun installStream(generation: Long, handle: Cancelable) {
            val cancel = lifecycleLock.withLock {
                if (closed || generation != connectionGeneration) true else {
                    stream = handle
                    false
                }
            }
            if (cancel) handle.cancel()
        }

        private fun installRetry(generation: Long, handle: Cancelable) {
            val cancel = lifecycleLock.withLock {
                if (closed || generation != retryGeneration) true else {
                    retry = handle
                    false
                }
            }
            if (cancel) handle.cancel()
        }

        private fun installPeriodic(handle: Cancelable) {
            val cancel = lifecycleLock.withLock {
                if (closed) true else {
                    periodic = handle
                    false
                }
            }
            if (cancel) handle.cancel()
        }

        private fun publishState() {
            runIfOpen {
                observer(RoomSyncState.Active(roomCode, room, freshness, connection))
            }
        }

        private fun publishMissing() {
            runIfOpen {
                observer(RoomSyncState.Missing(roomCode))
            }
        }

        private fun runIfOpen(action: () -> Unit) {
            lifecycleLock.withLock {
                if (closed) return
                activeOperations++
                operationDepth.set((operationDepth.get() ?: 0) + 1)
            }
            try {
                action()
            } finally {
                lifecycleLock.withLock {
                    activeOperations--
                    val depth = (operationDepth.get() ?: 0) - 1
                    if (depth == 0) operationDepth.remove() else operationDepth.set(depth)
                    quiescent.signalAll()
                }
            }
        }

        private fun terminate() {
            val ownOperations = operationDepth.get() ?: 0
            val currentThread = Thread.currentThread()
            var ownsTermination = false
            var resources: List<Cancelable> = emptyList()
            lifecycleLock.withLock {
                if (!closed) {
                    closed = true
                    terminationThread = currentThread
                    ownsTermination = true
                    connectionGeneration++
                    requestGeneration++
                    retryGeneration++
                    resources = listOfNotNull(request, stream, retry, periodic)
                    request = null
                    stream = null
                    retry = null
                    periodic = null
                    refreshPending = false
                } else if (!cancellationComplete && terminationThread === currentThread) {
                    return
                }
            }
            var cancellationFailure: Throwable? = null
            if (ownsTermination) {
                for (resource in resources) {
                    try {
                        resource.cancel()
                    } catch (failure: Throwable) {
                        if (cancellationFailure == null) {
                            cancellationFailure = failure
                        } else {
                            cancellationFailure.addSuppressed(failure)
                        }
                    }
                }
                lifecycleLock.withLock {
                    cancellationComplete = true
                    terminationThread = null
                    quiescent.signalAll()
                }
            }
            lifecycleLock.withLock {
                while (!cancellationComplete || activeOperations > ownOperations) {
                    quiescent.awaitUninterruptibly()
                }
            }
            cancellationFailure?.let { throw it }
        }
    }
}

internal class SerialExecutor(private val delegate: Executor) : Executor {
    private enum class WorkerState { IDLE, SUBMITTING, RUNNING }

    private val lock = ReentrantLock()
    private val submissionResolved = lock.newCondition()
    private val tasks = ArrayDeque<Runnable>()
    private var state = WorkerState.IDLE

    override fun execute(command: Runnable) {
        val submitWorker = lock.withLock {
            while (state == WorkerState.SUBMITTING) submissionResolved.awaitUninterruptibly()
            tasks.addLast(command)
            if (state == WorkerState.IDLE) {
                state = WorkerState.SUBMITTING
                true
            } else {
                false
            }
        }
        if (!submitWorker) return
        try {
            delegate.execute(::drain)
        } catch (rejection: Throwable) {
            lock.withLock {
                tasks.remove(command)
                state = WorkerState.IDLE
                submissionResolved.signalAll()
            }
            throw rejection
        }
    }

    private fun drain() {
        lock.withLock {
            state = WorkerState.RUNNING
            submissionResolved.signalAll()
        }
        while (true) {
            val command = lock.withLock {
                if (tasks.isEmpty()) {
                    state = WorkerState.IDLE
                    return
                }
                tasks.removeFirst()
            }
            try {
                command.run()
            } catch (failure: Throwable) {
                try {
                    Thread.currentThread().uncaughtExceptionHandler?.uncaughtException(Thread.currentThread(), failure)
                } catch (_: Throwable) {
                    // Keep draining: one callback must not wedge later lifecycle work.
                }
            }
        }
    }
}
