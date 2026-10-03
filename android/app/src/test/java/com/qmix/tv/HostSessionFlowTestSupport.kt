package com.qmix.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

/** Test-only subscription for legacy assertions; production observes [HostSessionController.states]. */
internal fun HostSessionController.collectStatesForTest(
    isolateFailures: Boolean = false, observer: (HostingState) -> Unit,
): AutoCloseable {
    val owner = SupervisorJob()
    val scope = CoroutineScope(owner + Dispatchers.Unconfined)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    scope.launch {
        states.collect { state ->
            try { observer(state) } catch (error: Throwable) {
                if (!isolateFailures) failure.compareAndSet(null, error)
            }
        }
    }
    return AutoCloseable {
        try {
            runBlocking { withTimeout(5_000) { owner.cancelAndJoin() } }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("state subscription cleanup did not join; last state=$state", error)
        }
        failure.get()?.let { throw it }
    }
}

internal fun HostSessionController.awaitStateForTest(
    step: String, predicate: (HostingState) -> Boolean,
): HostingState {
    return try {
        runBlocking { withTimeout(5_000) { states.first(predicate) } }
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("$step: publication did not arrive; last state=$state", error)
    }
}

internal fun HostSessionController.awaitRoomStateForTest(
    predicate: (HostingState.LiveRoom) -> Boolean,
): HostingState.LiveRoom = awaitStateForTest("matching live room publication") {
    it is HostingState.LiveRoom && predicate(it)
} as HostingState.LiveRoom

internal fun HostSessionController.awaitCreationDecisionForTest(): HostingState =
    awaitStateForTest("room creation decision") {
        it is HostingState.HttpWarning || it is HostingState.Pending ||
            it is HostingState.Invitation || it is HostingState.Error
    }

internal fun HostSessionController.awaitCreatedForTest() {
    awaitStateForTest("room creation completion") { it is HostingState.Invitation || it is HostingState.Error }
}

internal fun HostSessionController.awaitSetupForTest(): Boolean {
    awaitStateForTest("host cleanup completed with Setup") { it is HostingState.Setup }
    return true
}
