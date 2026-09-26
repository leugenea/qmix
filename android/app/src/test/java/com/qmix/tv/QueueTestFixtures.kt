package com.qmix.tv

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

/** Callback-controlled fixtures for existing host/playback contracts, not production adapters. */
internal fun interface TestQueueCommand {
    fun skip(roomCode: String, hostToken: String, callback: (QueueAdvanceCommandResult) -> Unit)
}

internal fun testQueueCommand(block: (String, String, (QueueAdvanceCommandResult) -> Unit) -> Unit) =
    TestQueueCommand(block)

internal fun testQueueCoordinator(
    parentScope: CoroutineScope,
    roomCode: String,
    hostToken: String,
    command: TestQueueCommand,
    reconciler: TestRoomFetcher,
    observer: (QueueAdvancementState) -> Unit = {},
    // Pure callback-driven unit tests use the same test thread throughout. Host/session
    // integration tests must instead pass a serialized context owned by their test.
    mutationContext: QueueMutationContext = QueueMutationContext(Dispatchers.Unconfined) { true },
): QueueAdvancementCoordinator = QueueAdvancementCoordinator(
    roomCode, hostToken,
    QueueAdvanceCommand { code, token -> suspendCancellableCoroutine { continuation ->
        command.skip(code, token) { if (continuation.isActive) continuation.resume(it) }
    } },
    QueueRoomReconciler { code -> suspendCancellableCoroutine { continuation ->
        val request = reconciler.fetch(code) { if (continuation.isActive) continuation.resume(it) }
        continuation.invokeOnCancellation { request.cancel() }
        if (!continuation.isActive) request.cancel()
    } }, observer, parentScope, mutationContext,
)

/** Owns a real mutation thread for tests with IO/collection/callback-thread entry points.
 * Construct before the coordinator; close the coordinator before this context in finally.
 */
internal class SerializedTestMutationContext(threadName: String = "test-queue-mutation") : AutoCloseable {
    private val owner = AtomicReference<Thread>()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, threadName) }

    init {
        val ready = CountDownLatch(1)
        executor.execute { owner.set(Thread.currentThread()); ready.countDown() }
        try {
            check(ready.await(5, TimeUnit.SECONDS)) { "mutation thread did not start" }
        } catch (failure: Throwable) {
            executor.shutdownNow()
            throw failure
        }
    }

    private val dispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== owner.get()
        override fun dispatch(context: CoroutineContext, block: Runnable) { executor.execute(block) }
    }

    val mutationContext = QueueMutationContext(dispatcher) { Thread.currentThread() === owner.get() }

    override fun close() {
        executor.shutdown()
        try {
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "mutation thread did not terminate" }
        } finally {
            if (!executor.isTerminated) executor.shutdownNow()
        }
    }
}

internal fun interface Cancelable {
    fun cancel()
}

/** Test-only callback fixture; no callback adapter is shipped in the application. */
internal fun interface TestRoomFetcher {
    fun fetch(roomCode: String, callback: (RoomFetchResult) -> Unit): Cancelable
}

internal suspend fun TestRoomFetcher.fetchRoom(roomCode: String): RoomFetchResult =
    suspendCancellableCoroutine { continuation ->
        val request = fetch(roomCode) { if (continuation.isActive) continuation.resume(it) }
        continuation.invokeOnCancellation { request.cancel() }
        if (!continuation.isActive) request.cancel()
    }
