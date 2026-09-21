package com.qmix.tv

import kotlin.coroutines.resume
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
    reconciler: RoomStateFetcher,
    observer: (QueueAdvancementState) -> Unit = {},
): QueueAdvancementCoordinator = QueueAdvancementCoordinator(
    roomCode, hostToken,
    QueueAdvanceCommand { code, token -> suspendCancellableCoroutine { continuation ->
        command.skip(code, token) { if (continuation.isActive) continuation.resume(it) }
    } },
    QueueRoomReconciler { code -> suspendCancellableCoroutine { continuation ->
        val request = reconciler.fetch(code) { if (continuation.isActive) continuation.resume(it) }
        continuation.invokeOnCancellation { request.cancel() }
        if (!continuation.isActive) request.cancel()
    } }, observer, parentScope, QueueMutationContext(Dispatchers.Unconfined) { true },
)
