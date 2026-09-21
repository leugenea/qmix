package com.qmix.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoroutineQueueTest {
    private val room = RoomState("ABCD", CurrentTrack("one", 0, "playing", "One", ""),
        listOf(QueuedTrack("two", "https://example/two", "Two", "", 1, "fixture")))

    /** qmix#178: lifecycle changes inside an observer cancel the original admission's Job. */
    @Test
    fun reentrant_foreground_replacement_cannot_dispatch_two_posts() = runTest {
        var posts = 0
        var cancellations = 0
        var replaced = false
        var replacementAccepted = false
        lateinit var coordinator: QueueAdvancementCoordinator
        coordinator = QueueAdvancementCoordinator("ABCD", "fixture", QueueAdvanceCommand { _, _ ->
            posts++
            try { awaitCancellation() } finally { cancellations++ }
        }, QueueRoomReconciler { error("Canceled POST must not reconcile") }, observer = { state ->
            if (state.pending && !replaced) {
                replaced = true
                coordinator.onForegroundLost()
                coordinator.onForegroundReconciled(room)
                replacementAccepted = coordinator.requestExplicitAdvance()
            }
        }, parentScope = backgroundScope,
            mutationContext = QueueMutationContext(StandardTestDispatcher(testScheduler)) { true })
        coordinator.onAuthoritativeRoom(room)

        val originalAccepted = coordinator.requestExplicitAdvance()
        runCurrent()

        assertTrue(replacementAccepted)
        assertEquals(1, posts)
        assertFalse(originalAccepted)
        coordinator.onForegroundLost()
        runCurrent()
        assertEquals(1, cancellations)
        assertFalse(coordinator.state.pending)
    }

    @Test
    fun admission_is_synchronous_and_post_and_reconciliation_are_serialized() = runTest {
        val post = CompletableDeferred<QueueAdvanceCommandResult>()
        val get = CompletableDeferred<RoomFetchResult>()
        var posts = 0
        var gets = 0
        val coordinator = QueueAdvancementCoordinator("ABCD", "fixture", QueueAdvanceCommand { _, _ ->
            posts++; post.await()
        }, QueueRoomReconciler { gets++; get.await() }, parentScope = backgroundScope,
            mutationContext = QueueMutationContext(StandardTestDispatcher(testScheduler)) { true })
        coordinator.onAuthoritativeRoom(room)
        assertTrue(coordinator.requestExplicitAdvance())
        assertTrue(coordinator.state.pending)
        assertFalse(coordinator.requestExplicitAdvance())
        assertFalse(coordinator.onPlaybackEnded("one"))
        runCurrent()
        assertEquals(1, posts)
        assertEquals(0, gets)
        post.complete(QueueAdvanceCommandResult.Indeterminate)
        runCurrent()
        coordinator.onAuthoritativeRoom(room.copy(current = room.current!!.copy(trackId = "two")))
        assertTrue(coordinator.state.pending)
        assertEquals(1, gets)
        get.complete(RoomFetchResult.Success(room))
        runCurrent()
        assertFalse(coordinator.state.pending)
        assertEquals(QueueAdvanceOutcome.RECONCILED_NO_ADVANCE, coordinator.state.lastOutcome)
        assertEquals(1, posts)
    }

    @Test
    fun rejected_command_does_not_get_and_ended_is_consumed_per_selection() = runTest {
        var posts = 0
        val coordinator = QueueAdvancementCoordinator("ABCD", "fixture", QueueAdvanceCommand { _, _ ->
            posts++; QueueAdvanceCommandResult.Rejected
        }, QueueRoomReconciler { error("Rejected POST must not GET") }, parentScope = backgroundScope,
            mutationContext = QueueMutationContext(StandardTestDispatcher(testScheduler)) { true })
        coordinator.onAuthoritativeRoom(room)
        assertTrue(coordinator.onPlaybackEnded("one"))
        runCurrent()
        assertFalse(coordinator.state.pending)
        assertEquals(QueueAdvanceOutcome.REJECTED, coordinator.state.lastOutcome)
        coordinator.onAuthoritativeRoom(room.copy(current = room.current!!.copy(positionSeconds = 10)))
        assertFalse(coordinator.onPlaybackEnded("one"))
        coordinator.onAuthoritativeRoom(room.copy(current = null))
        coordinator.onAuthoritativeRoom(room)
        assertTrue(coordinator.onPlaybackEnded("one"))
        runCurrent()
        assertEquals(2, posts)
    }

    @Test
    fun failed_get_leaves_pending_and_later_signal_launches_only_a_new_get() = runTest {
        var posts = 0
        var gets = 0
        val coordinator = QueueAdvancementCoordinator("ABCD", "fixture", QueueAdvanceCommand { _, _ ->
            posts++; QueueAdvanceCommandResult.Success
        }, QueueRoomReconciler {
            gets++
            if (gets == 1) RoomFetchResult.Failure else RoomFetchResult.Success(room)
        }, parentScope = backgroundScope,
            mutationContext = QueueMutationContext(StandardTestDispatcher(testScheduler)) { true })
        coordinator.onAuthoritativeRoom(room)
        coordinator.requestExplicitAdvance()
        runCurrent()
        assertTrue(coordinator.state.pending)
        coordinator.onAuthoritativeRoom(room)
        assertTrue(coordinator.state.pending)
        runCurrent()
        assertFalse(coordinator.state.pending)
        assertEquals(1, posts)
        assertEquals(2, gets)
    }

    @Test
    fun canceled_old_get_and_its_cleanup_cannot_change_new_operation() = runTest {
        val oldDelivery = CompletableDeferred<Unit>()
        val newDelivery = CompletableDeferred<RoomFetchResult>()
        var gets = 0
        var oldFinished = false
        val coordinator = QueueAdvancementCoordinator("ABCD", "fixture",
            QueueAdvanceCommand { _, _ -> QueueAdvanceCommandResult.Success }, QueueRoomReconciler {
                gets++
                if (gets == 1) {
                    try {
                        withContext(NonCancellable) { oldDelivery.await() }
                        RoomFetchResult.Success(room.copy(current = null))
                    } finally { oldFinished = true }
                } else newDelivery.await()
            }, parentScope = backgroundScope,
            mutationContext = QueueMutationContext(StandardTestDispatcher(testScheduler)) { true })
        coordinator.onAuthoritativeRoom(room)
        coordinator.requestExplicitAdvance()
        runCurrent()
        coordinator.onForegroundLost()
        assertFalse(coordinator.requestExplicitAdvance())
        coordinator.onForegroundReconciled(room)
        assertTrue(coordinator.requestExplicitAdvance())
        runCurrent()
        oldDelivery.complete(Unit)
        runCurrent()
        assertTrue(oldFinished)
        assertTrue(coordinator.state.pending)
        assertNull(coordinator.state.lastOutcome)
        newDelivery.complete(RoomFetchResult.Success(room))
        runCurrent()
        assertEquals(QueueAdvanceOutcome.RECONCILED_NO_ADVANCE, coordinator.state.lastOutcome)
    }

    @Test
    fun foreground_and_session_cancellation_reach_command_and_reconciliation() = runTest {
        var postCanceled = false
        var getCanceled = false
        var calls = 0
        val coordinator = QueueAdvancementCoordinator("ABCD", "fixture", QueueAdvanceCommand { _, _ ->
            if (++calls == 1) try { awaitCancellation() } finally { postCanceled = true }
            QueueAdvanceCommandResult.Success
        }, QueueRoomReconciler {
            try { awaitCancellation() } finally { getCanceled = true }
        }, parentScope = backgroundScope,
            mutationContext = QueueMutationContext(StandardTestDispatcher(testScheduler)) { true })
        coordinator.onAuthoritativeRoom(room)
        coordinator.requestExplicitAdvance()
        runCurrent()
        coordinator.onForegroundLost()
        runCurrent()
        assertTrue(postCanceled)
        coordinator.onForegroundReconciled(room)
        coordinator.requestExplicitAdvance()
        runCurrent()
        coordinator.close()
        runCurrent()
        assertTrue(getCanceled)
        assertFalse(coordinator.requestExplicitAdvance())
    }
}
