package com.qmix.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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

/** qmix#217: reentrant lifecycle callbacks cannot detach a live room worker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSessionSingleWorkerRegressionTest {
    @Test fun repository_factory_restart_keeps_recovery_owned_until_its_cleanup_finishes() {
        val server = roomServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstRecovery = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val secondRecovery = CountDownLatch(1)
        val attempts = AtomicInteger()
        val cleanupCompleted = AtomicBoolean(false)
        val overlappingRecovery = AtomicBoolean(false)
        val factories = AtomicInteger()
        val mutation = QueueMutationContext(Dispatchers.Default.limitedParallelism(1))
        lateinit var controller: HostSessionController
        controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            queueMutationContext = mutation, foregroundRecoveryContext = Dispatchers.Unconfined,
            roomRepositoryFactory = {
                if (factories.incrementAndGet() == 1) {
                    controller.onHostStopped()
                    controller.onHostStarted()
                }
                object : RoomRepository {
                    override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                        kotlinx.coroutines.awaitCancellation()
                    }
                }
            }, foregroundReconcilerFactory = { { _: String ->
                if (attempts.incrementAndGet() == 1) {
                    firstRecovery.countDown()
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { withContext(NonCancellable + Dispatchers.IO) {
                        cleanup.countDown()
                        check(releaseCleanup.await(10, TimeUnit.SECONDS))
                        cleanupCompleted.set(true)
                    } }
                } else {
                    if (!cleanupCompleted.get()) overlappingRecovery.set(true)
                    secondRecovery.countDown()
                    RoomFetchResult.Failure
                }
            } })
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            assertTrue("factory restart did not begin recovery", firstRecovery.await(5, TimeUnit.SECONDS))
            // Enter cancels the invitation's recovery; another foreground restart
            // must not admit a replacement until that worker finishes cleanup.
            controller.enterRoom()
            assertTrue("enter did not cancel recovery", cleanup.await(5, TimeUnit.SECONDS))
            controller.onHostStopped()
            controller.onHostStarted()
            val pending = controller.awaitStateForTest("entry waits for invitation recovery cleanup") {
                it is HostingState.LiveRoom && it.foregroundRecoveryPending
            } as HostingState.LiveRoom
            assertEquals("a second recovery overlapped cancellation", 1, attempts.get())
            assertEquals("entry installed a collection before cleanup", 1, factories.get())
            assertEquals(false, pending.isPrimaryActionEnabled)
            releaseCleanup.countDown()
            assertTrue("recovery did not resume after cleanup", secondRecovery.await(5, TimeUnit.SECONDS))
            assertTrue(cleanupCompleted.get())
            assertEquals("replacement recovery raced old cleanup", false, overlappingRecovery.get())
        } finally {
            releaseCleanup.countDown()
            controller.endRoom()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun published_recovery_cannot_collect_after_observer_restarts_host() {
        val server = roomServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recovered = RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "", 1, "fixture")))
        val restarted = CountDownLatch(1)
        val observerRestarted = CountDownLatch(1)
        val resumedCollection = CountDownLatch(1)
        val collectionCleanup = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupCompleted = AtomicBoolean(false)
        val overlap = AtomicBoolean(false)
        val liveCollection = CountDownLatch(1)
        val mutation = QueueMutationContext(Dispatchers.Default.limitedParallelism(1))
        val factories = AtomicInteger()
        val recoveries = AtomicInteger()
        val restartedOnce = AtomicBoolean()
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            queueMutationContext = mutation, foregroundRecoveryContext = Dispatchers.IO,
            roomCollectionContext = Dispatchers.Unconfined,
            roomRepositoryFactory = {
                val generation = factories.incrementAndGet()
                object : RoomRepository {
                    override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                        if (generation == 2) liveCollection.countDown()
                        if (generation == 3) {
                            // This handler may collect before its observer's queued Stop is processed.
                            try {
                                resumedCollection.countDown()
                                kotlinx.coroutines.awaitCancellation()
                            } finally { withContext(NonCancellable + Dispatchers.IO) {
                                collectionCleanup.countDown()
                                check(releaseCleanup.await(10, TimeUnit.SECONDS))
                                cleanupCompleted.set(true)
                            } }
                        } else kotlinx.coroutines.awaitCancellation()
                    }
                }
            }, foregroundReconcilerFactory = { { _: String ->
                if (recoveries.incrementAndGet() == 1) RoomFetchResult.Success(recovered)
                else {
                    if (!cleanupCompleted.get()) overlap.set(true)
                    restarted.countDown()
                    kotlinx.coroutines.awaitCancellation()
                }
            } })
        val observation = controller.collectStatesForTest { state ->
            val live = state as? HostingState.LiveRoom
            if (live?.foregroundRecoveryPending == false &&
                (live.synchronization as? RoomSyncState.Active)?.room === recovered &&
                restartedOnce.compareAndSet(false, true)) {
                controller.onHostStopped()
                controller.onHostStarted()
                observerRestarted.countDown()
            }
        }
        try {
            controller.createRoom()
            controller.awaitCreatedForTest()
            controller.enterRoom()
            assertTrue("live collection never started", liveCollection.await(5, TimeUnit.SECONDS))
            controller.onHostStopped()
            controller.onHostStarted()
            assertTrue("observer did not queue restart", observerRestarted.await(5, TimeUnit.SECONDS))
            assertTrue("recovery did not finish starting its collection", resumedCollection.await(5, TimeUnit.SECONDS))
            assertTrue("queued Stop did not cancel that collection", collectionCleanup.await(5, TimeUnit.SECONDS))
            val stopped = controller.awaitStateForTest("queued Stop closes recovered room") {
                (it as? HostingState.LiveRoom)?.foregroundRecoveryPending == true
            } as HostingState.LiveRoom
            assertEquals("FIFO recovery may install exactly one collection", 3, factories.get())
            assertEquals("new recovery started before predecessor cleanup", 1, recoveries.get())
            assertEquals(false, stopped.isPrimaryActionEnabled)
            releaseCleanup.countDown()
            assertTrue("queued Start did not recover after full cleanup", restarted.await(5, TimeUnit.SECONDS))
            assertTrue(cleanupCompleted.get())
            assertEquals("restarted recovery overlapped old collection", false, overlap.get())
            assertEquals(3, factories.get())
            assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        } finally {
            releaseCleanup.countDown()
            observation.close()
            controller.endRoom()
            scope.cancel()
            server.shutdown()
        }
    }

    private fun roomServer() = MockWebServer().apply {
        start()
        enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
    }
}
