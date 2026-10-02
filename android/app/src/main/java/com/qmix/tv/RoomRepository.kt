package com.qmix.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

sealed interface RoomEventStreamEvent {
    data object Opened : RoomEventStreamEvent
    data class Event(val type: String?) : RoomEventStreamEvent
    data object Closed : RoomEventStreamEvent
    data class Failure(val statusCode: Int?) : RoomEventStreamEvent
}

fun interface RoomEventStreamFactory {
    fun observe(roomCode: String): Flow<RoomEventStreamEvent>
}

fun interface RoomRepository {
    fun observe(roomCode: String): Flow<RoomSyncState>
}

class SequentialRoomRepository(
    private val fetchRoom: suspend (String) -> RoomFetchResult,
    private val eventStreams: RoomEventStreamFactory,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val logger: QMixComponentLogger =
        QMixComponentLogger.noOp(QMixLogComponent.ROOM_SYNC_SSE_RECONNECT),
) : RoomRepository {
    private companion object {
        const val PERIODIC_REFRESH_MILLIS = 15_000L
        val CHANGE_EVENTS = setOf("queue_snapshot", "queue_updated", "track_changed", "player_state")
    }

    private sealed interface Command {
        data object Refresh : Command
        data class RefreshCompleted(val result: RoomFetchResult) : Command
        data class ConnectionChanged(
            val connection: LiveConnection,
            val refresh: Boolean = false,
        ) : Command
        data object Missing : Command
    }

    override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
        emit(RoomSyncState.Active(roomCode, null, Freshness.LOADING, LiveConnection.CONNECTING))
        coroutineScope {
            val commands = Channel<Command>(4)
            var room: RoomState? = null
            var freshness = Freshness.LOADING
            var connection = LiveConnection.CONNECTING
            var refreshJob: Job? = null
            var refreshPending = false

            fun startRefresh() {
                refreshJob = launch {
                    val result = try {
                        fetchRoom(roomCode)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        RoomFetchResult.Failure
                    }
                    commands.send(Command.RefreshCompleted(result))
                }
            }

            fun admitRefresh(requested: Boolean, pending: Boolean): Boolean {
                if (requested) {
                    if (refreshJob != null) return true
                    startRefresh()
                }
                return pending
            }

            fun refreshResultState(result: RoomFetchResult, currentConnection: LiveConnection): RoomSyncState =
                when (result) {
                    is RoomFetchResult.Success -> {
                        room = result.room
                        freshness = Freshness.FRESH
                        RoomSyncState.Active(roomCode, room, freshness, currentConnection)
                    }
                    RoomFetchResult.Failure -> {
                        freshness = Freshness.STALE
                        RoomSyncState.Active(roomCode, room, freshness, currentConnection)
                    }
                    RoomFetchResult.Missing -> RoomSyncState.Missing(roomCode)
                }

            commands.send(Command.Refresh)

            val periodicJob = launch {
                while (currentCoroutineContext().isActive) {
                    delay(PERIODIC_REFRESH_MILLIS)
                    commands.send(Command.Refresh)
                }
            }
            val eventJob = launch {
                collectEvents(roomCode, commands)
            }

            try {
                var terminal = false
                while (!terminal) {
                    when (val command = commands.receive()) {
                        Command.Refresh -> {
                            refreshPending = admitRefresh(true, refreshPending)
                        }
                        is Command.RefreshCompleted -> {
                            // qmix#308: result handoff does not complete the producer's owned subtree.
                            // The send needs no reducer ACK, so joining here cannot hold up its return.
                            refreshJob?.join()
                            refreshJob = null
                            emit(refreshResultState(command.result, connection))
                            if (command.result == RoomFetchResult.Missing) terminal = true
                            if (!terminal && refreshPending) {
                                refreshPending = false
                                startRefresh()
                            }
                        }
                        is Command.ConnectionChanged -> {
                            connection = command.connection
                            emit(RoomSyncState.Active(roomCode, room, freshness, connection))
                            refreshPending = admitRefresh(command.refresh, refreshPending)
                        }
                        Command.Missing -> {
                            emit(RoomSyncState.Missing(roomCode))
                            terminal = true
                        }
                    }
                }
            } finally {
                refreshJob?.cancelAndJoin()
                eventJob.cancelAndJoin()
                periodicJob.cancelAndJoin()
                commands.close()
            }
        }
    }

    private suspend fun collectEvents(roomCode: String, commands: Channel<Command>) {
        var connectionAttempt = 0
        var retryAttempt = 0
        var warningEmitted = false
        while (currentCoroutineContext().isActive) {
            val refreshOnOpen = connectionAttempt++ > 0
            var statusCode: Int? = null
            try {
                eventStreams.observe(roomCode).collect { event ->
                    when (event) {
                        RoomEventStreamEvent.Opened -> {
                            retryAttempt = 0
                            commands.send(
                                Command.ConnectionChanged(
                                    LiveConnection.CONNECTED,
                                    refresh = refreshOnOpen,
                                ),
                            )
                        }
                        is RoomEventStreamEvent.Event -> {
                            if (event.type in CHANGE_EVENTS) commands.send(Command.Refresh)
                        }
                        RoomEventStreamEvent.Closed -> Unit
                        is RoomEventStreamEvent.Failure -> statusCode = event.statusCode
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                statusCode = null
            }

            if (statusCode == 404) {
                commands.send(Command.Missing)
                return
            }
            val cause = if (statusCode == null) QMixLogCause.NETWORK else QMixLogCause.HTTP_STATUS
            if (warningEmitted) {
                logger.debug(QMixLogOperation.SSE_CONNECTION, cause)
            } else {
                warningEmitted = true
                logger.warn(QMixLogOperation.SSE_CONNECTION, cause)
            }
            commands.send(Command.ConnectionChanged(LiveConnection.RECONNECTING))
            val reconnectDelay = backoff.delayMillis(retryAttempt++)
            logger.info(QMixLogOperation.RECONNECT)
            delay(reconnectDelay)
        }
    }
}
