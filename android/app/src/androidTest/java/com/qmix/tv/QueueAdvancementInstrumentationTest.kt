package com.qmix.tv

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        try { queueScope.cancel() } finally { mutationLane.close() }
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
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"code":"ABCD","current":null,"queue":[]}""",
            ),
        )
        val application = ApplicationProvider.getApplicationContext<QMixApplication>()
        val controller = application.hostSession
        controller.endRoom()
        controller.awaitSetupForTest()
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val invited = CountDownLatch(1)
        val subscription = controller.collectStatesForTest { if (it is HostingState.Invitation) invited.countDown() }
        try {
            assertTrue(controller.createRoom())
            if (controller.state is HostingState.HttpWarning) {
                assertTrue(controller.confirmHttpWarning())
            }
            assertTrue(invited.await(5, TimeUnit.SECONDS))

            controller.enterRoom()

            assertTrue(controller.state is HostingState.LiveRoom)
        } finally {
            subscription.close()
            controller.endRoom()
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
        checkNotNull(fetchCallback)(RoomFetchResult.Missing)
        mutationLane.context.run { Unit } // Observe the queued settlement after the callback.
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
        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
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

        controller.awaitStateForTest {
            ((it as? HostingState.LiveRoom)?.synchronization as? RoomSyncState.Active)
                ?.room?.current?.trackId == "current"
        }
        controller.onStartOrNext()
        assertFalse(controller.onPlaybackEnded("current"))
        assertEquals(1, commands.size)
        controller.endRoom()
        commands.single()(QueueAdvanceCommandResult.Indeterminate)
        controller.onStartOrNext()
        assertEquals(1, commands.size)
        controller.awaitSetupForTest()
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

        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
        repository.publish(RoomSyncState.Active("ABCD", selected, Freshness.FRESH, LiveConnection.CONNECTED))
        controller.awaitStateForTest { (it as? HostingState.LiveRoom)?.playback?.status ==
            LocalPlaybackStatus.BUFFERING }
        awaitConditionForTest { playback.prepared.map(PlaybackMedia::trackId) == listOf("current") }
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
        controller.awaitStateForTest { (it as? HostingState.LiveRoom)?.playback?.status ==
            LocalPlaybackStatus.PLAYING }
        assertEquals(LocalPlaybackStatus.PLAYING, (controller.state as HostingState.LiveRoom).playback.status)
        controller.onSeekBy(Long.MIN_VALUE)
        controller.onSeekBy(Long.MAX_VALUE)
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
        controller.onSeekBy(10_000)
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
        controller.onSeekBy(10_000)
        assertEquals(listOf(0L, 60_000L), playback.seeks)
        controller.onPlayPause()
        controller.awaitStateForTest { (it as? HostingState.LiveRoom)?.playback?.status ==
            LocalPlaybackStatus.PAUSED }
        assertEquals(LocalPlaybackStatus.PAUSED, (controller.state as HostingState.LiveRoom).playback.status)
        controller.onPlayPause()
        assertEquals(2, playback.playCount)
        playback.emit(
            PlaybackState(
                "current",
                PlaybackStatus.ERROR,
                error = PlaybackError(PlaybackErrorKind.NETWORK, "offline"),
            ),
        )
        controller.awaitStateForTest { (it as? HostingState.LiveRoom)?.playback?.status ==
            LocalPlaybackStatus.ERROR }
        controller.onRetryCurrent()
        awaitConditionForTest { retryRequests.size == 1 }
        assertEquals(listOf("ABCD"), retryRequests)
        checkNotNull(retry)(RoomFetchResult.Success(selected))
        awaitConditionForTest { playback.prepared.size == 2 }
        assertEquals(listOf("current", "current"), playback.prepared.map(PlaybackMedia::trackId))

        playback.emit(PlaybackState("current", PlaybackStatus.ENDED))
        awaitConditionForTest { commands.size == 1 }
        assertEquals(1, commands.size)
        controller.endRoom()
        assertEquals(2, playback.pauseCount)
        controller.awaitSetupForTest()
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
        val application = ApplicationProvider.getApplicationContext<QMixApplication>()
        val coordinator = application.createQueueCoordinator(
            OkHttpClient(), server.url("/").toString(), RoomCredentials("ABCD", "fixture", "/r/ABCD"),
            observer = { state ->
                assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
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
            }
            assertTrue(settled.await(5, TimeUnit.SECONDS))
            assertEquals("/rooms/ABCD/skip", server.takeRequest().path)
            assertEquals("/rooms/ABCD", server.takeRequest().path)
            assertEquals(2, server.requestCount)
        } finally { coordinator.close() }
    }

    private class RecordingRepository : RoomRepository {
        private val states = Channel<RoomSyncState>(Channel.UNLIMITED)

        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            for (state in states) emit(state)
        }

        fun publish(state: RoomSyncState) {
            states.trySend(state)
        }
    }

    private class RecordingPlaybackEngine : PlaybackEngine {
        override var state = PlaybackState()
            private set
        val prepared = CopyOnWriteArrayList<PlaybackMedia>()
        val seeks = CopyOnWriteArrayList<Long>()
        @Volatile var playCount = 0
        @Volatile var pauseCount = 0
        private val listeners = linkedSetOf<(PlaybackState) -> Unit>()

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
