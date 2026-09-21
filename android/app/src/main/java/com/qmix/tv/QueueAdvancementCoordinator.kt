package com.qmix.tv

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

sealed interface QueueAdvanceCommandResult {
    data object Success : QueueAdvanceCommandResult
    data object Indeterminate : QueueAdvanceCommandResult
    data object Rejected : QueueAdvanceCommandResult
}

fun interface QueueAdvanceCommand {
    suspend fun skip(roomCode: String, hostToken: String): QueueAdvanceCommandResult
}

fun interface QueueRoomReconciler {
    suspend fun fetch(roomCode: String): RoomFetchResult
}

/** Synchronous admission and coroutine resumptions share the injected immediate mutation context.
 * The host's temporary callback callers may enter from another thread until qmix#182.
 */
class QueueMutationContext(
    val dispatcher: CoroutineDispatcher,
    private val isCurrent: () -> Boolean,
) {
    fun <T> run(action: () -> T): T =
        if (isCurrent()) action() else runBlocking(dispatcher) { action() }
}

enum class QueueAdvanceOutcome { ADVANCED, RECONCILED_NO_ADVANCE, REJECTED }

data class QueueAdvancementState(
    val pending: Boolean = false,
    val lastOutcome: QueueAdvanceOutcome? = null,
)

/** qmix#178: unresolved business state outlives a failed GET, never its foreground/session owner. */
class QueueAdvancementCoordinator(
    private val roomCode: String,
    private val hostToken: String,
    private val command: QueueAdvanceCommand,
    private val reconciler: QueueRoomReconciler,
    private val observer: (QueueAdvancementState) -> Unit = {},
    parentScope: CoroutineScope,
    private val mutationContext: QueueMutationContext,
) : AutoCloseable {
    private class Pending(val previousCurrentId: String?, var needsReconciliation: Boolean = false)
    private class Selection(val trackId: String?, var endedConsumed: Boolean = false)

    private val sessionJob = SupervisorJob(requireNotNull(parentScope.coroutineContext[Job]))
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob + mutationContext.dispatcher)
    private var latestRoom: RoomState? = null
    private var selection: Selection? = null
    private var pending: Pending? = null
    private var operation: Job? = null
    private var foregroundReady = true

    @Volatile
    var state = QueueAdvancementState()
        private set

    fun onAuthoritativeRoom(room: RoomState): Unit = mutationContext.run {
        if (!sessionJob.isActive || !foregroundReady || room.code != roomCode) return@run
        updateRoom(room)
        if (pending?.needsReconciliation == true && operation?.isActive != true) startOperation(commandRequired = false)
    }

    fun onForegroundLost(): Unit = mutationContext.run {
        if (!sessionJob.isActive || !foregroundReady) return@run
        foregroundReady = false
        pending = null
        operation?.cancel()
        operation = null
        if (state.pending) publish(state.copy(pending = false))
    }

    fun onForegroundReconciled(room: RoomState): Unit = mutationContext.run {
        if (!sessionJob.isActive || foregroundReady || room.code != roomCode) return@run
        updateRoom(room)
        foregroundReady = true
    }

    fun requestExplicitAdvance(): Boolean = mutationContext.run { requestAdvance(null) }
    fun onPlaybackEnded(trackId: String): Boolean = mutationContext.run { requestAdvance(trackId) }

    private fun requestAdvance(endedTrackId: String?): Boolean {
        if (!sessionJob.isActive || !foregroundReady || pending != null) return false
        val room = latestRoom ?: return false
        if (room.queue.isEmpty()) return false
        if (endedTrackId != null) {
            val current = selection ?: return false
            if (current.trackId != endedTrackId || current.endedConsumed) return false
            current.endedConsumed = true
        }
        val unresolved = Pending(room.current?.trackId)
        pending = unresolved
        val job = createOperation(unresolved, commandRequired = true)
        operation = job
        publish(state.copy(pending = true))
        // Reentrant lifecycle observers cancel this admission's owned Job before it can dispatch.
        return job.start()
    }

    private fun startOperation(commandRequired: Boolean) {
        val unresolved = pending ?: return
        // Assign before starting: immediate completion/reentrant observers cannot overwrite a newer Job.
        val job = createOperation(unresolved, commandRequired)
        operation = job
        job.start()
    }

    private fun createOperation(unresolved: Pending, commandRequired: Boolean): Job =
        scope.launch(start = CoroutineStart.LAZY) {
            if (commandRequired) {
                val result = try {
                    command.skip(roomCode, hostToken)
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (_: Exception) {
                    QueueAdvanceCommandResult.Indeterminate
                }
                coroutineContext.ensureActive()
                if (result == QueueAdvanceCommandResult.Rejected) {
                    settle(QueueAdvanceOutcome.REJECTED)
                    return@launch
                }
                unresolved.needsReconciliation = true
            }
            val result = try {
                reconciler.fetch(roomCode)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                RoomFetchResult.Failure
            }
            coroutineContext.ensureActive()
            when (result) {
                is RoomFetchResult.Success -> if (result.room.code == roomCode) {
                    updateRoom(result.room)
                    settle(if (unresolved.previousCurrentId != result.room.current?.trackId) {
                        QueueAdvanceOutcome.ADVANCED
                    } else {
                        QueueAdvanceOutcome.RECONCILED_NO_ADVANCE
                    })
                }
                RoomFetchResult.Missing -> settle(QueueAdvanceOutcome.REJECTED)
                RoomFetchResult.Failure -> Unit
            }
            // Keep a completed Job rather than cleanup that could clear a newer operation.
        }

    private fun settle(outcome: QueueAdvanceOutcome) {
        pending = null
        publish(QueueAdvancementState(lastOutcome = outcome))
    }

    private fun publish(next: QueueAdvancementState) {
        state = next
        observer(next)
    }

    private fun updateRoom(room: RoomState) {
        if (selection == null || selection?.trackId != room.current?.trackId) {
            selection = Selection(room.current?.trackId)
        }
        latestRoom = room
    }

    override fun close(): Unit = mutationContext.run {
        sessionJob.cancel()
        pending = null
        operation = null
    }
}

class RoomAdvanceCommand(private val api: RoomApiClient) : QueueAdvanceCommand {
    override suspend fun skip(roomCode: String, hostToken: String): QueueAdvanceCommandResult = try {
        api.skip(roomCode, hostToken)
        QueueAdvanceCommandResult.Success
    } catch (canceled: CancellationException) {
        throw canceled
    } catch (failure: RoomApiException) {
        if (failure.logCause == QMixLogCause.HTTP_STATUS) QueueAdvanceCommandResult.Rejected
        else QueueAdvanceCommandResult.Indeterminate
    } catch (_: IllegalArgumentException) {
        // Invalid request construction cannot have sent the POST.
        QueueAdvanceCommandResult.Rejected
    }
}
