package com.qmix.tv

import android.os.Looper
import java.util.concurrent.Executors
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
