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
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deletes = LinkedBlockingQueue<Pair<String, String>>()
    private val deleteCount = AtomicInteger()
    private val repository = GenerationalRoomRepository()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        scope.cancel()
        networkScope.cancel()
        server.shutdown()
    }

    private fun controller(
        delete: suspend (String, String) -> Unit = { code, token ->
            deleteCount.incrementAndGet()
            deletes.put(code to token)
        },
        playback: PlaybackCoordinatorFactory? = null,
        closeScope: CoroutineScope = networkScope,
        logger: QMixComponentLogger = QMixComponentLogger.noOp(QMixLogComponent.ROOM_API_CREATION),
    ): HostSessionController {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""))
        return HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)),
            httpClient = OkHttpClient(), initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example", roomRepositoryFactory = { repository },
            roomCollectionScope = scope, roomCollectionContext = Dispatchers.IO,
            roomApiLogger = logger,
            playbackCoordinatorFactory = playback,
            roomCloseScope = closeScope,
            roomCloseCommandFactory = { RoomCloseCommand(delete) },
        ).also {
            it.createRoom()
            it.awaitCreatedForTest()
        }
    }

    private fun drainCloseScope() = runBlocking {
        withTimeout(5_000) { networkScope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().joinAll() }
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
        controller.onBack()
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
        controller.awaitRoomStateForTest { it.foregroundRecoveryPending }
        controller.awaitRoomStateForTest { it.foregroundRecoveryPending }
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
        controller.awaitRoomStateForTest { it.foregroundRecoveryPending }
        controller.awaitRoomStateForTest { it.foregroundRecoveryPending }
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

    private fun playingController(
        engine: RecordingEngine,
        report: suspend (PlayerReport) -> PlayerReportResult,
        delete: suspend (String, String) -> Unit = { code, token ->
            deleteCount.incrementAndGet()
            deletes.put(code to token)
        },
    ): HostSessionController = controller(delete = delete,
        playback = { _, credentials, observer, advance, sessionScope ->
        AuthoritativePlaybackCoordinator(credentials.code, "https://example/stream", engine,
            { RoomFetchResult.Failure }, sessionScope, QueueMutationContext(Dispatchers.Unconfined),
            advance, observer, { listener ->
                PlayerStatePublisher(credentials.code, credentials.hostToken,
                    { _, _, value -> report(value) }, sessionScope,
                    QueueMutationContext(Dispatchers.Unconfined), scope, listener)
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

    /** qmix#208/#278: backgrounding pauses the surviving room, not closing it. */
    @Test fun foreground_loss_reports_one_paused_without_delete() {
        val reports = LinkedBlockingQueue<PlayerReport>()
        val engine = RecordingEngine()
        val controller = playingController(engine, { report ->
            reports.put(report)
            PlayerReportResult.ACCEPTED
        })
        startPlaying(controller, engine)
        assertEquals("playing report entered; state=${controller.state}", PlayerReportState.PLAYING,
            reports.poll(5, TimeUnit.SECONDS)?.state)

        controller.onHostStopped()
        controller.onHostStopped()
        assertEquals("one foreground PAUSED; state=${controller.state}, reports=${reports.toList()}",
            PlayerReportState.PAUSED, reports.poll(5, TimeUnit.SECONDS)?.state)
        assertEquals("no DELETE on foreground loss; state=${controller.state}", 0, deleteCount.get())
        controller.abandonRoom()
        assertTrue("Setup after abandonment; state=${controller.state}", controller.awaitSetupForTest())
        drainCloseScope()
        assertTrue("no duplicate PAUSED after teardown; state=${controller.state}, reports=${reports.toList()}",
            reports.isEmpty())
        assertEquals("abandonment also must not DELETE; state=${controller.state}", 0, deleteCount.get())
    }

    /** qmix#208/#278: foreground loss reports PAUSED, but explicit end never waits for it. */
    @Test fun foreground_pause_in_flight_does_not_delay_explicit_end_delete() {
        val reports = LinkedBlockingQueue<PlayerReport>()
        val releasePause = CompletableDeferred<Unit>()
        val engine = RecordingEngine()
        val controller = playingController(engine, { report ->
            reports.put(report)
            if (report.state == PlayerReportState.PAUSED) {
                withContext(NonCancellable) { withTimeout(10_000) { releasePause.await() } }
            }
            PlayerReportResult.ACCEPTED
        })
        try {
            startPlaying(controller, engine)
            assertEquals("playing report entered; state=${controller.state}", PlayerReportState.PLAYING,
                reports.poll(5, TimeUnit.SECONDS)?.state)
            controller.onHostStopped()
            assertEquals("foreground PAUSED entered; reports=${reports.toList()}", PlayerReportState.PAUSED,
                reports.poll(5, TimeUnit.SECONDS)?.state)
            controller.endRoom()
            val close = deletes.poll(2, TimeUnit.SECONDS)
            assertEquals("DELETE during foreground PAUSED; state=${controller.state}, reports=${reports.toList()}",
                "ABCD" to "host-secret", close)
            assertEquals("one DELETE; last observed=$close", 1, deleteCount.get())
            assertTrue("PAUSED remains blocked when DELETE starts; state=${controller.state}", !releasePause.isCompleted)
        } finally { releasePause.complete(Unit) }
        assertTrue("Setup after PAUSED released; state=${controller.state}", controller.awaitSetupForTest())
        drainCloseScope()
    }

    /** qmix#278: DELETE cannot be held hostage by a report that ignores cancellation. */
    @Test fun explicit_end_deletes_once_while_older_report_is_still_hung_without_final_pause() {
        val reports = LinkedBlockingQueue<PlayerReport>()
        val releasePlaying = CompletableDeferred<Unit>()
        val engine = RecordingEngine()
        val controller = playingController(engine, { report ->
            reports.put(report)
            if (report.state == PlayerReportState.PLAYING) {
                withContext(NonCancellable) { withTimeout(10_000) { releasePlaying.await() } }
            }
            PlayerReportResult.ACCEPTED
        })
        try {
            startPlaying(controller, engine)
            assertEquals("older report entered; last observed=${reports.toList()}", PlayerReportState.PLAYING,
                reports.poll(5, TimeUnit.SECONDS)?.also { reports.put(it) }?.state)
            controller.endRoom()
            val close = deletes.poll(2, TimeUnit.SECONDS)
            assertEquals("prompt DELETE before hung report settles; state=${controller.state}, reports=${reports.toList()}",
                "ABCD" to "host-secret", close)
            assertEquals("one DELETE; last observed=$close, state=${controller.state}", 1, deleteCount.get())
            assertEquals("explicit end sends no final PAUSED; last observed=${reports.toList()}",
                listOf(PlayerReportState.PLAYING), reports.toList().map { it.state })
            controller.awaitStateForTest("explicit end while predecessor report is blocked") { it is HostingState.Ending }
            assertEquals("DELETE remains unique past old join; state=${controller.state}, reports=${reports.toList()}",
                1, deleteCount.get())
        } finally {
            releasePlaying.complete(Unit)
        }
        assertTrue("Setup after hung report released; last observed=${controller.state}", controller.awaitSetupForTest())
        drainCloseScope()
        assertEquals("still only one DELETE after teardown; last observed=${deletes.toList()}", 1, deleteCount.get())
        assertEquals("no late PAUSED after teardown; last observed=${reports.toList()}",
            listOf(PlayerReportState.PLAYING), reports.toList().map { it.state })
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
