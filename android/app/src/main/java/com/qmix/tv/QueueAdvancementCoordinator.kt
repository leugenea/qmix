package com.qmix.tv

import java.util.concurrent.Executor

sealed interface QueueAdvanceCommandResult {
    data object Success : QueueAdvanceCommandResult
    data object Indeterminate : QueueAdvanceCommandResult
    data object Rejected : QueueAdvanceCommandResult
}

fun interface QueueAdvanceCommand {
    fun skip(
        roomCode: String,
        hostToken: String,
        callback: (QueueAdvanceCommandResult) -> Unit,
    )
}

enum class QueueAdvanceOutcome {
    ADVANCED,
    RECONCILED_NO_ADVANCE,
    REJECTED,
}

data class QueueAdvancementState(
    val pending: Boolean = false,
    val lastOutcome: QueueAdvanceOutcome? = null,
)

class QueueAdvancementCoordinator(
    private val roomCode: String,
    private val hostToken: String,
    private val command: QueueAdvanceCommand,
    private val reconciler: RoomStateFetcher,
    private val observer: (QueueAdvancementState) -> Unit = {},
) : AutoCloseable {
    private enum class Phase { COMMAND, RECONCILING }

    private data class Pending(
        val token: Long,
        val previousCurrentId: String?,
        var phase: Phase,
    )

    private var latestRoom: RoomState? = null
    private var currentInitialized = false
    private var currentGeneration = 0L
    private var endedConsumedGeneration: Long? = null
    private var nextToken = 0L
    private var pending: Pending? = null
    private var reconcileRequest: Cancelable? = null
    private var reconcileInFlight = false
    private var foregroundReady = true
    private var closed = false

    @Volatile
    var state: QueueAdvancementState = QueueAdvancementState()
        private set

    fun onAuthoritativeRoom(room: RoomState) {
        if (room.code != roomCode) return
        val retryToken = synchronized(this) {
            if (closed || !foregroundReady) return
            updateLatestRoomLocked(room)
            val active = pending
            active?.token?.takeIf { active.phase == Phase.RECONCILING && !reconcileInFlight }
        }
        retryToken?.let(::startReconciliation)
    }

    fun onForegroundLost() {
        var notification: QueueAdvancementState? = null
        val request = synchronized(this) {
            if (closed || !foregroundReady) return
            foregroundReady = false
            nextToken++
            pending = null
            val active = reconcileRequest
            reconcileRequest = null
            reconcileInFlight = false
            if (state.pending) {
                state = state.copy(pending = false)
                notification = state
            }
            active
        }
        request?.cancel()
        notification?.let(observer)
    }

    fun onForegroundReconciled(room: RoomState) {
        if (room.code != roomCode) return
        synchronized(this) {
            if (closed || foregroundReady) return
            updateLatestRoomLocked(room)
            foregroundReady = true
        }
    }

    fun requestExplicitAdvance(): Boolean = requestAdvance(endedTrackId = null)

    fun onPlaybackEnded(trackId: String): Boolean = requestAdvance(endedTrackId = trackId)

    private fun requestAdvance(endedTrackId: String?): Boolean {
        lateinit var nextState: QueueAdvancementState
        val token: Long
        synchronized(this) {
            if (closed || !foregroundReady || pending != null) return false
            val room = latestRoom ?: return false
            if (room.queue.isEmpty()) return false
            if (endedTrackId != null) {
                if (room.current?.trackId != endedTrackId) return false
                if (endedConsumedGeneration == currentGeneration) return false
                endedConsumedGeneration = currentGeneration
            }
            token = ++nextToken
            pending = Pending(token, room.current?.trackId, Phase.COMMAND)
            nextState = QueueAdvancementState(pending = true, lastOutcome = state.lastOutcome)
            state = nextState
        }
        observer(nextState)
        var dispatchFailure = false
        val dispatched = synchronized(this) {
            val active = pending
            if (closed || active?.token != token || active.phase != Phase.COMMAND) {
                false
            } else {
                try {
                    command.skip(roomCode, hostToken) { result -> onCommandResult(token, result) }
                } catch (_: Throwable) {
                    dispatchFailure = true
                }
                true
            }
        }
        if (dispatchFailure) {
            onCommandResult(token, QueueAdvanceCommandResult.Indeterminate)
        }
        return dispatched
    }

    private fun onCommandResult(token: Long, result: QueueAdvanceCommandResult) {
        var rejectedState: QueueAdvancementState? = null
        val reconcile = synchronized(this) {
            val active = pending
            if (closed || active?.token != token || active.phase != Phase.COMMAND) return
            if (result == QueueAdvanceCommandResult.Rejected) {
                pending = null
                val rejected = QueueAdvancementState(pending = false, lastOutcome = QueueAdvanceOutcome.REJECTED)
                rejectedState = rejected
                state = rejected
                false
            } else {
                active.phase = Phase.RECONCILING
                true
            }
        }
        rejectedState?.let(observer)
        if (!reconcile) return

        startReconciliation(token)
    }

    private fun startReconciliation(token: Long) {
        val request = synchronized(this) {
            val active = pending
            if (closed || active?.token != token || active.phase != Phase.RECONCILING || reconcileInFlight) {
                null
            } else {
                reconcileInFlight = true
                try {
                    reconciler.fetch(roomCode) { result -> onReconciliationResult(token, result) }
                } catch (_: Throwable) {
                    reconcileInFlight = false
                    reconcileRequest = null
                    null
                }
            }
        }
        if (request == null) return
        val cancel = synchronized(this) {
            val active = pending
            if (closed || active?.token != token || active.phase != Phase.RECONCILING || !reconcileInFlight) {
                true
            } else {
                reconcileRequest = request
                false
            }
        }
        if (cancel) request.cancel()
    }

    private fun onReconciliationResult(token: Long, result: RoomFetchResult) {
        var notification: QueueAdvancementState? = null
        synchronized(this) {
            val active = pending
            if (closed || active?.token != token || active.phase != Phase.RECONCILING) return
            reconcileInFlight = false
            reconcileRequest = null
            when (result) {
                is RoomFetchResult.Success -> {
                    if (result.room.code != roomCode) return
                    updateLatestRoomLocked(result.room)
                    val outcome = if (active.previousCurrentId != result.room.current?.trackId) {
                        QueueAdvanceOutcome.ADVANCED
                    } else {
                        QueueAdvanceOutcome.RECONCILED_NO_ADVANCE
                    }
                    pending = null
                    val settled = QueueAdvancementState(pending = false, lastOutcome = outcome)
                    notification = settled
                    state = settled
                }
                RoomFetchResult.Missing -> {
                    pending = null
                    val rejected = QueueAdvancementState(pending = false, lastOutcome = QueueAdvanceOutcome.REJECTED)
                    notification = rejected
                    state = rejected
                }
                RoomFetchResult.Failure -> Unit
            }
        }
        notification?.let(observer)
    }

    private fun updateLatestRoomLocked(room: RoomState) {
        val previousCurrentId = latestRoom?.current?.trackId
        val nextCurrentId = room.current?.trackId
        if (!currentInitialized || previousCurrentId != nextCurrentId) {
            currentInitialized = true
            currentGeneration++
            endedConsumedGeneration = null
        }
        latestRoom = room
    }

    override fun close() {
        val request = synchronized(this) {
            if (closed) return
            closed = true
            nextToken++
            pending = null
            val active = reconcileRequest
            reconcileRequest = null
            reconcileInFlight = false
            active
        }
        request?.cancel()
    }
}

class AsyncRoomAdvanceCommand(
    private val api: RoomApiClient,
    private val executor: Executor,
) : QueueAdvanceCommand {
    override fun skip(
        roomCode: String,
        hostToken: String,
        callback: (QueueAdvanceCommandResult) -> Unit,
    ) {
        executor.execute {
            val result = try {
                api.skip(roomCode, hostToken)
                QueueAdvanceCommandResult.Success
            } catch (failure: RoomApiException) {
                if (failure.logCause == QMixLogCause.HTTP_STATUS) {
                    QueueAdvanceCommandResult.Rejected
                } else {
                    QueueAdvanceCommandResult.Indeterminate
                }
            } catch (_: Exception) {
                QueueAdvanceCommandResult.Rejected
            }
            callback(result)
        }
    }
}
