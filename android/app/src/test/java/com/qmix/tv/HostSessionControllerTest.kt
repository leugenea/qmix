package com.qmix.tv

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSessionControllerTest {
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
    fun observer_failure_logs_sanitized_host_session_boundary() {
        val sink = RecordingLogSink()
        val logger = QMixLogger(sink, QMixLogLevel.DEBUG) { null }
            .component(QMixLogComponent.APP_HOST_SESSION)
        val controller = HostSessionController(OkHttpClient(), logger = logger)

        controller.observe { throw IllegalStateException("host_token=do-not-log") }

        assertEquals(
            listOf(
                QMixLogRecord(
                    QMixLogLevel.ERROR,
                    QMixLogComponent.APP_HOST_SESSION,
                    QMixLogOperation.OBSERVER_NOTIFICATION,
                    QMixLogCause.CALLBACK_FAILURE,
                ),
            ),
            sink.records,
        )
        assertFalse(sink.records.toString().contains("do-not-log"))
    }

    @Test
    fun repeated_create_while_pending_sends_exactly_one_post() {
        server.enqueue(
            MockResponse()
                .setBodyDelay(150, TimeUnit.MILLISECONDS)
                .setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val controller = HostSessionController(OkHttpClient())
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val completed = CountDownLatch(1)
        controller.observe { if (it is HostingState.Invitation) completed.countDown() }

        assertTrue(controller.createRoom())
        assertFalse(controller.createRoom())

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(1, server.requestCount)
        assertEquals(
            HostingState.Invitation(GuestInvite("ABCD", "https://guest.example/r/ABCD")),
            controller.state,
        )
        assertFalse(controller.state.toString().contains("host-secret"))
    }

    @Test
    fun settings_changes_during_pending_cannot_reenable_create() {
        server.enqueue(
            MockResponse().setBodyDelay(150, TimeUnit.MILLISECONDS).setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val controller = HostSessionController(OkHttpClient())
        controller.updateSettings(server.url("/").toString(), "https://guest.example")

        assertTrue(controller.createRoom())
        controller.updateSettings("https://other.example", "https://other.example")
        assertFalse(controller.createRoom())

        server.takeRequest(5, TimeUnit.SECONDS)
        Thread.sleep(250)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun lost_create_response_is_not_retried_automatically() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val controller = HostSessionController(OkHttpClient())
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val failed = CountDownLatch(1)
        controller.observe { if (it is HostingState.Error) failed.countDown() }

        assertTrue(controller.createRoom())

        assertTrue(failed.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(1, server.requestCount)
        assertEquals(
            "Could not reach the server.",
            (controller.state as HostingState.Error).message,
        )
    }

    @Test
    fun invitation_action_transitions_to_initial_live_room_state() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val controller = HostSessionController(OkHttpClient())
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val invited = CountDownLatch(1)
        controller.observe { if (it is HostingState.Invitation) invited.countDown() }
        controller.createRoom()
        assertTrue(invited.await(5, TimeUnit.SECONDS))

        controller.enterRoom()

        assertEquals(
            HostingState.LiveRoom(
                GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                RoomSyncState.Active("ABCD", null, Freshness.LOADING, LiveConnection.CONNECTING),
            ),
            controller.state,
        )
    }

    @Test
    fun ending_a_pending_room_creation_ignores_its_late_response() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""")
                .setBodyDelay(500, TimeUnit.MILLISECONDS),
        )
        val controller = HostSessionController(OkHttpClient())
        val setup = HostingState.Setup(server.url("/").toString(), "https://guest.example")
        controller.updateSettings(setup.backendUrl, setup.guestOrigin)
        val invited = CountDownLatch(1)
        controller.observe { if (it is HostingState.Invitation) invited.countDown() }

        controller.createRoom()
        server.takeRequest(5, TimeUnit.SECONDS)
        controller.endRoom()

        assertEquals(false, invited.await(1, TimeUnit.SECONDS))
        assertEquals(setup, controller.state)
    }

    @Test
    fun primary_action_uses_the_queue_coordinator_and_publishes_pending_state() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = RecordingRoomRepository()
        val command = RecordingAdvanceCommand()
        val reconciler = RecordingReconciler()
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            executor = Executor { it.run() },
            roomRepositoryFactory = { repository },
            queueCoordinatorFactory = { _, credentials, observer ->
                QueueAdvancementCoordinator(
                    credentials.code,
                    credentials.hostToken,
                    command,
                    reconciler,
                    observer,
                )
            },
        )
        controller.createRoom()
        controller.enterRoom()
        val room = RoomState(
            "ABCD",
            null,
            listOf(QueuedTrack("track-1", "https://example/1", "Title", "Artist", 60, "fixture")),
        )
        repository.publish(RoomSyncState.Active("ABCD", room, Freshness.FRESH, LiveConnection.CONNECTED))

        controller.onStartOrNext()

        assertEquals(1, command.callbacks.size)
        assertTrue((controller.state as HostingState.LiveRoom).commandPending)
        command.complete(QueueAdvanceCommandResult.Indeterminate)
        reconciler.complete(RoomFetchResult.Success(room))
        assertFalse((controller.state as HostingState.LiveRoom).commandPending)
    }

    @Test
    fun start_command_followed_by_fresh_selection_starts_local_playback_once() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = RecordingRoomRepository()
        val command = RecordingAdvanceCommand()
        val reconciler = RecordingReconciler()
        val engine = HostRecordingPlaybackEngine()
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",
            executor = Executor { it.run() },
            roomRepositoryFactory = { repository },
            queueCoordinatorFactory = { _, credentials, observer ->
                QueueAdvancementCoordinator(credentials.code, credentials.hostToken, command, reconciler, observer)
            },
            playbackCoordinatorFactory = { backendUrl, credentials, observer, advanceAfterEnded ->
                AuthoritativePlaybackCoordinator(
                    roomCode = credentials.code,
                    streamUrl = "${backendUrl.trimEnd('/')}/rooms/${credentials.code}/current/stream",
                    playbackEngine = engine,
                    reconciler = reconciler,
                    dispatcher = Executor { it.run() },
                    advanceAfterEnded = advanceAfterEnded,
                    observer = observer,
                )
            },
        )
        controller.createRoom()
        controller.enterRoom()
        repository.publish(
            RoomSyncState.Active(
                "ABCD",
                RoomState("ABCD", null, listOf(QueuedTrack("one", "url", "One", "Artist", 60, "fixture"))),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            ),
        )

        controller.onStartOrNext()
        command.complete(QueueAdvanceCommandResult.Success)
        val selected = RoomState(
            "ABCD",
            CurrentTrack("one", 0, "playing", "One", "Artist"),
            listOf(QueuedTrack("two", "url", "Two", "Artist", 60, "fixture")),
        )
        reconciler.complete(RoomFetchResult.Success(selected))
        repository.publish(RoomSyncState.Active("ABCD", selected, Freshness.FRESH, LiveConnection.CONNECTED))
        repository.publish(RoomSyncState.Active("ABCD", selected, Freshness.FRESH, LiveConnection.CONNECTED))

        assertEquals(listOf("one"), engine.prepared.map(PlaybackMedia::trackId))
        assertEquals(1, engine.playCount)
        assertEquals(LocalPlaybackStatus.BUFFERING, (controller.state as HostingState.LiveRoom).playback.status)

        engine.emit(
            PlaybackState(
                mediaId = "one",
                status = PlaybackStatus.ERROR,
                error = PlaybackError(PlaybackErrorKind.NETWORK, "offline"),
            ),
        )
        controller.onStartOrNext()
        assertEquals(2, command.callbacks.size)
        assertEquals(LocalPlaybackStatus.ERROR, (controller.state as HostingState.LiveRoom).playback.status)

        val staleReplacement = selected.copy(current = CurrentTrack("two", 0, "playing", "Two", "Artist"))
        repository.publish(
            RoomSyncState.Active("ABCD", staleReplacement, Freshness.STALE, LiveConnection.RECONNECTING),
        )
        val presentation = controller.state as HostingState.LiveRoom
        assertEquals(Freshness.STALE, (presentation.synchronization as RoomSyncState.Active).freshness)
        assertEquals("two", presentation.synchronization.room?.current?.trackId)
        assertEquals(listOf("one"), engine.prepared.map(PlaybackMedia::trackId))
    }

    @Test
    fun room_sync_is_application_session_owned_and_canceled_when_session_ends() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        val repository = RecordingRoomRepository()
        val controller = HostSessionController(
            OkHttpClient(),
            roomRepositoryFactory = { repository },
        )
        controller.updateSettings(server.url("/").toString(), "https://guest.example")
        val invited = CountDownLatch(1)
        controller.observe { if (it is HostingState.Invitation) invited.countDown() }
        controller.createRoom()
        assertTrue(invited.await(5, TimeUnit.SECONDS))

        controller.enterRoom()
        val synchronized = RoomSyncState.Active(
            "ABCD",
            RoomState("ABCD", null, emptyList()),
            Freshness.FRESH,
            LiveConnection.CONNECTED,
        )
        repository.publish(synchronized)

        assertEquals("ABCD", repository.observedCode)
        assertEquals(synchronized, controller.roomSyncState)
        controller.endRoom()
        assertTrue(repository.closed)
        assertEquals(HostingState.Setup(server.url("/").toString(), "https://guest.example"), controller.state)
        repository.publish(synchronized.copy(freshness = Freshness.STALE))
        assertEquals(null, controller.roomSyncState)
    }

    private class HostRecordingPlaybackEngine : PlaybackEngine {
        override var state = PlaybackState()
            private set
        val prepared = mutableListOf<PlaybackMedia>()
        var playCount = 0
        private val listeners = linkedSetOf<(PlaybackState) -> Unit>()

        override fun prepare(media: PlaybackMedia) {
            prepared += media
            state = PlaybackState(mediaId = media.trackId, status = PlaybackStatus.BUFFERING)
            listeners.toList().forEach { it(state) }
        }
        override fun play() { playCount++ }
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
        override fun addListener(listener: (PlaybackState) -> Unit) { listeners += listener }
        override fun removeListener(listener: (PlaybackState) -> Unit) { listeners -= listener }

        fun emit(next: PlaybackState) {
            state = next
            listeners.toList().forEach { it(next) }
        }
    }

    private class RecordingAdvanceCommand : QueueAdvanceCommand {
        val callbacks = mutableListOf<(QueueAdvanceCommandResult) -> Unit>()

        override fun skip(
            roomCode: String,
            hostToken: String,
            callback: (QueueAdvanceCommandResult) -> Unit,
        ) {
            assertEquals("ABCD", roomCode)
            assertEquals("host-secret", hostToken)
            callbacks += callback
        }

        fun complete(result: QueueAdvanceCommandResult) {
            callbacks.last()(result)
        }
    }

    private class RecordingReconciler : RoomStateFetcher {
        private var callback: ((RoomFetchResult) -> Unit)? = null

        override fun fetch(roomCode: String, callback: (RoomFetchResult) -> Unit): Cancelable {
            assertEquals("ABCD", roomCode)
            this.callback = callback
            return Cancelable { }
        }

        fun complete(result: RoomFetchResult) {
            callback?.invoke(result)
        }
    }

    private class RecordingRoomRepository : RoomRepository {
        var observedCode: String? = null
        var closed = false
        private var observer: ((RoomSyncState) -> Unit)? = null

        override fun observe(roomCode: String, onUpdate: (RoomSyncState) -> Unit): AutoCloseable {
            observedCode = roomCode
            observer = onUpdate
            return AutoCloseable { closed = true }
        }

        fun publish(state: RoomSyncState) {
            observer?.invoke(state)
        }
    }
}
