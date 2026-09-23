package com.qmix.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay

internal fun HostSessionController.collectStatesForTest(observer: (HostingState) -> Unit): AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    scope.launch { states.collect { state -> try { observer(state) } catch (_: Throwable) { } } }
    return AutoCloseable { scope.cancel() }
}

internal fun HostSessionController.awaitCreatedForTest() {
    runBlocking { withTimeout(5_000) { states.first { it is HostingState.Invitation || it is HostingState.Error } } }
}

internal fun HostSessionController.awaitSetupForTest() {
    runBlocking { withTimeout(5_000) { states.first { it is HostingState.Setup } } }
}

internal fun HostSessionController.awaitStateForTest(predicate: (HostingState) -> Boolean): HostingState =
    runBlocking { withTimeout(5_000) { states.first(predicate) } }

internal fun awaitConditionForTest(predicate: () -> Boolean) {
    runBlocking {
        withTimeout(5_000) {
            while (!predicate()) delay(10)
        }
    }
}
