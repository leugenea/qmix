package com.qmix.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
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
                    } }
                } else {
                    secondRecovery.countDown()
                    RoomFetchResult.Failure
                }
            } })
        try {
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
            controller.enterRoom()
            assertTrue("factory restart did not begin recovery", firstRecovery.await(5, TimeUnit.SECONDS))
            controller.onHostStopped()
            controller.onHostStarted()
            assertTrue("stop lost the running recovery", cleanup.await(5, TimeUnit.SECONDS))
            // Recovery uses Unconfined: any admission on the mutation lane starts inline.
            // Drain that lane while cleanup is held, then inspect the attempted starts.
            mutation.run { Unit }
            assertEquals("a second recovery overlapped cancellation", 1, attempts.get())
            assertEquals(1L, secondRecovery.count)
            releaseCleanup.countDown()
            assertTrue("recovery did not resume after cleanup", secondRecovery.await(5, TimeUnit.SECONDS))
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
        val staleCollection = CountDownLatch(1)
        val mutation = QueueMutationContext(Dispatchers.Default.limitedParallelism(1))
        val factories = AtomicInteger()
        val recoveries = AtomicInteger()
        val restartedOnce = AtomicBoolean()
        val events = ConcurrentLinkedQueue<String>()
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope,
            queueMutationContext = mutation, foregroundRecoveryContext = Dispatchers.IO,
            roomRepositoryFactory = {
                events.add("factory ${factories.incrementAndGet()} observer=${restartedOnce.get()}")
                if (factories.get() > 1) staleCollection.countDown()
                object : RoomRepository {
                    override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                        kotlinx.coroutines.awaitCancellation()
                    }
                }
            }, foregroundReconcilerFactory = { { _: String ->
                events.add("recovery ${recoveries.incrementAndGet()}")
                if (recoveries.get() == 1) RoomFetchResult.Success(recovered)
                else {
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
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
            controller.enterRoom()
            controller.onHostStopped()
            controller.onHostStarted()
            assertTrue("observer did not restart the host", observerRestarted.await(5, TimeUnit.SECONDS))
            // The observer runs inside the old recovery's publication. Its signal puts
            // this mutation-lane drain behind the rest of that completion callback.
            mutation.run { Unit }
            assertTrue("observer did not start a new recovery", restarted.await(5, TimeUnit.SECONDS))
            assertEquals("stale recovery started a collection: $events", 1L, staleCollection.count)
            assertEquals(1, factories.get())
            assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        } finally {
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
