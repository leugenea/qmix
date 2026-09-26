package com.qmix.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exact-review regressions for qmix#182. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSessionReviewFindingsTest {
    private val room = RoomState("ABCD", null,
        listOf(QueuedTrack("one", "https://example/one", "One", "", 1, "fixture")))

    @Test fun default_mutation_context_serializes_concurrent_public_admission_and_end() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val admissionEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val endAttempted = CountDownLatch(1)
        val endReturned = CountDownLatch(1)
        val persistence = object : EndpointSettingsPersistence {
            override fun load() = EndpointSettings(server.url("/").toString(), "https://guest.example")
            override fun save(settings: EndpointSettings) = Unit
            override fun isHttpWarningAcknowledged(): Boolean {
                admissionEntered.countDown()
                check(releaseAdmission.await(10, TimeUnit.SECONDS))
                return true
            }
            override fun acknowledgeHttpWarning() = Unit
        }
        val controller = HostSessionController(OkHttpClient(), settingsPersistence = persistence,
            roomCollectionScope = scope) // Real default; no injected mutation context.
        val threads = Executors.newFixedThreadPool(2)
        try {
            val admission = threads.submit<Boolean> { controller.createRoom() }
            assertTrue(admissionEntered.await(5, TimeUnit.SECONDS))
            threads.execute { endAttempted.countDown(); controller.endRoom(); endReturned.countDown() }
            assertTrue(endAttempted.await(5, TimeUnit.SECONDS))
            assertEquals("endRoom entered an active admission mutation", false,
                endReturned.await(500, TimeUnit.MILLISECONDS))
            releaseAdmission.countDown()
            assertTrue(admission.get(5, TimeUnit.SECONDS))
            assertTrue(endReturned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.awaitSetupForTest())
        } finally {
            releaseAdmission.countDown()
            threads.shutdownNow()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun default_mutation_context_does_not_publish_create_after_concurrent_end() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val saveEntered = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val endReturned = CountDownLatch(1)
        val endingSeen = AtomicBoolean(false)
        val staleInvitation = AtomicBoolean(false)
        val persistence = object : EndpointSettingsPersistence {
            override fun load() = EndpointSettings(server.url("/").toString(), "https://guest.example")
            override fun save(settings: EndpointSettings) {
                saveEntered.countDown()
                check(releaseSave.await(10, TimeUnit.SECONDS))
            }
            override fun isHttpWarningAcknowledged() = true
            override fun acknowledgeHttpWarning() = Unit
        }
        val controller = HostSessionController(OkHttpClient(), settingsPersistence = persistence,
            roomCollectionScope = scope) // Intentionally use the real default mutation context.
        val observer = controller.collectStatesForTest { state ->
            if (state is HostingState.Ending) endingSeen.set(true)
            if (endingSeen.get() && state is HostingState.Invitation) staleInvitation.set(true)
        }
        val endThread = Executors.newSingleThreadExecutor { task -> Thread(task, "concurrent-end").apply { isDaemon = true } }
        try {
            assertTrue(controller.createRoom())
            assertTrue("create never entered save", saveEntered.await(5, TimeUnit.SECONDS))
            endThread.execute { controller.endRoom(); endReturned.countDown() }
            assertTrue("endRoom could not enter Ending while save was blocked", endReturned.await(5, TimeUnit.SECONDS))
            assertTrue("endRoom did not publish Ending", controller.state is HostingState.Ending)
            releaseSave.countDown()
            assertTrue("teardown did not reach Setup", controller.awaitSetupForTest())
            assertEquals("cancelled create published an invitation after Ending", false, staleInvitation.get())
        } finally {
            releaseSave.countDown()
            observer.close()
            endThread.shutdownNow()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun create_error_queued_behind_teardown_does_not_block_mutation_dispatcher() {
        val requestEntered = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    requestEntered.countDown()
                    check(releaseResponse.await(10, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(503)
                }
            }
            start()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val errorQueued = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val executor = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue<Runnable>(), { task -> Thread(task, "create-error-mutation").apply { isDaemon = true } }) {
            override fun execute(command: Runnable) {
                super.execute(command)
                if (armed.get() && !Thread.currentThread().name.startsWith("create-error-mutation")) errorQueued.countDown()
            }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val mutation = QueueMutationContext(dispatcher) { Thread.currentThread().name.startsWith("create-error-mutation") }
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            queueMutationContext = mutation)
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val afterTeardownEntered = CountDownLatch(1)
        val releaseAfterTeardown = CountDownLatch(1)
        val workerComplete = CountDownLatch(1)
        val returned = CountDownLatch(1)
        try {
            assertTrue(controller.createRoom())
            assertTrue(requestEntered.await(5, TimeUnit.SECONDS))
            scope.coroutineContext[Job]!!.children.single().invokeOnCompletion { workerComplete.countDown() }
            executor.execute { blockerEntered.countDown(); check(releaseBlocker.await(10, TimeUnit.SECONDS)) }
            assertTrue(blockerEntered.await(5, TimeUnit.SECONDS))
            executor.execute { controller.endRoom(); returned.countDown() }
            executor.execute { afterTeardownEntered.countDown(); check(releaseAfterTeardown.await(10, TimeUnit.SECONDS)) }
            armed.set(true)
            releaseResponse.countDown()
            assertTrue("create error did not queue behind teardown", errorQueued.await(5, TimeUnit.SECONDS))
            armed.set(false)
            releaseBlocker.countDown()
            assertTrue("create error blocked dispatcher teardown", returned.await(5, TimeUnit.SECONDS))
            assertTrue(afterTeardownEntered.await(5, TimeUnit.SECONDS))
            assertTrue("cancelled create worker blocked on queued mutation", workerComplete.await(5, TimeUnit.SECONDS))
        } finally {
            armed.set(false)
            releaseResponse.countDown()
            releaseBlocker.countDown()
            releaseAfterTeardown.countDown()
            scope.cancel()
            dispatcher.close()
            server.shutdown()
        }
    }

    @Test fun queued_worker_mutation_cannot_deadlock_dispatcher_teardown() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val enteredCommand = CountDownLatch(1)
        val finishCommand = CompletableDeferred<Unit>()
        val workerQueued = CountDownLatch(1)
        val teardownEntered = CountDownLatch(1)
        val teardownReturned = CountDownLatch(1)
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val executor = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue<Runnable>(), { task -> Thread(task, "review-mutation").apply { isDaemon = true } }) {
            override fun execute(command: Runnable) {
                super.execute(command)
                if (armed.get() && !Thread.currentThread().name.startsWith("review-mutation")) workerQueued.countDown()
            }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val mutation = QueueMutationContext(dispatcher) { Thread.currentThread().name.startsWith("review-mutation") }
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            roomCollectionContext = Dispatchers.IO, queueMutationContext = mutation,
            roomRepositoryFactory = { object : RoomRepository {
                override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                    emit(RoomSyncState.Active(roomCode, room, Freshness.FRESH, LiveConnection.CONNECTED))
                    kotlinx.coroutines.awaitCancellation()
                }
            } },
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                QueueAdvancementCoordinator(credentials.code, credentials.hostToken,
                    QueueAdvanceCommand { _, _ ->
                        enteredCommand.countDown()
                        finishCommand.await()
                        QueueAdvanceCommandResult.Rejected
                    }, QueueRoomReconciler { RoomFetchResult.Failure }, observer, sessionScope, mutation)
            })
        try {
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
            controller.enterRoom()
            val ready = CountDownLatch(1)
            val observation = controller.collectStatesForTest {
                if ((it as? HostingState.LiveRoom)?.isPrimaryActionEnabled == true) ready.countDown()
            }
            try {
                assertTrue("room not ready", ready.await(5, TimeUnit.SECONDS))
                controller.onStartOrNext()
                assertTrue("command not started", enteredCommand.await(5, TimeUnit.SECONDS))
                executor.execute { blockerEntered.countDown(); check(releaseBlocker.await(10, TimeUnit.SECONDS)) }
                assertTrue(blockerEntered.await(5, TimeUnit.SECONDS))
                executor.execute { teardownEntered.countDown(); controller.endRoom(); teardownReturned.countDown() }
                armed.set(true)
                finishCommand.complete(Unit)
                assertTrue("worker did not enqueue a mutation behind teardown", workerQueued.await(5, TimeUnit.SECONDS))
                armed.set(false)
                releaseBlocker.countDown()
                assertTrue("teardown never entered", teardownEntered.await(5, TimeUnit.SECONDS))
                val finished = teardownReturned.await(3, TimeUnit.SECONDS)
                if (!finished) Thread.getAllStackTraces().forEach { (thread, stack) ->
                    if (thread.name.contains("review-mutation") || stack.any { it.className.contains("QueueAdvancement") }) {
                        println("REVIEW STACK ${thread.name}: ${stack.joinToString(" / ")}")
                    }
                }
                assertTrue("teardown joined a worker waiting on its dispatcher", finished)
                assertTrue(controller.awaitSetupForTest())
            } finally { observation.close() }
        } finally {
            armed.set(false)
            releaseBlocker.countDown()
            finishCommand.complete(Unit)
            dispatcher.close()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun delayed_settle_for_a_cannot_clear_pending_gate_for_b() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mutationThread = AtomicReference<Thread>()
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "review-delayed-settle-mutation").also(mutationThread::set)
        }.asCoroutineDispatcher()
        val mutation = QueueMutationContext(dispatcher) { Thread.currentThread() === mutationThread.get() }
        val first = CompletableDeferred<Unit>()
        val aObserverEntered = CountDownLatch(1)
        val bCommandEntered = CountDownLatch(1)
        val delayedState = AtomicReference<QueueAdvancementState>()
        val delayedObserver = AtomicReference<(QueueAdvancementState) -> Unit>()
        val commands = AtomicInteger()
        val coordinator = AtomicReference<QueueAdvancementCoordinator>()
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            roomCollectionContext = Dispatchers.IO, queueMutationContext = mutation,
            roomRepositoryFactory = { object : RoomRepository {
                override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                    emit(RoomSyncState.Active(roomCode, room, Freshness.FRESH, LiveConnection.CONNECTED))
                    kotlinx.coroutines.awaitCancellation()
                }
            } },
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                QueueAdvancementCoordinator(credentials.code, credentials.hostToken,
                    QueueAdvanceCommand { _, _ ->
                        if (commands.incrementAndGet() == 1) {
                            first.await()
                            QueueAdvanceCommandResult.Rejected
                        } else {
                            bCommandEntered.countDown()
                            kotlinx.coroutines.awaitCancellation()
                        }
                    }, QueueRoomReconciler { RoomFetchResult.Failure }, { update ->
                        if (!update.pending) {
                            delayedState.set(update)
                            delayedObserver.set(observer)
                            aObserverEntered.countDown()
                        } else observer(update)
                    }, sessionScope, mutation)
                    .also(coordinator::set)
            })
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            controller.enterRoom()
            val ready = CountDownLatch(1)
            val observation = controller.collectStatesForTest {
                if ((it as? HostingState.LiveRoom)?.isPrimaryActionEnabled == true) ready.countDown()
            }
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                coordinator.get().onAuthoritativeRoom(room)
                controller.onStartOrNext()
                assertTrue((controller.state as HostingState.LiveRoom).commandPending)
                first.complete(Unit)
                assertTrue("A settle observer did not reach gate", aObserverEntered.await(5, TimeUnit.SECONDS))
                assertTrue("B was not admitted", coordinator.get().requestExplicitAdvance())
                assertTrue("B command did not start", bCommandEntered.await(5, TimeUnit.SECONDS))
                assertTrue(coordinator.get().state.pending)
                mutation.run { delayedObserver.get().invoke(delayedState.get()) }
                assertTrue("A cleared B host command gate", (controller.state as HostingState.LiveRoom).commandPending)
            } finally { observation.close() }
        } finally {
            first.complete(Unit)
            try {
                controller.endRoom()
                controller.awaitSetupForTest()
            } finally {
                scope.cancel()
                dispatcher.close()
                server.shutdown()
            }
        }
    }

    @Test fun setup_collector_cannot_admit_reentrant_session_before_old_cleanup() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val collectionStarted = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val cleanupCompleted = AtomicBoolean(false)
        val setupSeen = CountDownLatch(1)
        val watchSetup = AtomicBoolean(false)
        val teardownReturned = CountDownLatch(1)
        val reentrantAdmission = AtomicReference<Boolean?>()
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            roomCollectionContext = Dispatchers.IO,
            roomRepositoryFactory = { object : RoomRepository {
                override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                    collectionStarted.countDown()
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            cleanupStarted.countDown()
                            check(allowCleanup.await(10, TimeUnit.SECONDS))
                            cleanupCompleted.set(true)
                        }
                    }
                }
            } })
        val observer = controller.collectStatesForTest { state ->
            if (state is HostingState.Setup && watchSetup.get() && setupSeen.count > 0L) {
                reentrantAdmission.set(controller.createRoom())
                setupSeen.countDown()
            }
        }
        val endThread = Executors.newSingleThreadExecutor { task -> Thread(task, "review-teardown").apply { isDaemon = true } }
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            controller.enterRoom()
            assertTrue("old collection never started", collectionStarted.await(5, TimeUnit.SECONDS))
            watchSetup.set(true)
            endThread.execute { controller.endRoom(); teardownReturned.countDown() }
            assertTrue("old cleanup never started", cleanupStarted.await(5, TimeUnit.SECONDS))
            assertEquals("Setup exposed before cleanup", 1L, setupSeen.count)
            assertTrue("teardown must return without joining", teardownReturned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.state is HostingState.Ending)
            assertEquals(false, controller.createRoom())
            assertEquals(false, controller.confirmHttpWarning())
            controller.cancelHttpWarning()
            controller.updateSettings("https://replacement.example", "https://guest.example")
            controller.enterRoom()
            controller.onHostStopped()
            controller.onHostStarted()
            controller.onStartOrNext()
            controller.onPlayPause()
            controller.onInvite()
            assertTrue(controller.state is HostingState.Ending)
            assertEquals(LiveRoomBackResult.EXIT_ACTIVITY, controller.onBack())
            allowCleanup.countDown()
            assertTrue("teardown did not finish", teardownReturned.await(5, TimeUnit.SECONDS))
            assertTrue("Setup was not delivered", setupSeen.await(5, TimeUnit.SECONDS))
            assertTrue(cleanupCompleted.get())
            assertEquals("Setup collector may admit only after old cleanup", true, reentrantAdmission.get())
            assertTrue(cleanupCompleted.get())
            assertTrue(controller.state is HostingState.Pending || controller.state is HostingState.Invitation)
            controller.awaitCreatedForTest()
            assertEquals(2, server.requestCount)
        } finally {
            observer.close()
            allowCleanup.countDown()
            endThread.shutdownNow()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun teardown_joins_cancelled_foreground_recovery_without_blocking_its_dispatcher() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "review-recovery-mutation").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val mutation = QueueMutationContext(dispatcher) {
            Thread.currentThread().name.startsWith("review-recovery-mutation")
        }
        val started = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            roomCollectionContext = Dispatchers.IO, queueMutationContext = mutation,
            foregroundReconcilerFactory = { { _: String ->
                started.countDown()
                kotlinx.coroutines.awaitCancellation()
            } })
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            controller.enterRoom()
            controller.onHostStopped()
            controller.onHostStarted()
            assertTrue("recovery never began", started.await(5, TimeUnit.SECONDS))
            dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable {
                controller.endRoom()
                returned.countDown()
            })
            assertTrue("teardown joined recovery queued on its own dispatcher", returned.await(3, TimeUnit.SECONDS))
            assertTrue(controller.awaitSetupForTest())
        } finally {
            dispatcher.close()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun foreground_restart_waits_for_non_cancellable_collection_cleanup() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val collecting = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recovery = CountDownLatch(1)
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            roomRepositoryFactory = { object : RoomRepository {
                override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                    collecting.countDown()
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { withContext(NonCancellable + Dispatchers.IO) {
                        cleanup.countDown()
                        check(release.await(30, TimeUnit.SECONDS))
                    } }
                }
            } }, foregroundRecoveryContext = Dispatchers.Unconfined, foregroundReconcilerFactory = { { _: String ->
                recovery.countDown()
                RoomFetchResult.Failure
            } })
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            controller.enterRoom()
            assertTrue(collecting.await(5, TimeUnit.SECONDS))
            controller.onHostStopped()
            assertTrue(cleanup.await(5, TimeUnit.SECONDS))
            controller.onHostStarted()
            assertEquals("recovery overlapped old collection", 1L, recovery.count)
            release.countDown()
            assertTrue("recovery never started after collection cleanup", recovery.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            controller.endRoom()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun fresh_recovery_signal_waits_for_its_cancelled_collection() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstCollection = CountDownLatch(1)
        val nextCollection = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondRecovery = CountDownLatch(1)
        val collections = AtomicInteger()
        val fetches = AtomicInteger()
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            foregroundRecoveryContext = Dispatchers.Unconfined,
            roomRepositoryFactory = { object : RoomRepository {
                override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                    if (collections.incrementAndGet() == 1) {
                        firstCollection.countDown()
                        kotlinx.coroutines.awaitCancellation()
                    } else {
                        nextCollection.countDown()
                        try {
                            emit(RoomSyncState.Active(roomCode, room, Freshness.FRESH, LiveConnection.CONNECTED))
                            kotlinx.coroutines.awaitCancellation()
                        } finally { withContext(NonCancellable + Dispatchers.IO) {
                            cleanup.countDown()
                            check(release.await(30, TimeUnit.SECONDS))
                        } }
                    }
                }
            } }, foregroundReconcilerFactory = { { _: String ->
                if (fetches.incrementAndGet() == 2) secondRecovery.countDown()
                RoomFetchResult.Failure
            } })
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            controller.enterRoom()
            assertTrue(firstCollection.await(5, TimeUnit.SECONDS))
            controller.onHostStopped()
            controller.onHostStarted()
            assertTrue(nextCollection.await(5, TimeUnit.SECONDS))
            assertTrue(cleanup.await(5, TimeUnit.SECONDS))
            assertEquals("recovery overlapped cancelled collection", 1, fetches.get())
            release.countDown()
            assertTrue("recovery did not resume after collection cleanup", secondRecovery.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            controller.endRoom()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun foreground_restart_waits_for_cancelled_recovery_cleanup() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val release = CountDownLatch(1)
        val restarted = CountDownLatch(1)
        val attempts = AtomicInteger()
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            foregroundRecoveryContext = Dispatchers.Unconfined,
            foregroundReconcilerFactory = { { _: String ->
                if (attempts.incrementAndGet() == 1) {
                    started.countDown()
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { withContext(NonCancellable + Dispatchers.IO) {
                        cleanup.countDown()
                        check(release.await(30, TimeUnit.SECONDS))
                    } }
                } else {
                    restarted.countDown()
                    RoomFetchResult.Failure
                }
            } })
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            controller.enterRoom()
            controller.onHostStopped()
            controller.onHostStarted()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            controller.onHostStopped()
            assertTrue(cleanup.await(5, TimeUnit.SECONDS))
            controller.onHostStarted()
            assertEquals("recovery overlapped cancelled recovery", 1, attempts.get())
            release.countDown()
            assertTrue("recovery did not restart after cleanup", restarted.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            controller.endRoom()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun detached_playback_callback_cannot_modify_replacement_session() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mutationThread = AtomicReference<Thread>()
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "review-detached-playback-mutation").also(mutationThread::set)
        }.asCoroutineDispatcher()
        val mutation = QueueMutationContext(dispatcher) { Thread.currentThread() === mutationThread.get() }
        val observers = mutableListOf<(LocalPlaybackState) -> Unit>()
        val engine = object : PlaybackEngine {
            override val state = PlaybackState()
            override fun prepare(media: PlaybackMedia) = Unit
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(positionMs: Long) = Unit
            override fun release() = Unit
            override fun addListener(listener: (PlaybackState) -> Unit) = Unit
            override fun removeListener(listener: (PlaybackState) -> Unit) = Unit
        }
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            queueMutationContext = mutation,
            playbackCoordinatorFactory = { _, credentials, observer, advance, sessionScope ->
                observers.add(observer)
                AuthoritativePlaybackCoordinator(credentials.code, "https://example/stream", engine,
                    { RoomFetchResult.Failure }, sessionScope,
                    mutation, advance, observer)
            })
        try {
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
            controller.enterRoom()
            assertEquals(1, observers.size)
            controller.endRoom()
            assertTrue(controller.awaitSetupForTest())
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
            controller.enterRoom()
            assertEquals(2, observers.size)
            val replacement = controller.state
            mutation.run { observers.first()(LocalPlaybackState(status = LocalPlaybackStatus.BUFFERING)) }
            assertEquals("detached callback mutated replacement", replacement, controller.state)
        } finally {
            try {
                controller.endRoom()
                controller.awaitSetupForTest()
            } finally {
                scope.cancel()
                dispatcher.close()
                server.shutdown()
            }
        }
    }

    @Test fun successful_recovery_collector_stop_cannot_reopen_background_queue() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "recovery-stop-mutation").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val mutation = QueueMutationContext(dispatcher) {
            Thread.currentThread().name.startsWith("recovery-stop-mutation")
        }
        val recoveredRoom = RoomState("ABCD", CurrentTrack("one", 0, "playing", "One", "Artist"),
            listOf(QueuedTrack("two", "https://example/two", "Two", "", 1, "fixture")))
        val commands = AtomicInteger()
        val stopped = CountDownLatch(1)
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            queueMutationContext = mutation,
            foregroundReconcilerFactory = { { RoomFetchResult.Success(recoveredRoom) } },
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                QueueAdvancementCoordinator(credentials.code, credentials.hostToken,
                    QueueAdvanceCommand { _, _ -> commands.incrementAndGet(); QueueAdvanceCommandResult.Success },
                    QueueRoomReconciler { RoomFetchResult.Failure }, observer, sessionScope, mutation)
            })
        val observer = controller.collectStatesForTest { state ->
            if ((state as? HostingState.LiveRoom)?.foregroundRecoveryPending == false &&
                (state.synchronization as? RoomSyncState.Active)?.room === recoveredRoom) {
                controller.onHostStopped()
                stopped.countDown()
            }
        }
        try {
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
            controller.enterRoom()
            controller.onHostStopped()
            controller.onHostStarted()
            assertTrue("synchronous recovery collector never stopped host", stopped.await(5, TimeUnit.SECONDS))
            assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
            assertEquals("background playback end admitted queue command", false, controller.onPlaybackEnded("one"))
            assertEquals(0, commands.get())
        } finally {
            observer.close()
            controller.endRoom()
            scope.cancel()
            dispatcher.close()
            server.shutdown()
        }
    }

    private fun roomResponse() = MockResponse().setResponseCode(201)
        .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}""")
}
