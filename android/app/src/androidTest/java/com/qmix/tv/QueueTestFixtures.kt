package com.qmix.tv

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine

/** A test-owned, ready single-thread mutation lane; close after cancelling its owners. */
internal class TestMutationLane : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val thread = executor.submit<Thread> { Thread.currentThread() }.get()
    private val dispatcher = executor.asCoroutineDispatcher()
    val context = QueueMutationContext(dispatcher) { Thread.currentThread() === thread }

    override fun close() = dispatcher.close()
}

/** The Android main looper is an existing dedicated mutation lane (not test-owned). */
internal fun mainTestMutationContext() = QueueMutationContext(Dispatchers.Main.immediate) {
    Looper.myLooper() === Looper.getMainLooper()
}

/** Wait for every earlier lane task, without joining a blocked lane forever. */
internal fun QueueMutationContext.awaitLaneIdleForTest(timeoutMs: Long = 5_000) {
    val mainLane = dispatcher == Dispatchers.Main || dispatcher == Dispatchers.Main.immediate
    if (mainLane) {
        if (Looper.myLooper() === Looper.getMainLooper()) return
    } else if (isOnContext()) {
        return
    }
    val reached = CountDownLatch(1)
    if (mainLane) {
        if (!Handler(Looper.getMainLooper()).post { reached.countDown() }) {
            throw AssertionError("main mutation lane rejected test barrier")
        }
    } else {
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { reached.countDown() })
    }
    if (!reached.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        val lane = if (mainLane) "main mutation lane" else "queue mutation lane"
        throw AssertionError("$lane did not become idle within ${timeoutMs}ms")
    }
}

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
    mutationContext: QueueMutationContext = mainTestMutationContext(),
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
