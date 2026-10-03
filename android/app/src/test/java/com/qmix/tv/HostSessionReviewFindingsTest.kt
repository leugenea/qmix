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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

    private fun routeTwoCreations(server: MockWebServer) {
        val creations = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "DELETE" && request.path == "/rooms/ABCD" ->
                    MockResponse().setResponseCode(204)
                request.method == "POST" && request.path == "/rooms" &&
                    creations.incrementAndGet() <= 2 -> roomResponse()
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

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
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)), httpClient = OkHttpClient(), settingsPersistence = persistence,
            roomCollectionScope = scope) // Single processing lane; callers run concurrently.
        val finished = CountDownLatch(1)
        val sawPending = AtomicBoolean(false)
        val observation = controller.collectStatesForTest { state ->
            if (state is HostingState.Pending) sawPending.set(true)
            if (state is HostingState.Setup && sawPending.get()) finished.countDown()
        }
        val threads = Executors.newFixedThreadPool(2)
        try {
            val admission = threads.submit<Unit> { controller.createRoom() }
            assertTrue(admissionEntered.await(5, TimeUnit.SECONDS))
            threads.execute { endAttempted.countDown(); controller.endRoom(); endReturned.countDown() }
            assertTrue(endAttempted.await(5, TimeUnit.SECONDS))
            assertTrue("End offer blocked behind active handler", endReturned.await(5, TimeUnit.SECONDS))
            assertTrue("End must not preempt the active Create handler", controller.state is HostingState.Setup)
            releaseAdmission.countDown()
            admission.get(5, TimeUnit.SECONDS)
            assertTrue(endReturned.await(5, TimeUnit.SECONDS))
            assertTrue("queued End did not finish Create cleanup", finished.await(5, TimeUnit.SECONDS))
            assertTrue(sawPending.get())
            assertTrue(controller.state is HostingState.Setup)
        } finally {
            releaseAdmission.countDown()
            observation.close()
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
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)), httpClient = OkHttpClient(), settingsPersistence = persistence,
            roomCollectionScope = scope) // Same event-loop lane as the concurrent ingress test.
        val observer = controller.collectStatesForTest { state ->
            if (state is HostingState.Ending) endingSeen.set(true)
            if (endingSeen.get() && state is HostingState.Invitation) staleInvitation.set(true)
        }
        val endThread = Executors.newSingleThreadExecutor { task -> Thread(task, "concurrent-end").apply { isDaemon = true } }
        try {
            controller.createRoom()
            assertTrue("create never entered save", saveEntered.await(5, TimeUnit.SECONDS))
            endThread.execute { controller.endRoom(); endReturned.countDown() }
            assertTrue("End offer blocked while save was blocked", endReturned.await(5, TimeUnit.SECONDS))
            controller.awaitStateForTest("processed End during blocked save") { it is HostingState.Ending }
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
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestEntered.countDown()
                    check(releaseResponse.await(10, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(503)
                }
            }
            start()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "create-error-mutation").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val mutation = QueueMutationContext(dispatcher) { Thread.currentThread().name == "create-error-mutation" }
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            queueMutationContext = mutation)
        // The loop is an independent child; identify the new session, not children.single().
        val loopChildren = scope.coroutineContext[Job]!!.children.toSet()
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val workerComplete = CountDownLatch(1)
        val unexpectedError = AtomicBoolean(false)
        val observation = controller.collectStatesForTest {
            if (it is HostingState.Error) unexpectedError.set(true)
        }
        try {
            controller.createRoom()
            assertTrue("create request not started", requestEntered.await(5, TimeUnit.SECONDS))
            val owner = scope.coroutineContext[Job]!!.children.single { it !in loopChildren }
            val worker = owner.children.single()
            worker.invokeOnCompletion { workerComplete.countDown() }
            dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable {
                blockerEntered.countDown()
                check(releaseBlocker.await(10, TimeUnit.SECONDS))
            })
            assertTrue("processing lane not held", blockerEntered.await(5, TimeUnit.SECONDS))
            controller.endRoom() // End enters the inbox before the error result.
            releaseResponse.countDown()
            assertTrue("create worker waited for event-loop acknowledgement", workerComplete.await(5, TimeUnit.SECONDS))
            releaseBlocker.countDown()
            controller.awaitStateForTest("End joins completed create worker") { it is HostingState.Setup }
            controller.updateSettings(server.url("/").toString().trimEnd('/'), "https://after-error.example")
            controller.awaitStateForTest("settings processed after obsolete create error") {
                it is HostingState.Setup && it.guestOrigin == "https://after-error.example"
            }
            assertTrue("session owner was not fully joined", owner.isCompleted)
            assertEquals("obsolete create error replaced Setup", false, unexpectedError.get())
        } finally {
            releaseResponse.countDown()
            releaseBlocker.countDown()
            observation.close()
            scope.cancel()
            dispatcher.close()
            server.shutdown()
        }
    }

    @Test fun queued_worker_mutation_cannot_deadlock_dispatcher_teardown() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val enteredCommand = CountDownLatch(1)
        val coordinator = AtomicReference<QueueAdvancementCoordinator>()
        val finishCommand = CompletableDeferred<Unit>()
        val workerQueued = CountDownLatch(1)
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
                assertTrue("room not ready", ready.await(5, TimeUnit.SECONDS))
                controller.onStartOrNext()
                assertTrue("command not started", enteredCommand.await(5, TimeUnit.SECONDS))
                executor.execute { blockerEntered.countDown(); check(releaseBlocker.await(10, TimeUnit.SECONDS)) }
                assertTrue(blockerEntered.await(5, TimeUnit.SECONDS))
                val ownedWorkers = coordinator.get().foregroundWorkers()
                assertTrue("command has no owned worker", ownedWorkers.isNotEmpty())
                controller.endRoom()
                armed.set(true)
                finishCommand.complete(Unit)
                assertTrue("worker did not enqueue a mutation behind teardown", workerQueued.await(5, TimeUnit.SECONDS))
                armed.set(false)
                releaseBlocker.countDown()
                controller.awaitStateForTest("teardown joins queued coordinator mutation") { it is HostingState.Setup }
                assertTrue("teardown left coordinator workers alive", ownedWorkers.all { it.isCompleted })
            } finally { observation.close() }
        } finally {
            armed.set(false)
            releaseBlocker.countDown()
            finishCommand.complete(Unit)
            scope.cancel()
            dispatcher.close()
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
                controller.awaitStateForTest("A command pending publication") {
                    (it as? HostingState.LiveRoom)?.commandPending == true
                }
                first.complete(Unit)
                assertTrue("A settle observer did not reach gate", aObserverEntered.await(5, TimeUnit.SECONDS))
                assertTrue("B was not admitted", coordinator.get().requestExplicitAdvance())
                assertTrue("B command did not start", bCommandEntered.await(5, TimeUnit.SECONDS))
                assertTrue(coordinator.get().state.pending)
                mutation.run { delayedObserver.get().invoke(delayedState.get()) }
                controller.onInvite()
                val afterDelayedSettle = controller.awaitStateForTest("invitation after delayed A settle") {
                    (it as? HostingState.LiveRoom)?.invitationVisible == true
                } as HostingState.LiveRoom
                assertTrue("A cleared B host command gate", afterDelayedSettle.commandPending)
                assertTrue(coordinator.get().state.pending)
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
        val server = MockWebServer().apply { start() }
        routeTwoCreations(server)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val invitationStarted = CountDownLatch(1)
        val collectionStarted = CountDownLatch(1)
        val collections = AtomicInteger()
        val cleanupStarted = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val cleanupCompleted = AtomicBoolean(false)
        val setupSeen = CountDownLatch(1)
        val watchSetup = AtomicBoolean(false)
        val teardownReturned = CountDownLatch(1)
        val reentrantAfterCleanup = AtomicBoolean(false)
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)), httpClient = OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            roomCollectionContext = Dispatchers.IO,
            roomRepositoryFactory = { object : RoomRepository {
                override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                    if (collections.incrementAndGet() == 1) {
                        invitationStarted.countDown()
                        kotlinx.coroutines.awaitCancellation()
                    } else {
                        try {
                            collectionStarted.countDown()
                            kotlinx.coroutines.awaitCancellation()
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) {
                                cleanupStarted.countDown()
                                check(allowCleanup.await(10, TimeUnit.SECONDS))
                                cleanupCompleted.set(true)
                            }
                        }
                    }
                }
            } })
        val observer = controller.collectStatesForTest { state ->
            if (state is HostingState.Setup && watchSetup.get() && setupSeen.count > 0L) {
                reentrantAfterCleanup.set(cleanupCompleted.get())
                controller.createRoom()
                setupSeen.countDown()
            }
        }
        val endThread = Executors.newSingleThreadExecutor { task -> Thread(task, "review-teardown").apply { isDaemon = true } }
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            assertTrue("invitation collection never started", invitationStarted.await(5, TimeUnit.SECONDS))
            controller.enterRoom()
            assertTrue("old live collection never started", collectionStarted.await(5, TimeUnit.SECONDS))
            watchSetup.set(true)
            endThread.execute { controller.endRoom(); teardownReturned.countDown() }
            assertTrue("old cleanup never started", cleanupStarted.await(5, TimeUnit.SECONDS))
            assertEquals("Setup exposed before cleanup", 1L, setupSeen.count)
            assertTrue("teardown must return without joining", teardownReturned.await(5, TimeUnit.SECONDS))
            assertTrue(controller.state is HostingState.Ending)
            controller.confirmHttpWarning()
            controller.cancelHttpWarning()
            controller.enterRoom()
            controller.onStartOrNext()
            controller.onPlayPause()
            controller.onInvite()
            assertTrue(controller.state is HostingState.Ending)
            controller.onBack()
            allowCleanup.countDown()
            assertTrue("Setup was not delivered", setupSeen.await(5, TimeUnit.SECONDS))
            assertTrue(cleanupCompleted.get())
            assertTrue("Setup collector ran before old cleanup", reentrantAfterCleanup.get())
            controller.awaitStateForTest("replacement invitation after Setup callback") { it is HostingState.Invitation }
            val requests = (1..3).map { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            assertEquals(2, requests.count { it.method == "POST" && it.path == "/rooms" })
            assertEquals(1, requests.count { it.method == "DELETE" && it.path == "/rooms/ABCD" })
            assertEquals(3, server.requestCount)
        } finally {
            allowCleanup.countDown()
            observer.close()
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
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanupCompleted = AtomicBoolean(false)
        val worker = AtomicReference<Job>()
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            roomCollectionContext = Dispatchers.IO, queueMutationContext = mutation,
            foregroundRecoveryContext = dispatcher,
            foregroundReconcilerFactory = { { _: String ->
                worker.set(kotlinx.coroutines.currentCoroutineContext()[Job])
                started.countDown()
                try { kotlinx.coroutines.awaitCancellation() }
                finally { withContext(NonCancellable + dispatcher) {
                    // Cleanup resumes on the dispatcher on which the event loop is joining it.
                    cleanupStarted.countDown()
                    releaseCleanup.await()
                    cleanupCompleted.set(true)
                } }
            } })
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            controller.enterRoom()
            controller.onHostStopped()
            controller.onHostStarted()
            assertTrue("recovery never began", started.await(5, TimeUnit.SECONDS))
            controller.endRoom()
            assertTrue("joining recovery blocked its cleanup dispatcher", cleanupStarted.await(5, TimeUnit.SECONDS))
            assertEquals(HostingState.Ending, controller.state)
            assertEquals(false, worker.get().isCompleted)
            releaseCleanup.complete(Unit)
            controller.awaitStateForTest("teardown joins cancelled foreground recovery") { it is HostingState.Setup }
            assertTrue(cleanupCompleted.get())
            assertTrue("recovery worker survived Setup", worker.get().isCompleted)
        } finally {
            releaseCleanup.complete(Unit)
            scope.cancel()
            dispatcher.close()
            server.shutdown()
        }
    }

    @Test fun foreground_restart_waits_for_non_cancellable_collection_cleanup() {
        val server = MockWebServer().apply { start(); enqueue(roomResponse()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val invitationCollecting = CountDownLatch(1)
        val collecting = CountDownLatch(1)
        val collections = AtomicInteger()
        val cleanup = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recovery = CountDownLatch(1)
        val cleanupCompleted = AtomicBoolean(false)
        val overlap = AtomicBoolean(false)
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)), httpClient = OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            roomRepositoryFactory = { object : RoomRepository {
                override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                    if (collections.incrementAndGet() == 1) {
                        invitationCollecting.countDown()
                        kotlinx.coroutines.awaitCancellation()
                    } else {
                        try {
                            collecting.countDown()
                            kotlinx.coroutines.awaitCancellation()
                        } finally { withContext(NonCancellable + Dispatchers.IO) {
                            cleanup.countDown()
                            check(release.await(30, TimeUnit.SECONDS))
                            cleanupCompleted.set(true)
                        } }
                    }
                }
            } }, foregroundRecoveryContext = Dispatchers.Unconfined, foregroundReconcilerFactory = { { _: String ->
                if (!cleanupCompleted.get()) overlap.set(true)
                recovery.countDown()
                RoomFetchResult.Failure
            } })
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            assertTrue(invitationCollecting.await(5, TimeUnit.SECONDS))
            controller.enterRoom()
            assertTrue(collecting.await(5, TimeUnit.SECONDS))
            controller.onHostStopped()
            assertTrue(cleanup.await(5, TimeUnit.SECONDS))
            controller.onHostStarted()
            assertEquals("recovery overlapped old collection", 1L, recovery.count)
            release.countDown()
            assertTrue("recovery never started after collection cleanup", recovery.await(5, TimeUnit.SECONDS))
            assertTrue(cleanupCompleted.get())
            assertEquals("recovery overlapped collection cleanup", false, overlap.get())
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
        val invitationCollection = CountDownLatch(1)
        val liveCollection = CountDownLatch(1)
        val stopCleanup = CountDownLatch(1)
        val releaseStopCleanup = CountDownLatch(1)
        val freshCleanup = CountDownLatch(1)
        val releaseFreshCleanup = CountDownLatch(1)
        val stopCleanupCompleted = AtomicBoolean(false)
        val freshCleanupCompleted = AtomicBoolean(false)
        val overlappingFetch = AtomicBoolean(false)
        val secondRecovery = CountDownLatch(1)
        val collections = AtomicInteger()
        val fetches = AtomicInteger()
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(Dispatchers.Default.limitedParallelism(1)),
            httpClient = OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            foregroundRecoveryContext = Dispatchers.Unconfined,
            roomRepositoryFactory = { object : RoomRepository {
                override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                    val generation = collections.incrementAndGet()
                    if (generation == 1) {
                        invitationCollection.countDown()
                        kotlinx.coroutines.awaitCancellation()
                    } else {
                        try {
                            emit(RoomSyncState.Active(roomCode, room, Freshness.FRESH, LiveConnection.CONNECTED))
                            if (generation == 2) liveCollection.countDown()
                            kotlinx.coroutines.awaitCancellation()
                        } finally { withContext(NonCancellable + Dispatchers.IO) {
                            when (generation) {
                                2 -> {
                                    stopCleanup.countDown()
                                    check(releaseStopCleanup.await(10, TimeUnit.SECONDS))
                                    stopCleanupCompleted.set(true)
                                }
                                3 -> {
                                    // A fresh signal after failed GET cancels its own collector before another GET.
                                    freshCleanup.countDown()
                                    check(releaseFreshCleanup.await(10, TimeUnit.SECONDS))
                                    freshCleanupCompleted.set(true)
                                }
                            }
                        } }
                    }
                }
            } }, foregroundReconcilerFactory = { { _: String ->
                if (!stopCleanupCompleted.get()) overlappingFetch.set(true)
                if (fetches.incrementAndGet() == 1) RoomFetchResult.Failure
                else {
                    if (!freshCleanupCompleted.get()) overlappingFetch.set(true)
                    secondRecovery.countDown()
                    RoomFetchResult.Success(room)
                }
            } })
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            assertTrue("invitation collection never started", invitationCollection.await(5, TimeUnit.SECONDS))
            controller.enterRoom()
            assertTrue("live collection never armed cleanup", liveCollection.await(5, TimeUnit.SECONDS))
            controller.awaitStateForTest("initial fresh room publication") {
                (it as? HostingState.LiveRoom)?.isPrimaryActionEnabled == true
            }
            controller.onHostStopped()
            controller.onHostStarted()
            assertTrue("Stop did not cancel live collection", stopCleanup.await(5, TimeUnit.SECONDS))
            assertEquals("recovery overlapped cancelled live collection", 0, fetches.get())
            releaseStopCleanup.countDown()
            assertTrue("fresh signal did not cancel replacement collection", freshCleanup.await(5, TimeUnit.SECONDS))
            assertEquals("fresh-triggered GET overlapped its cancelled collector", 1, fetches.get())
            assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
            releaseFreshCleanup.countDown()
            assertTrue("second recovery did not start after fresh cleanup", secondRecovery.await(5, TimeUnit.SECONDS))
            val recovered = controller.awaitStateForTest("fresh-triggered successful recovery publication") {
                it is HostingState.LiveRoom && !it.foregroundRecoveryPending && it.isPrimaryActionEnabled
            } as HostingState.LiveRoom
            assertEquals(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED),
                recovered.synchronization)
            assertTrue(stopCleanupCompleted.get())
            assertTrue(freshCleanupCompleted.get())
            assertEquals("a GET overlapped predecessor cleanup", false, overlappingFetch.get())
            assertEquals(2, fetches.get())
        } finally {
            releaseStopCleanup.countDown()
            releaseFreshCleanup.countDown()
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
        val cleanupCompleted = AtomicBoolean(false)
        val overlap = AtomicBoolean(false)
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)), httpClient = OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            foregroundRecoveryContext = Dispatchers.Unconfined,
            foregroundReconcilerFactory = { { _: String ->
                if (attempts.incrementAndGet() == 1) {
                    started.countDown()
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { withContext(NonCancellable + Dispatchers.IO) {
                        cleanup.countDown()
                        check(release.await(30, TimeUnit.SECONDS))
                        cleanupCompleted.set(true)
                    } }
                } else {
                    if (!cleanupCompleted.get()) overlap.set(true)
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
            assertTrue(cleanupCompleted.get())
            assertEquals("successor recovery overlapped cancelled predecessor", false, overlap.get())
        } finally {
            release.countDown()
            controller.endRoom()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun detached_playback_callback_cannot_modify_replacement_session() {
        val server = MockWebServer().apply { start() }
        routeTwoCreations(server)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mutationThread = AtomicReference<Thread>()
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "review-detached-playback-mutation").also(mutationThread::set)
        }.asCoroutineDispatcher()
        val mutation = QueueMutationContext(dispatcher) { Thread.currentThread() === mutationThread.get() }
        val observers = java.util.concurrent.CopyOnWriteArrayList<(LocalPlaybackState) -> Unit>()
        val playbackCoordinators = java.util.concurrent.CopyOnWriteArrayList<AuthoritativePlaybackCoordinator>()
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
                    mutation, advance, observer).also(playbackCoordinators::add)
            })
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            controller.enterRoom()
            controller.onInvite()
            controller.awaitRoomStateForTest { it.invitationVisible }
            assertEquals(1, observers.size)
            val oldRoom = room.copy(current = CurrentTrack("old-track", 0, "playing", "Old", "Artist"))
            playbackCoordinators.first().onSynchronization(
                RoomSyncState.Active("ABCD", oldRoom, Freshness.FRESH, LiveConnection.CONNECTED))
            controller.awaitStateForTest("old coordinator buffering its genuine selection") {
                (it as? HostingState.LiveRoom)?.playback?.trackId == "old-track"
            }
            val stalePlayback = playbackCoordinators.first().state
            assertEquals(LocalPlaybackStatus.BUFFERING, stalePlayback.status)
            controller.endRoom()
            assertTrue(controller.awaitSetupForTest())
            controller.createRoom()
            controller.awaitCreatedForTest()
            val requests = (1..3).map { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            assertEquals(2, requests.count { it.method == "POST" && it.path == "/rooms" })
            assertEquals(1, requests.count { it.method == "DELETE" && it.path == "/rooms/ABCD" })
            controller.enterRoom()
            controller.onInvite()
            controller.awaitRoomStateForTest { it.invitationVisible }
            assertEquals(2, observers.size)
            val replacement = controller.state as HostingState.LiveRoom
            assertEquals(LocalPlaybackStatus.IDLE, replacement.playback.status)
            mutation.run { observers.first()(stalePlayback) }
            controller.onBack() // A real UI publication follows the queued stale playback result.
            val afterStale = controller.awaitStateForTest("replacement invite hidden after detached callback") {
                it is HostingState.LiveRoom && !it.invitationVisible
            }
            assertEquals("detached callback mutated replacement", replacement.copy(invitationVisible = false), afterStale)
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
            controller.createRoom()
            controller.awaitCreatedForTest()
            controller.enterRoom()
            controller.onHostStopped()
            controller.onHostStarted()
            assertTrue("recovery collector never queued Stop", stopped.await(5, TimeUnit.SECONDS))
            controller.awaitStateForTest("observer Stop processed after successful recovery") {
                (it as? HostingState.LiveRoom)?.foregroundRecoveryPending == true
            }
            assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
            controller.onPlaybackEnded("one")
            val exit = CountDownLatch(1)
            controller.onBack { exit.countDown() }
            assertTrue("Back did not complete background session cleanup", exit.await(5, TimeUnit.SECONDS))
            assertTrue(controller.state is HostingState.Setup)
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
