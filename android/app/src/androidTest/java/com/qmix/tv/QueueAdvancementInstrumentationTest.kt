package com.qmix.tv

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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

@RunWith(AndroidJUnit4::class)
class QueueAdvancementInstrumentationTest {
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val mutationLane = TestMutationLane()

    @org.junit.After
    fun cancelQueueScope() {
        try {
            try {
                runBlocking { kotlinx.coroutines.withTimeout(5_000) { queueScope.coroutineContext[Job]!!.cancelAndJoin() } }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("join native queue test root: timed out", timeout)
            }
        } finally { mutationLane.close() }
    }

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun production_application_constructs_queue_advancement_dependencies_for_a_live_room() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path == "/rooms" ->
                    MockResponse().setResponseCode(201).setBody(
                        """{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""",
                    )
                request.method == "DELETE" && request.path == "/rooms/ABCD" ->
                    MockResponse().setResponseCode(204)
                request.method == "GET" && request.path == "/rooms/ABCD" ->
                    MockResponse().setResponseCode(200).setBody(
                        """{"code":"ABCD","current":null,"queue":[]}""",
                    )
                request.method == "GET" && request.path == "/rooms/ABCD/events" ->
                    MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val application = ApplicationProvider.getApplicationContext<QMixApplication>()
        val controller = application.hostSession
        val endpoints = HostingState.Setup(server.url("/").toString(), "https://guest.example")
        // A distinct edit proves the queued reset ran even if the singleton began in Setup.
        val resetGuestOrigin = if (controller.state == endpoints) "https://reset.guest.example" else endpoints.guestOrigin
        controller.endRoom()
        controller.updateSettings(endpoints.backendUrl, resetGuestOrigin)
        controller.awaitStateForTest(step = "application wiring: prior session ended and distinct endpoints edited") {
            it == HostingState.Setup(endpoints.backendUrl, resetGuestOrigin)
        }
        controller.updateSettings(endpoints.backendUrl, endpoints.guestOrigin)
        val invited = CountDownLatch(1)
        val subscription = controller.collectStatesForTest { if (it is HostingState.Invitation) invited.countDown() }
        try {
            controller.createRoom()
            val admission = controller.awaitStateForTest(step = "application wiring: creation admission") {
                it is HostingState.HttpWarning || it is HostingState.Invitation || it is HostingState.Error
            }
            if (admission is HostingState.HttpWarning) {
                controller.confirmHttpWarning()
            }
            assertTrue("application wiring: invitation subscription was not notified", invited.await(5, TimeUnit.SECONDS))

            controller.enterRoom()
            controller.awaitStateForTest(step = "application wiring: live room published") {
                it is HostingState.LiveRoom
            }
            assertTrue(controller.state is HostingState.LiveRoom)
        } finally {
            subscription.close()
            controller.endRoom()
            controller.awaitSetupForTest(step = "application wiring: room teardown joined")
        }
    }

    @Test
    fun asynchronous_http_command_reports_success_rejection_and_decode_uncertainty() {
        val command = RoomAdvanceCommand(
            RoomApiClient(OkHttpClient(), server.url("/").toString()),
        )
        val observed = mutableListOf<QueueAdvanceCommandResult>()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"current":{"track_id":"next","pos_sec":0,"state":"playing","title":"Next","artist":"Artist"}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        observed += runBlocking { command.skip("ABCD", "host-secret") }
        observed += runBlocking { command.skip("ABCD", "host-secret") }
        observed += runBlocking { command.skip("ABCD", "host-secret") }
        observed += runBlocking { command.skip("ABCD", "malformed\nheader") }

        assertEquals(
            listOf(
                QueueAdvanceCommandResult.Success,
                QueueAdvanceCommandResult.Rejected,
                QueueAdvanceCommandResult.Indeterminate,
                QueueAdvanceCommandResult.Rejected,
            ),
            observed,
        )
        assertEquals(3, server.requestCount)
    }

    @Test
    fun coordinator_covers_dispatch_failure_missing_room_and_close_during_notification() {
        val room = RoomState(
            code = "ABCD",
            current = CurrentTrack("current", 0, "playing", "Current", "Artist"),
            queue = listOf(QueuedTrack("next", "https://example/next", "Next", "Artist", 60, "fixture")),
        )
        var fetchCallback: ((RoomFetchResult) -> Unit)? = null
        val fetchReady = CountDownLatch(1)
        val dispatchFailure = testQueueCoordinator(
            queueScope,
            roomCode = "ABCD",
            hostToken = "host-secret",
            command = testQueueCommand { _, _, _ -> throw IllegalStateException("dispatch") },
            reconciler = TestRoomFetcher { _, callback ->
                fetchCallback = callback
                fetchReady.countDown()
                Cancelable { }
            },
            mutationContext = mutationLane.context,
        )
        dispatchFailure.onAuthoritativeRoom(room)

        assertTrue(dispatchFailure.requestExplicitAdvance())
        assertTrue(dispatchFailure.state.pending)
        assertTrue("reconciler was not entered", fetchReady.await(5, TimeUnit.SECONDS))
        val dispatchWorkers = dispatchFailure.foregroundWorkers()
        assertTrue("dispatch failure: reconciliation must own a reached job", dispatchWorkers.isNotEmpty())
        checkNotNull(fetchCallback)(RoomFetchResult.Missing)
        awaitConditionForTest(step = "dispatch failure: missing-room reconciliation settled") {
            !dispatchFailure.state.pending && dispatchFailure.state.lastOutcome == QueueAdvanceOutcome.REJECTED
        }
        awaitConditionForTest(step = "dispatch failure: reconciliation jobs completed") {
            dispatchWorkers.all { it.isCompleted }
        }
        assertFalse(dispatchFailure.state.pending)
        assertEquals(QueueAdvanceOutcome.REJECTED, dispatchFailure.state.lastOutcome)

        var dispatched = false
        lateinit var closing: QueueAdvancementCoordinator
        closing = testQueueCoordinator(
            queueScope,
            roomCode = "ABCD",
            hostToken = "host-secret",
            command = testQueueCommand { _, _, _ -> dispatched = true },
            reconciler = TestRoomFetcher { _, _ -> Cancelable { } },
            observer = { if (it.pending) closing.close() },
            mutationContext = mutationLane.context,
        )
        closing.onAuthoritativeRoom(room)

        assertFalse(closing.requestExplicitAdvance())
        assertFalse(dispatched)
    }

    @Test
    fun host_controller_routes_explicit_and_ended_actions_through_its_live_coordinator() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""",
            ),
        )
        val repository = RecordingRepository()
        val commands = CopyOnWriteArrayList<(QueueAdvanceCommandResult) -> Unit>()
        val controller = HostSessionController(
            httpClient = OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope,
            roomCollectionContext = Dispatchers.Unconfined,
            queueMutationContext = mutationLane.context,
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                testQueueCoordinator(
                    sessionScope,
                    credentials.code,
                    credentials.hostToken,
                    testQueueCommand { _, _, callback -> commands += callback },
                    TestRoomFetcher { _, _ -> Cancelable { } },
                    observer,
                    mutationContext = mutationLane.context,
                )
            },
        )
        controller.createRoom()
        controller.awaitCreatedForTest(step = "host queue session: invitation published")
        repository.awaitInvitationObservationForTest()
        controller.enterRoom()
        repository.awaitLiveObservationForTest()
        repository.publish(
            RoomSyncState.Active(
                "ABCD",
                RoomState(
                    "ABCD",
                    CurrentTrack("current", 0, "playing", "Current", "Artist"),
                    listOf(QueuedTrack("next", "https://example/next", "Next", "Artist", 60, "fixture")),
                ),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            ),
        )

        controller.awaitStateForTest(step = "queue routing: authoritative current and queue published") {
            ((it as? HostingState.LiveRoom)?.synchronization as? RoomSyncState.Active)
                ?.room?.current?.trackId == "current"
        }
        controller.onStartOrNext()
        controller.onPlaybackEnded("current")
        controller.onInvite()
        controller.awaitStateForTest(step = "queue routing: invitation opened after explicit and ended inputs") {
            (it as? HostingState.LiveRoom)?.invitationVisible == true
        }
        awaitConditionForTest(step = "queue routing: one command reached the transport") { commands.size == 1 }
        assertEquals(1, commands.size)
        controller.endRoom()
        controller.awaitSetupForTest(step = "queue routing: command and session teardown joined")
        commands.single()(QueueAdvanceCommandResult.Indeterminate)
        controller.onStartOrNext()
        controller.updateSettings("https://replacement.example", "https://guest.example")
        controller.awaitStateForTest(step = "queue routing: setup edit follows late callback and out-of-room input") {
            it == HostingState.Setup("https://replacement.example", "https://guest.example")
        }
        assertEquals(1, commands.size)
    }

    @Test
    fun host_controller_propagates_playback_controls_retry_and_ended_advancement() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""",
            ),
        )
        val repository = RecordingRepository()
        val commands = CopyOnWriteArrayList<(QueueAdvanceCommandResult) -> Unit>()
        val playback = RecordingPlaybackEngine()
        var retry: ((RoomFetchResult) -> Unit)? = null
        val retryRequests = CopyOnWriteArrayList<String>()
        val selected = RoomState(
            "ABCD",
            CurrentTrack("current", 0, "playing", "Current", "Artist"),
            listOf(QueuedTrack("next", "https://example/next", "Next", "Artist", 60, "fixture")),
        )
        val controller = HostSessionController(
            httpClient = OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            roomRepositoryFactory = { repository },
            roomCollectionScope = queueScope,
            roomCollectionContext = Dispatchers.Unconfined,
            queueMutationContext = mutationLane.context,
            queueCoordinatorFactory = { _, credentials, observer, sessionScope ->
                testQueueCoordinator(
                    sessionScope,
                    credentials.code,
                    credentials.hostToken,
                    testQueueCommand { _, _, callback -> commands += callback },
                    TestRoomFetcher { _, _ -> Cancelable { } },
                    observer,
                    mutationContext = mutationLane.context,
                )
            },
            playbackCoordinatorFactory = { backendUrl, credentials, observer, advanceAfterEnded, sessionScope ->
                AuthoritativePlaybackCoordinator(
                    roomCode = credentials.code,
                    streamUrl = "${backendUrl.trimEnd('/')}/rooms/${credentials.code}/current/stream",
                    playbackEngine = playback,
                    reconciler = TestRoomFetcher { roomCode, callback ->
                        retry = callback
                        retryRequests += roomCode
                        Cancelable { retry = null }
                    }::fetchRoom,
                    parentScope = sessionScope,
                    mutationContext = mutationLane.context,
                    advanceAfterEnded = advanceAfterEnded,
                    observer = observer,
                )
            },
        )

        controller.createRoom()
        controller.awaitCreatedForTest(step = "host queue session: invitation published")
        repository.awaitInvitationObservationForTest()
        controller.enterRoom()
        repository.awaitLiveObservationForTest()
        repository.publish(RoomSyncState.Active("ABCD", selected, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitStateForTest(step = "playback controls: selected track buffering") { (it as? HostingState.LiveRoom)?.playback?.status ==
            LocalPlaybackStatus.BUFFERING }
        awaitConditionForTest(step = "playback controls: first prepare and play completed") {
            playback.prepared.map(PlaybackMedia::trackId) == listOf("current") && playback.playCount == 1
        }
        assertEquals(listOf("current"), playback.prepared.map(PlaybackMedia::trackId))
        assertEquals(LocalPlaybackStatus.BUFFERING, (controller.state as HostingState.LiveRoom).playback.status)

        playback.emit(
            PlaybackState(
                "current",
                PlaybackStatus.READY,
                isPlaying = true,
                positionMs = 20_000,
                durationMs = 60_000,
                isSeekable = true,
            ),
        )
        controller.awaitStateForTest(step = "playback controls: playing timeline published") { (it as? HostingState.LiveRoom)?.playback?.status ==
            LocalPlaybackStatus.PLAYING }
        assertEquals(LocalPlaybackStatus.PLAYING, (controller.state as HostingState.LiveRoom).playback.status)
        controller.onSeekBy(Long.MIN_VALUE)
        controller.onSeekBy(Long.MAX_VALUE)
        awaitConditionForTest(step = "playback controls: seek calls clamped to both bounds") {
            playback.seeks == listOf(0L, 60_000L)
        }
        assertEquals(listOf(0L, 60_000L), playback.seeks)
        playback.emit(
            PlaybackState(
                "current",
                PlaybackStatus.READY,
                isPlaying = true,
                positionMs = 20_000,
                durationMs = null,
                isSeekable = true,
            ),
        )
        controller.awaitStateForTest(step = "playback controls: unknown duration published") {
            (it as? HostingState.LiveRoom)?.playback?.let {
                it.status == LocalPlaybackStatus.PLAYING && it.durationMs == null && it.isSeekable
            } == true
        }
        controller.onSeekBy(10_000)
        controller.onInvite()
        controller.awaitStateForTest(step = "playback controls: invitation opened after unknown-duration seek") {
            (it as? HostingState.LiveRoom)?.invitationVisible == true
        }
        assertEquals(listOf(0L, 60_000L), playback.seeks)
        controller.onBack { }
        controller.awaitStateForTest(step = "playback controls: invitation dismissed without ending room") {
            (it as? HostingState.LiveRoom)?.invitationVisible == false
        }
        playback.emit(
            PlaybackState(
                "current",
                PlaybackStatus.READY,
                isPlaying = true,
                positionMs = 20_000,
                durationMs = 60_000,
                isSeekable = false,
            ),
        )
        controller.awaitStateForTest(step = "playback controls: unseekable known timeline published") {
            (it as? HostingState.LiveRoom)?.playback?.let {
                it.status == LocalPlaybackStatus.PLAYING && it.durationMs == 60_000L && !it.isSeekable
            } == true
        }
        controller.onSeekBy(10_000)
        controller.onPlayPause()
        controller.awaitStateForTest(step = "playback controls: toggle paused after unseekable seek") { (it as? HostingState.LiveRoom)?.playback?.status ==
            LocalPlaybackStatus.PAUSED }
        assertEquals(listOf(0L, 60_000L), playback.seeks)
        assertEquals(LocalPlaybackStatus.PAUSED, (controller.state as HostingState.LiveRoom).playback.status)
        controller.onPlayPause()
        awaitConditionForTest(step = "playback controls: explicit toggle resumed engine") { playback.playCount == 2 }
        assertEquals(2, playback.playCount)
        playback.emit(
            PlaybackState(
                "current",
                PlaybackStatus.ERROR,
                error = PlaybackError(PlaybackErrorKind.NETWORK, "offline"),
            ),
        )
        controller.awaitStateForTest(step = "playback controls: recoverable player error published") { (it as? HostingState.LiveRoom)?.playback?.status ==
            LocalPlaybackStatus.ERROR }
        controller.onRetryCurrent()
        awaitConditionForTest(step = "playback controls: retry reached authoritative fetch") { retryRequests.size == 1 }
        assertEquals(listOf("ABCD"), retryRequests)
        checkNotNull(retry)(RoomFetchResult.Success(selected))
        awaitConditionForTest(step = "playback controls: retry prepare and play completed") {
            playback.prepared.size == 2 && playback.playCount == 3
        }
        controller.awaitStateForTest(step = "playback controls: retry buffering published") {
            (it as? HostingState.LiveRoom)?.playback?.status == LocalPlaybackStatus.BUFFERING
        }
        assertEquals(listOf("current", "current"), playback.prepared.map(PlaybackMedia::trackId))

        playback.emit(PlaybackState("current", PlaybackStatus.ENDED))
        awaitConditionForTest(step = "playback controls: ended reached one advance command") { commands.size == 1 }
        assertEquals(1, commands.size)
        controller.endRoom()
        controller.awaitSetupForTest(step = "playback controls: all session workers joined")
        assertEquals(2, playback.pauseCount)
    }

    /** qmix#178: the same application factory used by the host owns HTTP command and GET Jobs. */
    @Test
    fun production_queue_wiring_serializes_commands_and_reconciles_on_main() {
        server.enqueue(MockResponse().setBody(
            """{"current":{"track_id":"two","pos_sec":0,"state":"playing","title":"Two","artist":""}}""",
        ))
        server.enqueue(MockResponse().setBody(
            """{"code":"ABCD","current":{"track_id":"two","pos_sec":0,"state":"playing","title":"Two","artist":""},"queue":[]}""",
        ))
        val settled = CountDownLatch(1)
        val observerLoopers = CopyOnWriteArrayList<android.os.Looper?>()
        var commandWorkers = emptyList<Job>()
        val application = ApplicationProvider.getApplicationContext<QMixApplication>()
        val coordinator = application.createQueueCoordinator(
            OkHttpClient(), server.url("/").toString(), RoomCredentials("ABCD", "fixture", "/r/ABCD"),
            observer = { state ->
                observerLoopers += android.os.Looper.myLooper()
                if (state.lastOutcome == QueueAdvanceOutcome.ADVANCED) settled.countDown()
            },
        )
        try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                coordinator.onAuthoritativeRoom(RoomState("ABCD", null,
                    listOf(QueuedTrack("two", "https://example/two", "Two", "", 1, "fixture"))))
                assertTrue(coordinator.requestExplicitAdvance())
                assertFalse(coordinator.requestExplicitAdvance())
                assertFalse(coordinator.onPlaybackEnded("two"))
                commandWorkers = coordinator.foregroundWorkers()
            }
            assertTrue("production queue wiring: admitted command must own a job", commandWorkers.isNotEmpty())
            assertTrue("production queue wiring: advanced notification not delivered", settled.await(5, TimeUnit.SECONDS))
            awaitConditionForTest(step = "production queue wiring: command and reconciliation jobs completed") {
                commandWorkers.all { it.isCompleted }
            }
            observerLoopers.forEach { assertEquals(android.os.Looper.getMainLooper(), it) }
            assertEquals("/rooms/ABCD/skip", checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
                "production queue wiring: skip request not recorded"
            }.path)
            assertEquals("/rooms/ABCD", checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
                "production queue wiring: reconciliation request not recorded"
            }.path)
            assertEquals(2, server.requestCount)
        } finally { coordinator.close() }
    }

    private class RecordingRepository : RoomRepository {
        private val invitationObserved = CountDownLatch(1)
        private val liveObserved = CountDownLatch(2)
        private val states = Channel<RoomSyncState>(Channel.UNLIMITED)

        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            invitationObserved.countDown()
            liveObserved.countDown()
            for (state in states) emit(state)
        }

        fun awaitInvitationObservationForTest() {
            assertTrue("invitation observation did not start", invitationObserved.await(5, TimeUnit.SECONDS))
        }

        fun awaitLiveObservationForTest() {
            assertTrue("live room observation did not start", liveObserved.await(5, TimeUnit.SECONDS))
        }

        fun publish(state: RoomSyncState) {
            states.trySend(state)
        }
    }

    private class RecordingPlaybackEngine : PlaybackEngine {
        @Volatile override var state = PlaybackState()
            private set
        val prepared = CopyOnWriteArrayList<PlaybackMedia>()
        val seeks = CopyOnWriteArrayList<Long>()
        @Volatile var playCount = 0
        @Volatile var pauseCount = 0
        private val listeners = CopyOnWriteArrayList<(PlaybackState) -> Unit>()

        override fun prepare(media: PlaybackMedia) {
            prepared += media
            state = PlaybackState(media.trackId, PlaybackStatus.BUFFERING)
            listeners.toList().forEach { it(state) }
        }

        override fun play() { playCount++ }
        override fun pause() { pauseCount++ }
        override fun seekTo(positionMs: Long) { seeks += positionMs }
        override fun release() = Unit
        override fun addListener(listener: (PlaybackState) -> Unit) { listeners += listener }
        override fun removeListener(listener: (PlaybackState) -> Unit) { listeners -= listener }

        fun emit(next: PlaybackState) {
            state = next
            listeners.toList().forEach { it(next) }
        }
    }
}
