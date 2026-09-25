package com.qmix.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.junit.Assert.assertFalse
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
        val controller = HostSessionController(OkHttpClient(), settingsPersistence = persistence,
            roomCollectionScope = scope)
        runWithTeardown({
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
            assertEquals(HostingState.Error(UserMessage.INVALID_ENDPOINT, backend, "https://guest.example"),
                controller.state)
            assertEquals(0, saves.get())
            assertEquals(1, server.requestCount)
            assertTrue("rejected response must not expose its URL", !controller.state.toString().contains("attacker"))
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
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
        val controller = HostSessionController(OkHttpClient(), settingsPersistence = persistence,
            roomCollectionScope = scope)
        val invitations = AtomicInteger()
        val observation = controller.collectStatesForTest { if (it is HostingState.Invitation) invitations.incrementAndGet() }
        runWithTeardown({
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
            assertEquals(HostingState.Error(UserMessage.PERSISTENCE_ERROR, backend, "https://guest.example"),
                controller.state)
            assertEquals(0, invitations.get())
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
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
        val server = MockWebServer().apply { start(); enqueue(response()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val collecting = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val release = CountDownLatch(1)
        val primaryCalls = AtomicInteger()
        val controller = HostSessionController(OkHttpClient(),
            initialBackendUrl = server.url("/").toString(), initialGuestOrigin = "https://guest.example",
            roomCollectionScope = scope,
            roomRepositoryFactory = { object : RoomRepository {
                override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                    collecting.countDown()
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { withContext(NonCancellable + Dispatchers.IO) {
                        cleanup.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    } }
                }
            } }, primaryActionHandler = { primaryCalls.incrementAndGet() })
        runWithTeardown({
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
            controller.enterRoom()
            assertTrue(collecting.await(5, TimeUnit.SECONDS))
            controller.endRoom()
            assertTrue(cleanup.await(5, TimeUnit.SECONDS))
            assertEquals(HostingState.Ending, controller.state)
            assertFalse(controller.createRoom())
            controller.endRoom()
            controller.updateSettings("https://other.example", "https://other.example")
            controller.enterRoom()
            controller.onStartOrNext()
            assertEquals(LiveRoomBackResult.EXIT_ACTIVITY, controller.onBack())
            assertFalse(controller.onPlaybackEnded("one"))
            assertEquals(0, primaryCalls.get())
            assertEquals(HostingState.Ending, controller.state)
            release.countDown()
            assertTrue(controller.awaitSetupForTest())
            assertEquals(HostingState.Setup(server.url("/").toString().trimEnd('/'), "https://guest.example"),
                controller.state)
        }, { release.countDown() }, {
            if (controller.state !is HostingState.Setup) controller.endRoom()
            assertTrue(controller.awaitSetupForTest())
        }, { scope.cancel() }, { server.shutdown() })
    }

    @Test fun absent_session_commands_cannot_mutate_setup_or_issue_network_requests() {
        val server = MockWebServer().apply { start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val backend = server.url("/").toString().trimEnd('/')
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = backend,
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope)
        try {
            val setup = controller.state
            assertEquals(HostingState.Setup(backend, "https://guest.example"), setup)
            assertFalse(controller.confirmHttpWarning())
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
            assertFalse(controller.onPlaybackEnded("unknown"))
            assertEquals(LiveRoomBackResult.IGNORED, controller.onBack())
            assertEquals(setup, controller.state)
            assertEquals(0, server.requestCount)
        } finally {
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
