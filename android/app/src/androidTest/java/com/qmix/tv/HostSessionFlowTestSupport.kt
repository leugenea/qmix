package com.qmix.tv

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

/** Synchronous observers retain failures for the test thread rather than silently losing them. */
internal fun HostSessionController.collectStatesForTest(
    isolateFailures: Boolean = false,
    observer: (HostingState) -> Unit,
): AutoCloseable {
    val owner = SupervisorJob()
    val scope = CoroutineScope(owner + Dispatchers.Unconfined)
    val failure = AtomicReference<Throwable?>()
    scope.launch {
        states.collect { state ->
            try { observer(state) } catch (caught: Throwable) {
                if (!isolateFailures) failure.compareAndSet(null, caught)
            }
        }
    }
    return AutoCloseable {
        try {
            runBlocking { withTimeout(5_000) { owner.cancelAndJoin() } }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("join host state subscription: timed out; last state=$state", timeout)
        }
        failure.get()?.let { throw it }
    }
}

internal fun HostSessionController.awaitCreatedForTest(step: String = "room creation result") {
    awaitStateForTest(step) { it is HostingState.Invitation || it is HostingState.Error }
}

internal fun HostSessionController.awaitSetupForTest(step: String = "session cleanup publishes Setup") {
    awaitStateForTest(step) { it is HostingState.Setup }
}

internal fun HostSessionController.awaitStateForTest(
    step: String = "host state publication",
    predicate: (HostingState) -> Boolean,
): HostingState = try {
    runBlocking { withTimeout(5_000) { states.first(predicate) } }
} catch (timeout: TimeoutCancellationException) {
    throw AssertionError("$step: timed out; last state=$state", timeout)
}

/** Poll real external readiness; the bound is only a labeled hang guard. */
internal fun awaitConditionForTest(
    step: String = "observable condition",
    predicate: () -> Boolean,
) {
    try {
        runBlocking { withTimeout(5_000) { while (!predicate()) delay(10) } }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("$step: timed out; predicate remains false", timeout)
    }
}
