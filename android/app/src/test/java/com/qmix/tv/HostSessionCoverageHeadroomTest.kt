package com.qmix.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** qmix#217: repeatable coverage for host-session error and teardown boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSessionCoverageHeadroomTest {
    @Test fun server_issued_cross_origin_invite_is_rejected_without_persisting_credentials() {
        val server = MockWebServer().apply {
            start()
            enqueue(response("//attacker.example/r/ABCD"))
            enqueue(response("/r/ABCD"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val saves = AtomicInteger()
        val backend = server.url("/").toString().trimEnd('/')
        val persistence = object : EndpointSettingsPersistence {
            override fun load() = EndpointSettings(backend, "https://guest.example")
            override fun save(settings: EndpointSettings) { saves.incrementAndGet() }
            override fun isHttpWarningAcknowledged() = true
            override fun acknowledgeHttpWarning() = Unit
        }
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(Dispatchers.Default.limitedParallelism(1)), httpClient = OkHttpClient(), settingsPersistence = persistence,
            roomCollectionScope = scope)
        runWithTeardown({
            controller.createRoom()
            controller.awaitStateForTest("cross-origin invite rejected") { it is HostingState.Error }
            assertEquals(HostingState.Error(UserMessage.INVALID_ENDPOINT, backend, "https://guest.example"),
                controller.state)
            assertEquals(0, saves.get())
            assertEquals(1, server.requestCount)
            assertTrue("rejected response must not expose its URL", !controller.state.toString().contains("attacker"))
            controller.createRoom()
            controller.awaitStateForTest("valid retry invitation") { it is HostingState.Invitation }
            assertEquals(HostingState.Invitation(GuestInvite("ABCD", "https://guest.example/r/ABCD")),
                controller.state)
            assertEquals(1, saves.get())
            assertEquals(2, server.requestCount)
        }, {
            controller.endRoom()
            assertTrue(controller.awaitSetupForTest())
        }, { scope.cancel() }, { server.shutdown() })
    }

    @Test fun failed_persistence_does_not_publish_an_invitation_and_allows_a_later_retry() {
        val server = MockWebServer().apply { start(); enqueue(response()); enqueue(response()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val saves = AtomicInteger()
        val backend = server.url("/").toString().trimEnd('/')
        val persistence = object : EndpointSettingsPersistence {
            override fun load() = EndpointSettings(backend, "https://guest.example")
            override fun save(settings: EndpointSettings) {
                if (saves.incrementAndGet() == 1) throw IllegalStateException("disk unavailable")
            }
            override fun isHttpWarningAcknowledged() = true
            override fun acknowledgeHttpWarning() = Unit
        }
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(Dispatchers.Default.limitedParallelism(1)), httpClient = OkHttpClient(), settingsPersistence = persistence,
            roomCollectionScope = scope)
        val invitations = AtomicInteger()
        val invitationPublished = CountDownLatch(1)
        val observation = controller.collectStatesForTest {
            if (it is HostingState.Invitation) {
                invitations.incrementAndGet()
                invitationPublished.countDown()
            }
        }
        runWithTeardown({
            controller.createRoom()
            controller.awaitStateForTest("save failure rejects creation") { it is HostingState.Error }
            assertEquals(HostingState.Error(UserMessage.PERSISTENCE_ERROR, backend, "https://guest.example"),
                controller.state)
            assertEquals(0, invitations.get())
            controller.createRoom()
            controller.awaitStateForTest("successful persistence retry invitation") { it is HostingState.Invitation }
            assertTrue("invitation observer did not receive retry", invitationPublished.await(5, TimeUnit.SECONDS))
            assertTrue(controller.state is HostingState.Invitation)
            assertEquals(1, invitations.get())
            assertEquals(2, saves.get())
            assertEquals(2, server.requestCount)
        }, { observation.close() }, {
            controller.endRoom()
            assertTrue(controller.awaitSetupForTest())
        }, { scope.cancel() }, { server.shutdown() })
    }

    @Test fun ending_room_rejects_reentrant_actions_until_collection_cleanup_completes() {
        val server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                    request.method == "POST" && request.path == "/rooms" -> response()
                    request.method == "DELETE" && request.path == "/rooms/ABCD" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val invitationCollecting = CountDownLatch(1)
        val collecting = CountDownLatch(1)
        val collections = AtomicInteger()
        val cleanup = CountDownLatch(1)
        val release = CountDownLatch(1)
        val primaryCalls = AtomicInteger()
        val cleanupCompleted = AtomicBoolean(false)
        val prematureCollection = AtomicBoolean(false)
        val ignoredBackExits = AtomicInteger()
        val replacementCollecting = CountDownLatch(1)
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(Dispatchers.Default.limitedParallelism(1)), httpClient = OkHttpClient(),
            initialBackendUrl = server.url("/").toString(), initialGuestOrigin = "https://guest.example",
            roomCollectionScope = scope,
            roomRepositoryFactory = { object : RoomRepository {
                override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                    val generation = collections.incrementAndGet()
                    if (generation > 2) {
                        if (!cleanupCompleted.get()) prematureCollection.set(true)
                        replacementCollecting.countDown()
                    }
                    if (generation == 1) {
                        // Invitation monitoring ends on entry, without holding cleanup.
                        invitationCollecting.countDown()
                        kotlinx.coroutines.awaitCancellation()
                    } else {
                        try {
                            if (generation == 2) emit(RoomSyncState.Active(roomCode,
                                RoomState(roomCode, null, listOf(QueuedTrack("one", "https://example/one", "One", "", 1, "fixture"))),
                                Freshness.FRESH, LiveConnection.CONNECTED))
                            collecting.countDown()
                            kotlinx.coroutines.awaitCancellation()
                        } finally { withContext(NonCancellable + Dispatchers.IO) {
                            cleanup.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            cleanupCompleted.set(true)
                        } }
                    }
                }
            } }, primaryActionHandler = { primaryCalls.incrementAndGet() })
        runWithTeardown({
            controller.createRoom()
            controller.awaitCreatedForTest()
            assertTrue(invitationCollecting.await(5, TimeUnit.SECONDS))
            controller.enterRoom()
            assertTrue(collecting.await(5, TimeUnit.SECONDS))
            controller.awaitStateForTest("eligible live room before Ending cleanup") {
                (it as? HostingState.LiveRoom)?.isPrimaryActionEnabled == true
            }
            controller.endRoom()
            assertTrue(cleanup.await(5, TimeUnit.SECONDS))
            assertEquals(HostingState.Ending, controller.state)
            // These controls are processed after cleanup against Setup, not rejected at offer time.
            controller.endRoom()
            controller.enterRoom()
            controller.onStartOrNext()
            controller.onBack { ignoredBackExits.incrementAndGet() }
            controller.onPlaybackEnded("one")
            controller.updateSettings(server.url("/").toString().trimEnd('/'), "https://other.example")
            controller.createRoom() // FIFO admits this only after the predecessor has fully joined.
            assertEquals(0, primaryCalls.get())
            assertEquals(HostingState.Ending, controller.state)
            assertEquals(2, collections.get())
            release.countDown()
            val replacement = controller.awaitStateForTest("queued creation after Ending cleanup") {
                (it as? HostingState.Invitation)?.invite?.guestUrl == "https://other.example/r/ABCD"
            }
            assertEquals(HostingState.Invitation(GuestInvite("ABCD", "https://other.example/r/ABCD")), replacement)
            assertTrue("replacement collection did not start", replacementCollecting.await(5, TimeUnit.SECONDS))
            assertTrue("old collection cleanup did not finish", cleanupCompleted.get())
            assertEquals("new collection overlapped old cleanup", false, prematureCollection.get())
            assertEquals("out-of-session controls reached primary handler", 0, primaryCalls.get())
            assertEquals("Back in Setup must not exit", 0, ignoredBackExits.get())
            val requests = (1..3).map { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            assertEquals(2, requests.count { it.method == "POST" && it.path == "/rooms" })
            assertEquals(1, requests.count { it.method == "DELETE" && it.path == "/rooms/ABCD" })
            assertEquals(3, server.requestCount)
        }, { release.countDown() }, {
            if (controller.state !is HostingState.Setup) controller.endRoom()
            assertTrue(controller.awaitSetupForTest())
        }, { scope.cancel() }, { server.shutdown() })
    }

    @Test fun absent_session_commands_cannot_mutate_setup_or_issue_network_requests() {
        val server = MockWebServer().apply { start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val backend = server.url("/").toString().trimEnd('/')
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(Dispatchers.Default.limitedParallelism(1)), httpClient = OkHttpClient(), initialBackendUrl = backend,
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope)
        val seen = ConcurrentLinkedQueue<HostingState>()
        val observation = controller.collectStatesForTest { seen.add(it) }
        val ignoredBackExits = AtomicInteger()
        try {
            val setup = controller.state
            assertEquals(HostingState.Setup(backend, "https://guest.example"), setup)
            controller.confirmHttpWarning()
            controller.cancelHttpWarning()
            controller.enterRoom()
            controller.onHostStopped()
            controller.onHostStarted()
            controller.setCommandPending(true)
            controller.onInvite()
            controller.onStartOrNext()
            controller.onPlayPause()
            controller.onPlay()
            controller.onPause()
            controller.onSeekBy(-10_000)
            controller.onRetryCurrent()
            controller.pausePlayback()
            controller.resumePlayback()
            controller.retryCurrent()
            controller.onPlaybackEnded("unknown")
            controller.onBack { ignoredBackExits.incrementAndGet() }
            val configured = HostingState.Setup(backend, "https://configured.example")
            controller.updateSettings(backend, configured.guestOrigin)
            controller.awaitStateForTest("configuration after absent-session commands") { it == configured }
            assertTrue("absent commands published a session state: $seen", seen.all { it == setup || it == configured })
            assertEquals("Back without a live room invoked exit", 0, ignoredBackExits.get())
            assertEquals(0, server.requestCount)
        } finally {
            observation.close()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test fun teardown_failures_preserve_the_body_failure_and_still_run_every_cleanup() {
        val primary = AssertionError("body")
        val teardown = AssertionError("teardown")
        var cleaned = false
        val thrown = org.junit.Assert.assertThrows(AssertionError::class.java) {
            runWithTeardown({ throw primary }, { throw teardown }, { cleaned = true })
        }
        assertSame(primary, thrown)
        assertEquals(listOf(teardown), thrown.suppressed.toList())
        assertTrue(cleaned)
        assertSame(teardown, org.junit.Assert.assertThrows(AssertionError::class.java) {
            runWithTeardown({}, { throw teardown })
        })
    }

    private fun runWithTeardown(body: () -> Unit, vararg teardown: () -> Unit) {
        var failure: Throwable? = null
        try {
            body()
        } catch (error: Throwable) {
            failure = error
        } finally {
            for (step in teardown) {
                try {
                    step()
                } catch (error: Throwable) {
                    val primary = failure
                    if (primary == null) failure = error
                    else if (primary !== error) primary.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun response(url: String = "/r/ABCD") = MockResponse().setResponseCode(201)
        .setBody("""{"code":"ABCD","host_token":"fixture","url":"$url"}""")
}
