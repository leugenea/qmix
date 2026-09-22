package com.qmix.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueAdvancementCoordinatorTest {
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @org.junit.After
    fun cancelQueueScope() { queueScope.cancel() }

    private val command = RecordingAdvanceCommand()
    private val reconciler = RecordingReconciler()
    private val observed = mutableListOf<QueueAdvancementState>()
    private val coordinator = testQueueCoordinator(
        queueScope,
        roomCode = "ABCD",
        hostToken = "host-secret",
        command = command,
        reconciler = reconciler,
        observer = observed::add,
    )

    @Test
    fun explicit_start_sends_one_command_and_blocks_double_press() {
        coordinator.onAuthoritativeRoom(room(currentId = null, queueIds = listOf("one")))

        assertTrue(coordinator.requestExplicitAdvance())
        assertFalse(coordinator.requestExplicitAdvance())

        assertEquals(1, command.calls.size)
        assertEquals("ABCD", command.calls.single().roomCode)
        assertEquals("host-secret", command.calls.single().hostToken)
        assertTrue(coordinator.state.pending)
    }

    @Test
    fun foreground_recovery_blocks_commands_and_invalidates_pre_background_completion() {
        coordinator.onAuthoritativeRoom(room(currentId = "current", queueIds = listOf("next")))
        assertTrue(coordinator.requestExplicitAdvance())

        coordinator.onForegroundLost()
        command.complete(QueueAdvanceCommandResult.Success)
        coordinator.onAuthoritativeRoom(room(currentId = "stale", queueIds = listOf("next")))

        assertFalse(coordinator.requestExplicitAdvance())
        assertEquals(0, reconciler.calls)
        assertFalse(coordinator.state.pending)

        coordinator.onForegroundReconciled(room(currentId = "replacement", queueIds = listOf("next")))

        assertTrue(coordinator.requestExplicitAdvance())
        assertEquals(2, command.calls.size)
    }

    @Test
    fun next_and_ended_for_the_same_current_share_one_command() {
        coordinator.onAuthoritativeRoom(room(currentId = "current", queueIds = listOf("next")))

        assertTrue(coordinator.requestExplicitAdvance())
        assertFalse(coordinator.onPlaybackEnded("current"))

        assertEquals(1, command.calls.size)
    }

    @Test
    fun ended_is_consumed_once_per_authoritative_selection() {
        coordinator.onAuthoritativeRoom(room(currentId = "current", queueIds = listOf("next")))

        assertTrue(coordinator.onPlaybackEnded("current"))
        command.complete(QueueAdvanceCommandResult.Indeterminate)
        reconciler.complete(RoomFetchResult.Success(room(currentId = "current", queueIds = listOf("next"))))

        assertFalse(coordinator.onPlaybackEnded("current"))
        assertEquals(1, command.calls.size)
        assertEquals(QueueAdvanceOutcome.RECONCILED_NO_ADVANCE, coordinator.state.lastOutcome)
    }

    @Test
    fun lost_response_reconciles_advanced_state_without_retrying_post() {
        coordinator.onAuthoritativeRoom(room(currentId = "current", queueIds = listOf("next")))
        coordinator.requestExplicitAdvance()

        command.complete(QueueAdvanceCommandResult.Indeterminate)
        assertEquals(1, reconciler.calls)
        reconciler.complete(RoomFetchResult.Success(room(currentId = "next", queueIds = emptyList())))

        assertEquals(1, command.calls.size)
        assertFalse(coordinator.state.pending)
        assertEquals(QueueAdvanceOutcome.ADVANCED, coordinator.state.lastOutcome)
    }

    @Test
    fun unchanged_reconciliation_settles_and_allows_a_later_explicit_command() {
        val unchanged = room(currentId = "current", queueIds = listOf("next"))
        coordinator.onAuthoritativeRoom(unchanged)
        coordinator.requestExplicitAdvance()
        command.complete(QueueAdvanceCommandResult.Indeterminate)

        reconciler.complete(RoomFetchResult.Success(unchanged))

        assertEquals(QueueAdvanceOutcome.RECONCILED_NO_ADVANCE, coordinator.state.lastOutcome)
        assertFalse(coordinator.state.pending)
        assertTrue(coordinator.requestExplicitAdvance())
        assertEquals(2, command.calls.size)
    }

    @Test
    fun failed_reconcile_stays_pending_until_a_fresh_authoritative_snapshot_arrives() {
        val initial = room(currentId = "current", queueIds = listOf("next"))
        coordinator.onAuthoritativeRoom(initial)
        coordinator.requestExplicitAdvance()
        command.complete(QueueAdvanceCommandResult.Success)

        reconciler.complete(RoomFetchResult.Failure)
        assertTrue(coordinator.state.pending)

        coordinator.onAuthoritativeRoom(room(currentId = "next", queueIds = emptyList()))
        assertEquals(2, reconciler.calls)
        reconciler.complete(RoomFetchResult.Success(room(currentId = "next", queueIds = emptyList())), index = 1)
        assertFalse(coordinator.state.pending)
        assertEquals(QueueAdvanceOutcome.ADVANCED, coordinator.state.lastOutcome)
    }

    @Test
    fun unrelated_repository_snapshot_cannot_settle_a_pending_reconciliation() {
        val initial = room(currentId = "current", queueIds = listOf("next"))
        coordinator.onAuthoritativeRoom(initial)
        coordinator.requestExplicitAdvance()
        command.complete(QueueAdvanceCommandResult.Indeterminate)

        coordinator.onAuthoritativeRoom(initial)

        assertTrue(coordinator.state.pending)
        assertEquals(null, coordinator.state.lastOutcome)
        assertEquals(1, reconciler.calls)
    }

    @Test
    fun stale_reconcile_callback_cannot_settle_a_newer_command() {
        val initial = room(currentId = "current", queueIds = listOf("next"))
        coordinator.onAuthoritativeRoom(initial)
        coordinator.requestExplicitAdvance()
        command.complete(QueueAdvanceCommandResult.Indeterminate)
        reconciler.complete(RoomFetchResult.Success(initial), index = 0)
        assertTrue(coordinator.requestExplicitAdvance())
        command.complete(QueueAdvanceCommandResult.Indeterminate, index = 1)

        reconciler.complete(RoomFetchResult.Success(room(currentId = "next", queueIds = emptyList())), index = 0)

        assertTrue(coordinator.state.pending)
        assertEquals(QueueAdvanceOutcome.RECONCILED_NO_ADVANCE, coordinator.state.lastOutcome)
    }

    @Test
    fun closing_from_the_pending_observer_prevents_command_dispatch() {
        val recordedCommand = RecordingAdvanceCommand()
        lateinit var closingCoordinator: QueueAdvancementCoordinator
        closingCoordinator = testQueueCoordinator(
        queueScope,
            roomCode = "ABCD",
            hostToken = "host-secret",
            command = recordedCommand,
            reconciler = RecordingReconciler(),
            observer = { state -> if (state.pending) closingCoordinator.close() },
        )
        closingCoordinator.onAuthoritativeRoom(room(currentId = null, queueIds = listOf("one")))

        assertFalse(closingCoordinator.requestExplicitAdvance())
        assertTrue(recordedCommand.calls.isEmpty())
    }

    @Test
    fun synchronous_reconciler_failure_remains_pending_without_escaping() {
        val throwingReconciler = RecordingReconciler().apply { failure = IllegalStateException("rejected") }
        val safeCoordinator = testQueueCoordinator(
        queueScope,
            roomCode = "ABCD",
            hostToken = "host-secret",
            command = command,
            reconciler = throwingReconciler,
        )
        safeCoordinator.onAuthoritativeRoom(room(currentId = "current", queueIds = listOf("next")))
        safeCoordinator.requestExplicitAdvance()

        command.complete(QueueAdvanceCommandResult.Indeterminate)

        assertTrue(safeCoordinator.state.pending)
    }

    @Test
    fun synchronous_reconciliation_completion_settles_and_cancels_the_returned_request() {
        val synchronous = SynchronousReconciler(
            RoomFetchResult.Success(room(currentId = "next", queueIds = emptyList())),
        )
        val guardedCoordinator = testQueueCoordinator(
        queueScope,
            roomCode = "ABCD",
            hostToken = "host-secret",
            command = command,
            reconciler = synchronous,
        )
        guardedCoordinator.onAuthoritativeRoom(room(currentId = "current", queueIds = listOf("next")))
        guardedCoordinator.requestExplicitAdvance()

        command.complete(QueueAdvanceCommandResult.Success)

        assertEquals(QueueAdvanceOutcome.ADVANCED, guardedCoordinator.state.lastOutcome)
        assertFalse(guardedCoordinator.state.pending)
        assertTrue(synchronous.returnedRequestCanceled)
    }

    @Test
    fun stale_completion_after_close_cannot_start_reconcile_or_change_state() {
        coordinator.onAuthoritativeRoom(room(currentId = null, queueIds = listOf("one")))
        coordinator.requestExplicitAdvance()
        val stateBeforeClose = coordinator.state

        coordinator.close()
        command.complete(QueueAdvanceCommandResult.Indeterminate)

        assertEquals(0, reconciler.calls)
        assertEquals(stateBeforeClose, coordinator.state)
    }

    @Test
    fun stale_ended_and_empty_queue_are_noops() {
        coordinator.onAuthoritativeRoom(room(currentId = "current", queueIds = emptyList()))

        assertFalse(coordinator.requestExplicitAdvance())
        assertFalse(coordinator.onPlaybackEnded("other"))
        assertFalse(coordinator.onPlaybackEnded("current"))
        assertTrue(command.calls.isEmpty())
    }

    @Test
    fun rejected_command_settles_without_reconciliation() {
        coordinator.onAuthoritativeRoom(room(currentId = "current", queueIds = listOf("next")))
        coordinator.requestExplicitAdvance()

        command.complete(QueueAdvanceCommandResult.Rejected)

        assertFalse(coordinator.state.pending)
        assertEquals(QueueAdvanceOutcome.REJECTED, coordinator.state.lastOutcome)
        assertEquals(0, reconciler.calls)
    }

    @Test
    fun missing_room_during_reconciliation_rejects_the_command_and_allows_a_later_attempt() {
        coordinator.onAuthoritativeRoom(room(currentId = "current", queueIds = listOf("next")))
        assertTrue(coordinator.requestExplicitAdvance())
        command.complete(QueueAdvanceCommandResult.Success)

        reconciler.complete(RoomFetchResult.Missing)

        assertFalse(coordinator.state.pending)
        assertEquals(QueueAdvanceOutcome.REJECTED, coordinator.state.lastOutcome)
        assertTrue(coordinator.requestExplicitAdvance())
        assertEquals(2, command.calls.size)
    }

    @Test
    fun reconciliation_for_another_room_cannot_settle_the_command() {
        val initial = room(currentId = "current", queueIds = listOf("next"))
        coordinator.onAuthoritativeRoom(initial)
        coordinator.requestExplicitAdvance()
        command.complete(QueueAdvanceCommandResult.Success)

        reconciler.complete(RoomFetchResult.Success(initial.copy(code = "OTHER")))

        assertTrue(coordinator.state.pending)
        assertEquals(null, coordinator.state.lastOutcome)
        coordinator.onAuthoritativeRoom(initial)
        assertEquals(2, reconciler.calls)
    }

    @Test
    fun command_dispatch_failure_reconciles_before_permitting_another_advance() {
        command.failure = IllegalStateException("transport failed before callback")
        coordinator.onAuthoritativeRoom(room(currentId = "current", queueIds = listOf("next")))

        assertTrue(coordinator.requestExplicitAdvance())

        assertTrue(coordinator.state.pending)
        assertEquals(1, reconciler.calls)
        command.failure = null
        reconciler.complete(RoomFetchResult.Success(room(currentId = "current", queueIds = listOf("next"))))
        assertEquals(QueueAdvanceOutcome.RECONCILED_NO_ADVANCE, coordinator.state.lastOutcome)
        assertTrue(coordinator.requestExplicitAdvance())
    }

    @Test
    fun foreground_loss_cancels_an_in_flight_reconciliation_and_ignores_its_late_result() {
        coordinator.onAuthoritativeRoom(room(currentId = "current", queueIds = listOf("next")))
        coordinator.requestExplicitAdvance()
        command.complete(QueueAdvanceCommandResult.Success)
        val stateBeforeLoss = coordinator.state

        coordinator.onForegroundLost()
        reconciler.complete(RoomFetchResult.Success(room(currentId = "next", queueIds = emptyList())))

        assertTrue(reconciler.canceled.single())
        assertFalse(coordinator.state.pending)
        assertEquals(stateBeforeLoss.lastOutcome, coordinator.state.lastOutcome)
        assertFalse(coordinator.requestExplicitAdvance())
    }

    /** qmix#178: a failed GET cannot later settle the retry owned by a later signal. */
    @Test
    fun failed_reconciliation_delivery_cannot_settle_a_new_reconciliation() {
        val initial = room(currentId = "current", queueIds = listOf("next"))
        coordinator.onAuthoritativeRoom(initial)
        coordinator.requestExplicitAdvance()
        command.complete(QueueAdvanceCommandResult.Success)
        reconciler.complete(RoomFetchResult.Failure, index = 0)
        coordinator.onAuthoritativeRoom(initial)
        assertEquals(2, reconciler.calls)

        reconciler.complete(RoomFetchResult.Success(initial), index = 0)

        assertTrue(coordinator.state.pending)
        reconciler.complete(RoomFetchResult.Success(initial), index = 1)
        assertFalse(coordinator.state.pending)
    }

    private fun room(currentId: String?, queueIds: List<String>) = RoomState(
        code = "ABCD",
        current = currentId?.let { CurrentTrack(it, 0, "playing", it, "Artist") },
        queue = queueIds.map { QueuedTrack(it, "https://example/$it", it, "Artist", 60, "fixture") },
    )

    private class RecordingAdvanceCommand : TestQueueCommand {
        data class Call(
            val roomCode: String,
            val hostToken: String,
            val callback: (QueueAdvanceCommandResult) -> Unit,
        )

        val calls = mutableListOf<Call>()
        var failure: Throwable? = null

        override fun skip(
            roomCode: String,
            hostToken: String,
            callback: (QueueAdvanceCommandResult) -> Unit,
        ) {
            failure?.let { throw it }
            calls += Call(roomCode, hostToken, callback)
        }

        fun complete(result: QueueAdvanceCommandResult, index: Int = calls.lastIndex) {
            calls[index].callback(result)
        }
    }

    private class RecordingReconciler : TestRoomFetcher {
        var calls = 0
        var failure: Throwable? = null
        val canceled = mutableListOf<Boolean>()
        private val callbacks = mutableListOf<(RoomFetchResult) -> Unit>()

        override fun fetch(roomCode: String, callback: (RoomFetchResult) -> Unit): Cancelable {
            assertEquals("ABCD", roomCode)
            calls++
            failure?.let { throw it }
            callbacks += callback
            canceled += false
            val index = canceled.lastIndex
            return Cancelable { canceled[index] = true }
        }

        fun complete(result: RoomFetchResult, index: Int = callbacks.lastIndex) {
            callbacks[index](result)
        }
    }

    private class SynchronousReconciler(
        private val result: RoomFetchResult,
    ) : TestRoomFetcher {
        var returnedRequestCanceled = false
            private set

        override fun fetch(roomCode: String, callback: (RoomFetchResult) -> Unit): Cancelable {
            assertEquals("ABCD", roomCode)
            callback(result)
            return Cancelable { returnedRequestCanceled = true }
        }
    }

}
