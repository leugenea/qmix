package com.qmix.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** qmix#312 INT-01: real coordinators on a posted, non-immediate Main start boundary.
 * Main is scheduler-controlled here; this is not native Handler/OS-thread evidence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostPublisherPostedStartTest {
    @Test fun play_stop_before_report_entry_recovers_ordinary_patch_after_final_pause_settles() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val host = PostedHost(this)
        var failure: Throwable? = null
        try {
            host.start()
            host.controller.pausePlayback()
            host.awaitLive { it.playback.status == LocalPlaybackStatus.PAUSED }
            runCurrent()
            assertEquals(listOf(PlayerReportState.PAUSED), host.reports.map { it.state })
            assertTrue("initial ordinary PAUSED has fully completed", host.ordinaryJobs.single().isCompleted)
            // Both ingress calls run in one Main callback before the posted host turn.
            withContext(Dispatchers.Main) {
                host.controller.resumePlayback()
                host.controller.onHostStopped()
                host.controller.onHostStarted()
            }
            host.recoveryStarted.await()
            host.finalPauseStarted.await()
            runCurrent()
            assertEquals("PLAY ran locally, but its cancelled posted report never entered", 2, host.engine.plays)
            assertEquals(listOf(PlayerReportState.PAUSED, PlayerReportState.PAUSED), host.reports.map { it.state })
            val finalPause = host.finalScope.coroutineContext[Job]!!.children.single()
            assertFalse("independent final PAUSED is held", finalPause.isCompleted)
            host.recovery.complete(RoomFetchResult.Success(host.room))
            host.awaitLive { !it.foregroundRecoveryPending }
            host.controller.onInvite()
            host.awaitLive { it.invitationVisible }
            runCurrent()
            assertEquals("ordinary recovery report cannot overtake final PAUSED", 2, host.reports.size)
            host.releaseFinalPause.complete(Unit)
            finalPause.join()
            runCurrent()
            assertTrue("process-owned final PAUSED fully settled", finalPause.isCompleted)
            assertEquals("fresh same-track recovery must resume ordinary PATCH delivery",
                listOf(PlayerReportState.PAUSED, PlayerReportState.PAUSED, PlayerReportState.PAUSED),
                host.reports.map { it.state })
            host.controller.resumePlayback()
            host.awaitLive { it.playback.status == LocalPlaybackStatus.BUFFERING }
            runCurrent()
            assertEquals("subsequent eligible PLAY emits an ordinary PATCH", PlayerReportState.PLAYING, host.reports.last().state)
            assertEquals(4, host.reports.size)
            assertTrue(host.ordinaryJobs.all { it.isCompleted })
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { host.close() } catch (error: Throwable) {
                if (failure == null) throw error
                failure.addSuppressed(error)
            } finally { Dispatchers.resetMain() }
        }
    }

    private class PostedHost(test: TestScope) {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
            start()
        }
        val finalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutation = QueueMutationContext(Dispatchers.Main) { true }
        val room = RoomState("ABCD", CurrentTrack("one", 0, "playing", "One", ""), emptyList())
        val engine = PostedEngine()
        val reports = java.util.concurrent.CopyOnWriteArrayList<PlayerReport>()
        val ordinaryJobs = mutableListOf<Job>()
        val recoveryStarted = CompletableDeferred<Unit>()
        val recovery = CompletableDeferred<RoomFetchResult>()
        val finalPauseStarted = CompletableDeferred<Unit>()
        val releaseFinalPause = CompletableDeferred<Unit>()
        val controller = HostSessionController(OkHttpClient(),
            initialBackendUrl = server.url("/").toString(), initialGuestOrigin = "https://guest.example",
            roomCollectionScope = test.backgroundScope, queueMutationContext = mutation,
            roomRepositoryFactory = { object : RoomRepository {
                override fun observe(roomCode: String) = flow {
                    emit(RoomSyncState.Active(roomCode, room, Freshness.FRESH, LiveConnection.CONNECTED))
                    kotlinx.coroutines.awaitCancellation()
                }
            } },
            foregroundReconcilerFactory = { { _: String ->
                recoveryStarted.complete(Unit)
                recovery.await()
            } },
            queueCoordinatorFactory = { _, credentials, observer, owner ->
                QueueAdvancementCoordinator(credentials.code, credentials.hostToken,
                    QueueAdvanceCommand { _, _ -> error("no queue advancement expected") },
                    QueueRoomReconciler { RoomFetchResult.Failure }, observer, owner, mutation)
            },
            playbackCoordinatorFactory = { _, credentials, observer, ended, owner ->
                AuthoritativePlaybackCoordinator(credentials.code, "https://example/stream", engine,
                    { RoomFetchResult.Failure }, owner, mutation, ended, observer,
                    statePublisherFactory = { listener ->
                        PlayerStatePublisher(credentials.code, credentials.hostToken, ::report,
                            owner, mutation, finalScope, listener)
                    })
            })

        private suspend fun report(code: String, token: String, report: PlayerReport): PlayerReportResult {
            assertEquals("ABCD", code)
            assertEquals("fixture", token)
            reports += report
            if (reports.size == 2) {
                finalPauseStarted.complete(Unit)
                releaseFinalPause.await()
            } else ordinaryJobs += requireNotNull(currentCoroutineContext()[Job])
            return PlayerReportResult.ACCEPTED
        }

        suspend fun start() {
            controller.createRoom()
            awaitState { it is HostingState.Invitation }
            controller.enterRoom()
            awaitLive { it.playback.trackId == "one" }
        }

        suspend fun awaitLive(predicate: (HostingState.LiveRoom) -> Boolean) =
            awaitState { it is HostingState.LiveRoom && predicate(it) }

        private suspend fun awaitState(predicate: (HostingState) -> Boolean) =
            withContext(Dispatchers.IO) {
                withTimeout(5_000) { controller.states.first(predicate) }
            }

        suspend fun close() {
            releaseFinalPause.complete(Unit)
            recovery.complete(RoomFetchResult.Failure)
            controller.abandonRoom()
            try { awaitState { it is HostingState.Setup } } finally {
                finalScope.cancel()
                server.shutdown()
            }
        }
    }

    private class PostedEngine : PlaybackEngine {
        override val state = PlaybackState()
        var plays = 0
        override fun prepare(media: PlaybackMedia) = Unit
        override fun play() { plays++ }
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
        override fun addListener(listener: (PlaybackState) -> Unit) = Unit
        override fun removeListener(listener: (PlaybackState) -> Unit) = Unit
    }
}
