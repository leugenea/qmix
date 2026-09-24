package com.qmix.tv

import java.util.concurrent.Executors
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueMutationContextTest {
    /** qmix#182: teardown must remove queued mutations before the worker resumes. */
    @Test
    fun cancelled_worker_does_not_execute_queued_mutation() = runBlocking {
        val pending = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        }
        val mutation = QueueMutationContext(dispatcher)
        var published = false
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            mutation.runFromWorker { published = true }
        }
        assertEquals(1, pending.size)
        worker.cancel()
        worker.join()
        pending.removeFirst().run()
        assertFalse("cancelled session published a stale mutation", published)
    }

    /** qmix#182: a failed dispatched mutation propagates to its worker, not into the dispatcher. */
    @Test
    fun worker_dispatch_propagates_action_failure_and_can_dispatch_again() = runBlocking {
        val pending = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        }
        val mutation = QueueMutationContext(dispatcher)
        val failure = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                mutation.runFromWorker { throw IllegalStateException("rejected mutation") }
                org.junit.Assert.fail("failed mutation was accepted")
            } catch (expected: IllegalStateException) {
                assertEquals("rejected mutation", expected.message)
            }
        }
        assertEquals(1, pending.size)
        pending.removeFirst().run()
        failure.join()
        assertTrue(failure.isCompleted)
        val accepted = launch(start = CoroutineStart.UNDISPATCHED) {
            assertEquals("next", mutation.runFromWorker { "next" })
        }
        assertEquals(1, pending.size)
        pending.removeFirst().run()
        accepted.join()
        assertTrue(accepted.isCompleted)
    }

    @Test
    fun nested_distinct_context_markers_do_not_leak_between_owners() {
        val dispatcher = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = false
            override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        }
        val first = QueueMutationContext(dispatcher)
        val second = QueueMutationContext(dispatcher)
        assertFalse(first.isOnContext())
        first.run {
            assertTrue(first.isOnContext())
            assertFalse(second.isOnContext())
            second.run {
                assertTrue(first.isOnContext())
                assertTrue(second.isOnContext())
            }
            assertTrue(first.isOnContext())
            assertFalse(second.isOnContext())
        }
        assertFalse(first.isOnContext())
        assertFalse(second.isOnContext())
    }

    /** qmix#178: legacy callers retain synchronous admission on the injected immediate context. */
    @Test
    fun foreign_thread_admission_and_reentrant_observers_share_one_immediate_context() {
        val owner = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-queue-mutation").also { owner.set(it) }
        }
        val dispatcher = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== owner.get()
            override fun dispatch(context: CoroutineContext, block: Runnable) { executor.execute(block) }
        }
        val context = QueueMutationContext(dispatcher) { Thread.currentThread() === owner.get() }
        val parent = CoroutineScope(SupervisorJob() + dispatcher)
        val room = RoomState("ABCD", null, listOf(QueuedTrack("one", "https://example/one", "One", "", 1, "fixture")))
        val settled = java.util.concurrent.CountDownLatch(2)
        var commands = 0
        var settlements = 0
        lateinit var coordinator: QueueAdvancementCoordinator
        coordinator = QueueAdvancementCoordinator("ABCD", "fixture", QueueAdvanceCommand { _, _ ->
            assertEquals(owner.get(), Thread.currentThread())
            commands++
            QueueAdvanceCommandResult.Success
        }, QueueRoomReconciler { RoomFetchResult.Success(room) }, observer = { state ->
            assertEquals(owner.get(), Thread.currentThread())
            if (!state.pending) {
                if (++settlements == 1) assertTrue(coordinator.requestExplicitAdvance())
                settled.countDown()
            }
        }, parentScope = parent, mutationContext = context)
        try {
            coordinator.onAuthoritativeRoom(room)
            assertTrue(coordinator.requestExplicitAdvance())
            assertTrue(settled.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(2, commands)
            assertEquals(2, settlements)
            assertFalse(coordinator.state.pending)
            parent.cancel()
            assertFalse(coordinator.requestExplicitAdvance())
        } finally {
            coordinator.close()
            parent.cancel()
            executor.shutdownNow()
        }
    }
}
