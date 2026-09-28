package com.qmix.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** qmix#259: lifecycle teardown is distinct from explicit host close. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostRoomClosureTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deletes = LinkedBlockingQueue<Pair<String, String>>()
    private val deleteCount = AtomicInteger()
    private val repository = GenerationalRoomRepository()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun controller(
        delete: suspend (String, String) -> Unit = { code, token ->
            deleteCount.incrementAndGet()
            deletes.put(code to token)
        },
        playback: PlaybackCoordinatorFactory? = null,
        closeScope: CoroutineScope = scope,
        logger: QMixComponentLogger = QMixComponentLogger.noOp(QMixLogComponent.ROOM_API_CREATION),
    ): HostSessionController {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        return HostSessionController(
            OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomRepositoryFactory = { repository },
            roomCollectionScope = scope, roomCollectionContext = Dispatchers.IO,
            roomApiLogger = logger,
            playbackCoordinatorFactory = playback,
            roomCloseScope = closeScope,
            roomCloseCommandFactory = { RoomCloseCommand(delete) },
        ).also {
            assertTrue(it.createRoom())
            it.awaitCreatedForTest()
        }
    }

    private fun drainCloseScope() = runBlocking {
        withTimeout(5_000) { scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().joinAll() }
    }

    private fun expectDelete() {
        assertEquals("ABCD" to "host-secret", deletes.poll(5, TimeUnit.SECONDS))
        assertEquals(1, deleteCount.get())
    }

    @Test fun invitation_end_deletes_once_and_double_end_does_not_repeat() {
        val controller = controller()
        repository.awaitGeneration()
        controller.endRoom()
        controller.endRoom()
        assertTrue(controller.awaitSetupForTest())
        expectDelete()
        drainCloseScope()
        assertEquals(1, deleteCount.get())
    }

    @Test fun live_room_back_deletes_once_after_invitation_observer_stops() {
        val controller = controller()
        repository.awaitGeneration()
        controller.enterRoom()
        repository.awaitGeneration() // invitation and live are distinct observers (#264).
        assertEquals(LiveRoomBackResult.EXIT_ACTIVITY, controller.onBack())
        assertTrue(controller.awaitSetupForTest())
        expectDelete()
        drainCloseScope()
    }

    @Test fun foreground_loss_does_not_delete_even_when_it_stops_both_observers() {
        val controller = controller()
        repository.awaitGeneration() // invitation observer
        controller.enterRoom()
        repository.awaitGeneration() // live observer (#264)
        controller.onHostStopped()
        assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        controller.abandonRoom()
        assertTrue(controller.awaitSetupForTest())
        drainCloseScope()
        assertEquals(0, deleteCount.get())
    }

    @Test fun activity_teardown_detaches_locally_without_delete() {
        val controller = controller()
        repository.awaitGeneration()
        controller.abandonRoom()
        assertTrue(controller.awaitSetupForTest())
        drainCloseScope()
        assertEquals(0, deleteCount.get())
    }

    @Test fun live_room_foreground_loss_does_not_delete() {
        val controller = controller()
        repository.awaitGeneration()
        controller.enterRoom()
        repository.awaitGeneration()
        controller.onHostStopped()
        assertTrue((controller.state as HostingState.LiveRoom).foregroundRecoveryPending)
        controller.abandonRoom()
        assertTrue(controller.awaitSetupForTest())
        drainCloseScope()
        assertEquals(0, deleteCount.get())
    }

    @Test fun timed_out_delete_is_cancelled_without_blocking_setup() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val never = CompletableDeferred<Unit>()
        val controller = controller(delete = { _, _ ->
            started.countDown()
            try { never.await() } finally { cancelled.countDown() }
        })
        repository.awaitGeneration()
        controller.endRoom()
        assertTrue("Setup must not await DELETE", controller.awaitSetupForTest())
        assertTrue("DELETE was not attempted", started.await(5, TimeUnit.SECONDS))
        assertTrue("DELETE deadline did not cancel transport", cancelled.await(5, TimeUnit.SECONDS))
        assertTrue(controller.state is HostingState.Setup)
    }

    @Test fun delete_failure_is_logged_safely_and_does_not_prevent_setup() {
        val started = CountDownLatch(1)
        val records = LinkedBlockingQueue<QMixLogRecord>()
        val logger = QMixLogger(QMixLogSink { records.put(it) }, QMixLogLevel.DEBUG) { null }
            .component(QMixLogComponent.ROOM_API_CREATION)
        val controller = controller(delete = { _, _ ->
            started.countDown()
            throw IllegalStateException("host_token=must-not-log")
        }, logger = logger)
        repository.awaitGeneration()
        controller.endRoom()
        assertTrue(controller.awaitSetupForTest())
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val record = checkNotNull(records.poll(5, TimeUnit.SECONDS))
        assertEquals(QMixLogOperation.DELETE_ROOM, record.operation)
        assertFalse(record.toString().contains("must-not-log"))
    }

    @Test fun missing_room_new_room_does_not_delete() {
        val used = controller()
        val invitation = repository.awaitGeneration()
        invitation.trySend(RoomSyncState.Active("ABCD", RoomState("ABCD", null,
            listOf(QueuedTrack("one", "https://example/one", "One", "", 1, "fixture"))),
            Freshness.FRESH, LiveConnection.CONNECTED))
        // Wait for the track observation via a second, authoritative missing event.
        invitation.trySend(RoomSyncState.Missing("ABCD"))
        used.awaitRoomStateForTest { it.synchronization is RoomSyncState.Missing }
        used.onNewRoom()
        // The new POST needs a response before it can leave Pending.
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"EFGH","host_token":"new-secret","url":"/r/EFGH"}"""))
        runBlocking { withTimeout(5_000) {
            used.states.first { it is HostingState.Invitation && it.invite.code == "EFGH" }
        } }
        used.abandonRoom()
        assertTrue(used.awaitSetupForTest())
        drainCloseScope()
        assertEquals(0, deleteCount.get())
    }

    @Test fun automatic_replacement_of_unused_missing_room_does_not_delete() {
        val controller = controller()
        val invitation = repository.awaitGeneration()
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"EFGH","host_token":"new-secret","url":"/r/EFGH"}"""))
        invitation.trySend(RoomSyncState.Missing("ABCD"))
        runBlocking { withTimeout(5_000) {
            controller.states.first { it is HostingState.Invitation && it.invite.code == "EFGH" }
        } }
        controller.abandonRoom()
        assertTrue(controller.awaitSetupForTest())
        drainCloseScope()
        assertEquals(0, deleteCount.get())
    }

    @Test fun final_pause_finishes_before_delete_without_delaying_setup() {
        val reports = LinkedBlockingQueue<PlayerReport>()
        val allowPause = CompletableDeferred<Unit>()
        val pauseAcknowledged = CompletableDeferred<Unit>()
        val deleteBeforePauseSettled = java.util.concurrent.atomic.AtomicBoolean()
        val engine = RecordingEngine()
        lateinit var publisher: PlayerStatePublisher
        val controller = controller(delete = { code, token ->
            if (!pauseAcknowledged.isCompleted || publisher.finalPauseCompletion()?.isCompleted != true) {
                deleteBeforePauseSettled.set(true)
            }
            deleteCount.incrementAndGet()
            deletes.put(code to token)
        }, playback = { _, credentials, observer, advance, sessionScope ->
            AuthoritativePlaybackCoordinator(credentials.code, "https://example/stream", engine,
                { RoomFetchResult.Failure }, sessionScope, QueueMutationContext(Dispatchers.Unconfined),
                advance, observer, { listener ->
                    PlayerStatePublisher(credentials.code, credentials.hostToken,
                        { _, _, report ->
                            reports.put(report)
                            if (report.state == PlayerReportState.PAUSED) {
                                allowPause.await()
                                pauseAcknowledged.complete(Unit)
                            }
                            PlayerReportResult.ACCEPTED
                        }, sessionScope, QueueMutationContext(Dispatchers.Unconfined), scope, listener)
                            .also { publisher = it }
                })
        })
        repository.awaitGeneration()
        controller.enterRoom()
        val live = repository.awaitGeneration()
        live.trySend(RoomSyncState.Active("ABCD", RoomState("ABCD",
            CurrentTrack("one", 3, "playing", "One", "Artist"), emptyList()),
            Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitRoomStateForTest { (it.synchronization as? RoomSyncState.Active)?.room?.current?.trackId == "one" }
        assertTrue("playback was not prepared", engine.prepared.await(5, TimeUnit.SECONDS))
        engine.emit(PlaybackState("one", PlaybackStatus.READY, isPlaying = true, positionMs = 3_000))
        assertEquals(PlayerReportState.PLAYING, reports.poll(5, TimeUnit.SECONDS)?.state)
        controller.endRoom()
        assertTrue(controller.awaitSetupForTest())
        assertEquals(PlayerReportState.PAUSED, reports.poll(5, TimeUnit.SECONDS)?.state)
        allowPause.complete(Unit)
        runBlocking { withTimeout(5_000) { pauseAcknowledged.await() } }
        expectDelete()
        drainCloseScope()
        assertFalse("DELETE overtook end-time PAUSED", deleteBeforePauseSettled.get())
    }

    private fun playingController(
        engine: RecordingEngine,
        report: suspend (PlayerReport) -> PlayerReportResult,
        capture: (PlayerStatePublisher) -> Unit = {},
        closeScope: CoroutineScope = scope,
        delete: suspend (String, String) -> Unit = { code, token ->
            deleteCount.incrementAndGet()
            deletes.put(code to token)
        },
    ): HostSessionController = controller(delete = delete, closeScope = closeScope,
        playback = { _, credentials, observer, advance, sessionScope ->
        AuthoritativePlaybackCoordinator(credentials.code, "https://example/stream", engine,
            { RoomFetchResult.Failure }, sessionScope, QueueMutationContext(Dispatchers.Unconfined),
            advance, observer, { listener ->
                PlayerStatePublisher(credentials.code, credentials.hostToken,
                    { _, _, value -> report(value) }, sessionScope,
                    QueueMutationContext(Dispatchers.Unconfined), scope, listener).also(capture)
            })
    })

    private fun startPlaying(controller: HostSessionController, engine: RecordingEngine) {
        repository.awaitGeneration() // invitation
        controller.enterRoom()
        repository.awaitGeneration().trySend(RoomSyncState.Active("ABCD", RoomState("ABCD",
            CurrentTrack("one", 3, "playing", "One", "Artist"), emptyList()),
            Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitRoomStateForTest {
            (it.synchronization as? RoomSyncState.Active)?.room?.current?.trackId == "one"
        }
        assertTrue("playback was not prepared", engine.prepared.await(5, TimeUnit.SECONDS))
        engine.emit(PlaybackState("one", PlaybackStatus.READY, isPlaying = true, positionMs = 3_000))
    }

    @Test fun foreground_pause_in_flight_finishes_before_explicit_end_delete() {
        val reports = LinkedBlockingQueue<PlayerReport>()
        val releasePause = CompletableDeferred<Unit>()
        val pauseAcknowledged = CompletableDeferred<Unit>()
        val deleteBeforePauseSettled = java.util.concurrent.atomic.AtomicBoolean()
        val engine = RecordingEngine()
        lateinit var publisher: PlayerStatePublisher
        val controller = playingController(engine, { report ->
            reports.put(report)
            if (report.state == PlayerReportState.PAUSED) {
                releasePause.await()
                pauseAcknowledged.complete(Unit)
            }
            PlayerReportResult.ACCEPTED
        }, capture = { publisher = it }, delete = { code, token ->
            if (!pauseAcknowledged.isCompleted || publisher.finalPauseCompletion()?.isCompleted != true) {
                deleteBeforePauseSettled.set(true)
            }
            deleteCount.incrementAndGet()
            deletes.put(code to token)
        })
        try {
            startPlaying(controller, engine)
            assertEquals(PlayerReportState.PLAYING, reports.poll(5, TimeUnit.SECONDS)?.state)
            controller.onHostStopped()
            assertEquals(PlayerReportState.PAUSED, reports.poll(5, TimeUnit.SECONDS)?.state)
            controller.endRoom()
            assertTrue(controller.awaitSetupForTest())
            releasePause.complete(Unit)
            runBlocking { withTimeout(5_000) { pauseAcknowledged.await() } }
            expectDelete()
            drainCloseScope()
            assertFalse("DELETE overtook foreground PAUSED", deleteBeforePauseSettled.get())
        } finally { releasePause.complete(Unit) }
    }

    @Test fun timed_out_foreground_pause_then_settled_predecessor_allows_later_end() {
        val reports = LinkedBlockingQueue<PlayerReport>()
        val releasePlaying = CompletableDeferred<Unit>()
        val engine = RecordingEngine()
        lateinit var publisher: PlayerStatePublisher
        val controller = playingController(engine, { report ->
            reports.put(report)
            if (report.state == PlayerReportState.PLAYING) {
                withContext(NonCancellable) { releasePlaying.await() }
            }
            PlayerReportResult.ACCEPTED
        }, { publisher = it })
        try {
            startPlaying(controller, engine)
            assertEquals(PlayerReportState.PLAYING, reports.poll(5, TimeUnit.SECONDS)?.state)
            controller.onHostStopped()
            runBlocking { withTimeout(5_000) { publisher.finalPauseCompletion()!!.join() } }
            assertFalse("old report still in flight", publisher.canCloseRoomAfterFinalPause())
            releasePlaying.complete(Unit)
            runBlocking {
                withTimeout(5_000) {
                    while (!publisher.canCloseRoomAfterFinalPause()) kotlinx.coroutines.yield()
                }
            }
            controller.endRoom()
            assertTrue(controller.awaitSetupForTest())
            expectDelete()
            drainCloseScope()
            assertTrue("the timed-out pause must never be sent", reports.isEmpty())
        } finally { releasePlaying.complete(Unit) }
    }

    @Test fun still_running_predecessor_blocks_delete_after_close_deadline() {
        val reports = LinkedBlockingQueue<PlayerReport>()
        val releasePlaying = CompletableDeferred<Unit>()
        val closeOnly = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = RecordingEngine()
        lateinit var publisher: PlayerStatePublisher
        val controller = playingController(engine, { report ->
            reports.put(report)
            if (report.state == PlayerReportState.PLAYING) {
                withContext(NonCancellable) { releasePlaying.await() }
            }
            PlayerReportResult.ACCEPTED
        }, capture = { publisher = it }, closeScope = closeOnly)
        try {
            startPlaying(controller, engine)
            assertEquals(PlayerReportState.PLAYING, reports.poll(5, TimeUnit.SECONDS)?.state)
            controller.onHostStopped()
            runBlocking { withTimeout(5_000) { publisher.finalPauseCompletion()!!.join() } }
            assertFalse(publisher.canCloseRoomAfterFinalPause())
            controller.endRoom()
            // The close worker must have actually exited before asserting absence of DELETE.
            runBlocking { withTimeout(5_000) {
                closeOnly.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().joinAll()
            } }
            assertEquals(0, deleteCount.get())
            releasePlaying.complete(Unit)
            assertTrue(controller.awaitSetupForTest())
            drainCloseScope()
            assertEquals(0, deleteCount.get())
        } finally {
            releasePlaying.complete(Unit)
            closeOnly.cancel()
        }
    }

    private class GenerationalRoomRepository : RoomRepository {
        private val generations = LinkedBlockingQueue<Channel<RoomSyncState>>()
        fun awaitGeneration(): Channel<RoomSyncState> = checkNotNull(generations.poll(5, TimeUnit.SECONDS))
        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            val channel = Channel<RoomSyncState>(Channel.UNLIMITED)
            generations.put(channel)
            try { for (state in channel) emit(state) } finally { channel.close() }
        }
    }

    private class RecordingEngine : PlaybackEngine {
        override var state = PlaybackState()
            private set
        private val listeners = linkedSetOf<(PlaybackState) -> Unit>()
        val prepared = CountDownLatch(1)
        override fun prepare(media: PlaybackMedia) { prepared.countDown() }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
        override fun addListener(listener: (PlaybackState) -> Unit) { listeners += listener }
        override fun removeListener(listener: (PlaybackState) -> Unit) { listeners -= listener }
        fun emit(value: PlaybackState) { state = value; listeners.toList().forEach { it(value) } }
    }
}
