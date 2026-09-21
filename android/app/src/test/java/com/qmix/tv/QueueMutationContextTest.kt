package com.qmix.tv

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueMutationContextTest {
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
