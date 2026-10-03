package com.qmix.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** qmix#312: host observers offer commands without nested transitions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSequentialLoopTest {
    @Test fun fresh_observer_command_is_processed_after_observer_returns() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}"""))
            start()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failure = AtomicReference<Throwable?>()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val controller = HostSessionController(OkHttpClient(),
                initialBackendUrl = server.url("/").toString(), initialGuestOrigin = "https://guest.example",
                roomCollectionScope = scope, queueMutationContext = QueueMutationContext(dispatcher),
                roomRepositoryFactory = { object : RoomRepository {
                    override fun observe(roomCode: String) = flow {
                        emit(RoomSyncState.Active(roomCode, RoomState(roomCode, null, emptyList()),
                            Freshness.FRESH, LiveConnection.CONNECTED))
                        kotlinx.coroutines.awaitCancellation()
                    }
                } })
            val observer = controller.collectStatesForTest { state ->
                val current = state as? HostingState.LiveRoom
                if ((current?.synchronization as? RoomSyncState.Active)?.freshness == Freshness.FRESH &&
                    !current.commandPending) {
                    try {
                        controller.setCommandPending(true)
                        assertEquals("observer offer must not mutate host state inline", state, controller.state)
                    } catch (error: Throwable) { failure.set(error) }
                }
            }
            try {
                controller.createRoom()
                if (controller.state is HostingState.HttpWarning) controller.confirmHttpWarning()
                // InitialEndpointSettingsPersistence acknowledges configured endpoints.
                controller.awaitCreatedForTest()
                controller.enterRoom()
                controller.awaitRoomStateForTest { it.commandPending }
                failure.get()?.let { throw it }
            } finally {
                observer.close()
                controller.abandonRoom()
                controller.awaitSetupForTest()
                scope.cancel()
                server.shutdown()
            }
        }
    }
    @Test fun stop_start_joins_real_command_cleanup_before_fresh_recovery() = withLiveHost { host ->
        host.controller.onStartOrNext()
        host.await(host.commandStarted, "queue command started")
        host.controller.onHostStopped()
        host.controller.onHostStarted()
        host.await(host.commandCleanup, "cancelled command cleanup started")
        // Recovery-pending is published before coordinator suspension. A cancelled
        // IO command can enter its finally before playback has published PAUSED.
        val stopped = host.controller.awaitStateForTest("Stop published suspended playback during command cleanup") {
            it is HostingState.LiveRoom && it.foregroundRecoveryPending &&
                it.playback.status == LocalPlaybackStatus.PAUSED
        } as HostingState.LiveRoom
        assertFalse(stopped.commandPending)
        assertFalse(stopped.isPrimaryActionEnabled)
        assertEquals(LocalPlaybackStatus.PAUSED, stopped.playback.status)
        host.assertDispatcherIsRunnable()
        assertEquals("queued Start cannot pass owned command cleanup", 0, host.recoveryCalls.get())
        host.releaseCommand.complete(Unit)
        host.await(host.recoveryStarted, "fresh recovery GET started after join")
        assertTrue(host.commandFinished.get())
        host.controller.resumePlayback()
        host.controller.onPlaybackEnded("one")
        host.recoveryResults.trySend(RoomFetchResult.Success(host.room))
        host.controller.awaitRoomStateForTest { !it.foregroundRecoveryPending }
        // pending=false precedes reconciliation; a later FIFO UI transition is
        // the receipt that the complete recovery handler has returned.
        host.controller.onInvite()
        host.controller.awaitRoomStateForTest { it.invitationVisible }
        assertEquals("background ENDED did not advance", 1, host.commands.get())
        assertEquals("recovery does not autoplay", 1, host.engine.plays.get())
        assertTrue(host.engine.pauses.get() > 0)
    }

    @Test fun recovered_new_selection_rejects_old_ended_event_and_keeps_real_pause_controls() = withLiveHost { host ->
        host.controller.onHostStopped()
        host.controller.awaitRoomStateForTest { it.foregroundRecoveryPending }
        host.controller.onHostStarted()
        host.await(host.recoveryStarted, "new-selection fresh GET started")
        val replacement = host.room.copy(current = CurrentTrack("two", 0, "playing", "Two", ""))
        host.recoveryResults.trySend(RoomFetchResult.Success(replacement))
        host.controller.awaitRoomStateForTest { !it.foregroundRecoveryPending && it.playback.trackId == "two" }
        host.controller.onPlaybackEnded("one")
        host.controller.resumePlayback()
        host.controller.awaitRoomStateForTest { it.playback.status == LocalPlaybackStatus.BUFFERING }
        assertFalse("obsolete selection must not open a queue command", (host.controller.state as HostingState.LiveRoom).commandPending)
        assertEquals(0, host.commands.get())
        assertEquals(listOf("one", "two"), host.engine.preparations.toList())
        assertEquals(2, host.engine.plays.get())
    }

    @Test fun end_recreate_waits_for_real_command_cleanup_but_delete_does_not() = withLiveHost { host ->
        host.server.enqueue(host.createdResponse())
        host.controller.onStartOrNext()
        host.await(host.commandStarted, "old command entered")
        host.controller.endRoom()
        host.await(host.commandCleanup, "End owns cancelled command cleanup")
        host.controller.awaitStateForTest("Ending while old command is held") { it is HostingState.Ending }
        host.await(host.deleteStarted, "independent authenticated DELETE admitted")
        assertEquals("ABCD" to "fixture", host.deleted.get())
        host.controller.createRoom()
        host.assertDispatcherIsRunnable()
        assertEquals("replacement POST waits for the complete old tree", 1, host.server.requestCount)
        host.releaseCommand.complete(Unit)
        host.controller.awaitStateForTest("fresh recreated invitation") { it is HostingState.Invitation }
        assertTrue(host.commandFinished.get())
        assertEquals(2, host.server.requestCount)
        host.await(host.deleteStarted, "authenticated explicit-End DELETE executed")
        assertEquals(1, host.deletes.get())
    }

    @Test fun used_missing_then_queued_stop_start_cleans_real_coordinators_before_manual_replace() = withLiveHost { host ->
        host.server.enqueue(host.createdResponse())
        host.controller.onStartOrNext()
        host.await(host.commandStarted, "used-room command started")
        host.publishMissing()
        host.await(host.commandCleanup, "Missing closes real command worker")
        val missing = host.controller.awaitRoomStateForTest { it.synchronization is RoomSyncState.Missing }
        assertFalse(missing.isPrimaryActionEnabled)
        assertFalse(missing.commandPending)
        host.controller.onHostStopped()
        host.controller.onHostStarted()
        host.controller.onPlaybackEnded("one")
        host.controller.onNewRoom()
        host.assertDispatcherIsRunnable()
        assertEquals("used Missing cannot auto-create while cleanup is held", 1, host.server.requestCount)
        host.releaseCommand.complete(Unit)
        host.controller.awaitStateForTest("manual replacement after Missing and queued lifecycle events") {
            it is HostingState.Invitation
        }
        assertTrue(host.commandFinished.get())
        assertEquals("Missing/Stop must not issue authenticated DELETE", 0, host.deletes.get())
        assertEquals("detached Missing room must not recover", 0, host.recoveryCalls.get())
        assertEquals("obsolete ENDED did not request another command", 1, host.commands.get())
    }

    @Test fun processed_stop_rejects_manual_missing_replacement_until_foreground_return() = withLiveHost { host ->
        host.server.enqueue(host.createdResponse())
        host.publishMissing()
        host.controller.awaitRoomStateForTest { it.synchronization is RoomSyncState.Missing }
        host.controller.onHostStopped()
        host.controller.setCommandPending(true)
        host.controller.awaitRoomStateForTest { it.commandPending }
        // This public FIFO transition follows full Missing cleanup and processed Stop.
        host.controller.setCommandPending(false)
        host.controller.awaitRoomStateForTest { !it.commandPending }
        host.controller.onNewRoom()
        host.controller.setCommandPending(true)
        val outcome = host.controller.awaitStateForTest("background manual decision processed") {
            it is HostingState.Invitation || (it is HostingState.LiveRoom && it.commandPending)
        }
        assertTrue("processed Stop must retain Missing, not POST a background replacement", outcome is HostingState.LiveRoom)
        assertEquals(1, host.server.requestCount)
        host.controller.onHostStarted()
        host.controller.onNewRoom()
        host.controller.awaitStateForTest("manual replacement is available after foreground return") {
            it is HostingState.Invitation
        }
        assertEquals(2, host.server.requestCount)
        assertEquals(0, host.deletes.get())
        assertEquals("detached Missing room is not recovered", 0, host.recoveryCalls.get())
    }

    @Test fun entering_during_invitation_recovery_restarts_fresh_fetch_for_real_coordinators() = withLiveHost { host ->
        host.controller.abandonRoom()
        host.controller.awaitSetupForTest()
        host.server.enqueue(host.createdResponse())
        host.controller.createRoom()
        host.controller.awaitCreatedForTest()
        host.controller.onHostStopped()
        host.controller.awaitStateForTest("invitation recovery-pending on Stop") {
            (it as? HostingState.Invitation)?.foregroundRecoveryPending == true
        }
        host.controller.onHostStarted()
        assertEquals(1, host.awaitRecoveryRequest())
        host.controller.enterRoom()
        assertEquals("Enter must replace cancelled invitation GET with a live-room fresh GET", 2, host.awaitRecoveryRequest())
        host.recoveryResults.trySend(RoomFetchResult.Success(host.room))
        host.controller.awaitRoomStateForTest {
            !it.foregroundRecoveryPending && it.playback.trackId == "one"
        }
        assertEquals("recovery never starts playback automatically", 1, host.engine.plays.get())
    }

    @Test fun failed_invitation_recovery_retries_from_fresh_monitor_before_enter() = withLiveHost { host ->
        host.controller.abandonRoom()
        host.controller.awaitSetupForTest()
        host.server.enqueue(host.createdResponse())
        host.controller.createRoom()
        host.controller.awaitCreatedForTest()
        host.nextObservation("new invitation collection started")
        host.controller.onHostStopped()
        host.controller.awaitStateForTest("invitation suspended") {
            (it as? HostingState.Invitation)?.foregroundRecoveryPending == true
        }
        host.controller.onHostStarted()
        assertEquals(1, host.awaitRecoveryRequest())
        host.recoveryResults.trySend(RoomFetchResult.Failure)
        val retryMonitor = host.nextObservation("failed invitation recovery restarts monitoring")
        retryMonitor.trySend(RoomSyncState.Active("ABCD", host.room, Freshness.FRESH, LiveConnection.CONNECTED))
        assertEquals("fresh invitation monitor must request genuine fresh recovery", 2, host.awaitRecoveryRequest())
        host.recoveryResults.trySend(RoomFetchResult.Success(host.room))
        host.controller.awaitStateForTest("invitation recovered after transient failure") {
            (it as? HostingState.Invitation)?.foregroundRecoveryPending == false
        }
        host.nextObservation("recovered invitation monitor started")
        host.controller.enterRoom()
        host.nextObservation("recovered live monitor started").trySend(
            RoomSyncState.Active("ABCD", host.room, Freshness.FRESH, LiveConnection.CONNECTED))
        host.controller.awaitRoomStateForTest { !it.foregroundRecoveryPending && it.playback.trackId == "one" }
    }

    @Test fun playback_close_failure_does_not_skip_owned_join_or_strand_next_creation() = withLiveHost { host ->
        host.server.enqueue(host.createdResponse())
        host.controller.onStartOrNext()
        host.await(host.commandStarted, "command owned before failing playback close")
        host.engine.failPause = true
        host.controller.endRoom()
        host.await(host.engine.pauseFailed, "playback close reached its controlled exception")
        host.await(host.commandCleanup, "owned command cleanup survives playback close exception")
        host.controller.createRoom()
        host.assertDispatcherIsRunnable()
        assertEquals(HostingState.Ending, host.controller.state)
        assertEquals("creation cannot pass the owned join after a close exception", 1, host.server.requestCount)
        host.releaseCommand.complete(Unit)
        host.controller.awaitStateForTest("creation remains usable after playback close failure") {
            it is HostingState.Invitation
        }
        assertEquals(2, host.server.requestCount)
        host.await(host.deleteStarted, "authenticated explicit-End DELETE executed")
        assertEquals(1, host.deletes.get())
    }

    @Test fun back_exit_callback_failure_does_not_close_the_fifo_inbox() = withLiveHost { host ->
        host.server.enqueue(host.createdResponse())
        host.controller.onBack {
            host.controller.createRoom()
            error("controlled Activity exit callback failure")
        }
        host.controller.awaitStateForTest("observer-offered creation after failing Back UI effect") {
            it is HostingState.Invitation
        }
        assertEquals(2, host.server.requestCount)
        host.await(host.deleteStarted, "authenticated explicit-End DELETE executed")
        assertEquals(1, host.deletes.get())
    }

    private fun withLiveHost(test: (LiveHost) -> Unit) {
        val host = LiveHost()
        var failure: Throwable? = null
        try { host.start(); test(host) } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { host.close() } catch (error: Throwable) {
                if (failure == null) throw error
                failure.addSuppressed(error)
            }
        }
    }

    /** Concrete real-coordinator fixture; all gates have a bounded watchdog and unconditional release. */
    private class LiveHost : AutoCloseable {
        val server = MockWebServer().apply { start() }
        private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val mutation = QueueMutationContext(dispatcher)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = LoopEngine()
        val room = RoomState("ABCD", CurrentTrack("one", 0, "playing", "One", ""),
            listOf(QueuedTrack("next", "https://example/next", "Next", "", 1, "fixture")))
        val commands = AtomicInteger()
        val commandStarted = CountDownLatch(1)
        val commandCleanup = CountDownLatch(1)
        val commandFinished = java.util.concurrent.atomic.AtomicBoolean()
        val releaseCommand = CompletableDeferred<Unit>()
        val recoveryCalls = AtomicInteger()
        val recoveryStarted = CountDownLatch(1)
        private val recoveryRequests = LinkedBlockingQueue<Int>()
        val recoveryResults = Channel<RoomFetchResult>(Channel.UNLIMITED)
        val deleteStarted = CountDownLatch(1)
        val deletes = AtomicInteger()
        val deleted = AtomicReference<Pair<String, String>>()
        private val observations = LinkedBlockingQueue<Channel<RoomSyncState>>()
        private lateinit var liveObservation: Channel<RoomSyncState>
        val controller = HostSessionController(OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomCollectionScope = scope, roomCloseScope = closeScope,
            queueMutationContext = mutation,
            roomRepositoryFactory = { object : RoomRepository {
                override fun observe(roomCode: String) = flow {
                    val channel = Channel<RoomSyncState>(Channel.UNLIMITED)
                    observations.put(channel)
                    try { for (sync in channel) emit(sync) } finally { channel.close() }
                }
            } },
            foregroundReconcilerFactory = { { _: String ->
                recoveryRequests.put(recoveryCalls.incrementAndGet())
                recoveryStarted.countDown()
                recoveryResults.receive()
            } },
            queueCoordinatorFactory = { _, credentials, observer, owner ->
                QueueAdvancementCoordinator(credentials.code, credentials.hostToken,
                    QueueAdvanceCommand { _, _ ->
                        commands.incrementAndGet()
                        commandStarted.countDown()
                        try { kotlinx.coroutines.awaitCancellation() } finally {
                            commandCleanup.countDown()
                            withContext(NonCancellable) { withTimeout(10_000) { releaseCommand.await() } }
                            commandFinished.set(true)
                        }
                    }, QueueRoomReconciler { RoomFetchResult.Failure }, observer, owner, mutation)
            },
            playbackCoordinatorFactory = { _, credentials, observer, ended, owner ->
                AuthoritativePlaybackCoordinator(credentials.code, "https://example/stream", engine,
                    { RoomFetchResult.Failure }, owner, mutation, ended, observer)
            },
            roomCloseCommandFactory = { RoomCloseCommand { code, token ->
                deleted.set(code to token)
                deletes.incrementAndGet()
                deleteStarted.countDown()
            } })

        fun createdResponse() = MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"fixture","url":"/r/ABCD"}""")

        fun start() {
            server.enqueue(createdResponse())
            controller.createRoom()
            controller.awaitCreatedForTest()
            checkNotNull(observations.poll(5, TimeUnit.SECONDS)) { "invitation subscription" }
            controller.enterRoom()
            liveObservation = checkNotNull(observations.poll(5, TimeUnit.SECONDS)) { "live subscription" }
            liveObservation.trySend(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))
            controller.awaitRoomStateForTest { it.isPrimaryActionEnabled && it.playback.trackId == "one" }
        }

        fun nextObservation(step: String): Channel<RoomSyncState> = checkNotNull(observations.poll(5, TimeUnit.SECONDS)) {
            "$step: last state=${controller.state}"
        }

        fun awaitRecoveryRequest(): Int = checkNotNull(recoveryRequests.poll(5, TimeUnit.SECONDS)) {
            "fresh recovery request did not start; last state=${controller.state}"
        }

        fun publishMissing() { liveObservation.trySend(RoomSyncState.Missing("ABCD")) }

        fun await(latch: CountDownLatch, step: String) {
            assertTrue("$step: last state=${controller.state}", latch.await(5, TimeUnit.SECONDS))
        }

        fun assertDispatcherIsRunnable() {
            val available = CountDownLatch(1)
            scope.launch(dispatcher) { available.countDown() }
            await(available, "Main-equivalent dispatcher remains runnable during suspend join")
        }

        override fun close() {
            engine.failPause = false
            releaseCommand.complete(Unit)
            recoveryResults.trySend(RoomFetchResult.Failure)
            controller.abandonRoom()
            try { controller.awaitSetupForTest() } finally {
                scope.cancel()
                closeScope.cancel()
                dispatcher.close()
                server.shutdown()
            }
        }
    }

    private class LoopEngine : PlaybackEngine {
        override val state = PlaybackState()
        val preparations = java.util.concurrent.CopyOnWriteArrayList<String>()
        val plays = AtomicInteger()
        val pauses = AtomicInteger()
        val pauseFailed = CountDownLatch(1)
        @Volatile var failPause = false
        override fun prepare(media: PlaybackMedia) { preparations += media.trackId }
        override fun play() { plays.incrementAndGet() }
        override fun pause() {
            pauses.incrementAndGet()
            if (failPause) {
                pauseFailed.countDown()
                error("controlled playback close failure")
            }
        }
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
        override fun addListener(listener: (PlaybackState) -> Unit) = Unit
        override fun removeListener(listener: (PlaybackState) -> Unit) = Unit
    }

}
