package com.qmix.tv

import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveRoomPresentationTest {
    private val roomScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        roomScope.cancel()
        server.shutdown()
    }

    @Test
    fun local_playback_resources_cover_every_actual_state_and_sanitize_errors() {
        assertEquals(R.string.playback_status_idle, localPlaybackStatusResource(LocalPlaybackStatus.IDLE))
        assertEquals(R.string.playback_status_buffering, localPlaybackStatusResource(LocalPlaybackStatus.BUFFERING))
        assertEquals(R.string.playback_status_playing, localPlaybackStatusResource(LocalPlaybackStatus.PLAYING))
        assertEquals(R.string.playback_status_paused, localPlaybackStatusResource(LocalPlaybackStatus.PAUSED))
        assertEquals(R.string.playback_status_completed, localPlaybackStatusResource(LocalPlaybackStatus.COMPLETED))
        assertEquals(R.string.playback_status_error, localPlaybackStatusResource(LocalPlaybackStatus.ERROR))

        assertEquals(
            FormattedText(R.string.playback_stream_request_failed_http, 502),
            localPlaybackErrorText(PlaybackError(PlaybackErrorKind.HTTP, "token=do-not-render", 502)),
        )
        assertEquals(
            FormattedText(R.string.playback_stream_seek_failed_http, 416),
            localPlaybackErrorText(PlaybackError(PlaybackErrorKind.RANGE, "secret URL", 416)),
        )
        assertEquals(
            FormattedText(R.string.playback_decode_failed),
            localPlaybackErrorText(PlaybackError(PlaybackErrorKind.DECODE, "decoder internals")),
        )
        assertEquals(
            FormattedText(R.string.playback_network_interrupted),
            localPlaybackErrorText(PlaybackError(PlaybackErrorKind.NETWORK, "private endpoint")),
        )
        assertEquals(
            FormattedText(R.string.playback_unknown_failed),
            localPlaybackErrorText(PlaybackError(PlaybackErrorKind.UNKNOWN, "stack details")),
        )
    }

    @Test
    fun primary_action_requires_a_fresh_connected_room_with_a_nonempty_queue() {
        val invite = GuestInvite("ABCD", "https://guest.example/r/ABCD")
        val queued = QueuedTrack("track-1", "https://example/1", "Title", "Artist", 0, "fixture")
        val room = RoomState("ABCD", null, listOf(queued))
        val ready = HostingState.LiveRoom(invite, active(room))

        assertEquals(LiveRoomPrimaryAction.START, ready.primaryAction)
        assertTrue(ready.isPrimaryActionEnabled)
        assertFalse(ready.copy(commandPending = true).isPrimaryActionEnabled)
        assertFalse(ready.copy(synchronization = active(room.copy(queue = emptyList()))).isPrimaryActionEnabled)
        assertFalse(ready.copy(synchronization = active(room, freshness = Freshness.STALE)).isPrimaryActionEnabled)
        assertFalse(ready.copy(synchronization = active(room, connection = LiveConnection.RECONNECTING)).isPrimaryActionEnabled)
        assertFalse(ready.copy(synchronization = active(null)).isPrimaryActionEnabled)
        assertFalse(ready.copy(synchronization = RoomSyncState.Missing("ABCD")).isPrimaryActionEnabled)
    }

    @Test
    fun current_track_changes_the_primary_action_without_conflating_an_empty_queue() {
        val current = CurrentTrack("current", 0, "playing", "Current", "Artist")
        val presentation = HostingState.LiveRoom(
            GuestInvite("ABCD", "https://guest.example/r/ABCD"),
            active(RoomState("ABCD", current, emptyList())),
        )

        assertEquals(LiveRoomPrimaryAction.NEXT, presentation.primaryAction)
        assertFalse(presentation.isPrimaryActionEnabled)
    }

    @Test
    fun repository_updates_are_published_and_stale_null_retains_the_last_safe_room() {
        val repository = RecordingRoomRepository()
        val controller = createController(repository)
        val observed = mutableListOf<HostingState>()
        val loadingDelivered = java.util.concurrent.CountDownLatch(1)
        val freshDelivered = java.util.concurrent.CountDownLatch(1)
        val staleDelivered = java.util.concurrent.CountDownLatch(1)
        controller.collectStatesForTest { state ->
            observed += state
            val sync = (state as? HostingState.LiveRoom)?.synchronization as? RoomSyncState.Active
            if (sync?.freshness == Freshness.LOADING) loadingDelivered.countDown()
            if (sync?.freshness == Freshness.FRESH) freshDelivered.countDown()
            if (sync?.freshness == Freshness.STALE) staleDelivered.countDown()
        }
        createAndEnter(controller)
        assertTrue(loadingDelivered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val room = RoomState("ABCD", null, emptyList())

        repository.publish(active(room))
        assertTrue(freshDelivered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        repository.publish(active(null, freshness = Freshness.STALE, connection = LiveConnection.RECONNECTING))

        assertTrue(staleDelivered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val liveUpdates = observed.filterIsInstance<HostingState.LiveRoom>()
        assertEquals(3, liveUpdates.size)
        assertEquals(Freshness.LOADING, (liveUpdates[0].synchronization as RoomSyncState.Active).freshness)
        assertEquals(Freshness.FRESH, (liveUpdates[1].synchronization as RoomSyncState.Active).freshness)
        assertEquals(room, (liveUpdates[2].synchronization as RoomSyncState.Active).room)
        assertEquals("https://guest.example/r/ABCD", liveUpdates.last().invite.guestUrl)
        assertFalse(liveUpdates.last().toString().contains("host-secret"))
        assertEquals(liveUpdates.last().synchronization, controller.roomSyncState)
    }

    @Test
    fun missing_and_connection_states_are_published_without_inventing_room_data() {
        val repository = RecordingRoomRepository()
        val controller = createController(repository)
        val observed = mutableListOf<HostingState>()
        val reconnectDelivered = java.util.concurrent.CountDownLatch(1)
        val missingDelivered = java.util.concurrent.CountDownLatch(1)
        controller.collectStatesForTest { state ->
            observed += state
            val sync = (state as? HostingState.LiveRoom)?.synchronization
            if ((sync as? RoomSyncState.Active)?.connection == LiveConnection.RECONNECTING) reconnectDelivered.countDown()
            if (sync is RoomSyncState.Missing) missingDelivered.countDown()
        }
        createAndEnter(controller)

        repository.publish(active(null, connection = LiveConnection.RECONNECTING))
        assertTrue(reconnectDelivered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        repository.publish(RoomSyncState.Missing("ABCD"))
        assertTrue(missingDelivered.await(5, java.util.concurrent.TimeUnit.SECONDS))

        val reconnecting = observed[observed.lastIndex - 1] as HostingState.LiveRoom
        val reconnectingState = reconnecting.synchronization as RoomSyncState.Active
        assertEquals(LiveConnection.RECONNECTING, reconnectingState.connection)
        assertNull(reconnectingState.room)
        assertTrue((observed.last() as HostingState.LiveRoom).synchronization is RoomSyncState.Missing)
    }

    @Test
    fun late_repository_updates_after_back_ends_the_session_are_ignored() {
        val repository = RecordingRoomRepository()
        val controller = createController(repository)
        createAndEnter(controller)
        val observed = mutableListOf<HostingState>()
        controller.collectStatesForTest(observed::add)

        assertEquals(LiveRoomBackResult.EXIT_ACTIVITY, controller.onBack())
        assertTrue(controller.awaitSetupForTest())
        val countAfterBack = observed.size
        repository.publish(active(RoomState("ABCD", null, emptyList())))

        assertTrue(repository.closed)
        assertEquals(countAfterBack, observed.size)
        assertNull(controller.roomSyncState)
    }

    @Test
    fun handler_boundary_dispatches_primary_action_and_models_invite_and_back() {
        val repository = RecordingRoomRepository()
        var commands = 0
        val controller = createController(repository, onStartOrNext = { commands++ })
        createAndEnter(controller)
        val queued = QueuedTrack("track-1", "https://example/1", "Title", "Artist", 0, "fixture")
        repository.publish(active(RoomState("ABCD", null, listOf(queued))))

        controller.onStartOrNext()
        controller.setCommandPending(true)
        controller.onStartOrNext()
        controller.onInvite()

        assertEquals(1, commands)
        assertTrue((controller.state as HostingState.LiveRoom).invitationVisible)
        assertEquals(LiveRoomBackResult.HANDLED, controller.onBack())
        assertFalse((controller.state as HostingState.LiveRoom).invitationVisible)
        assertFalse(repository.closed)
        assertEquals(LiveRoomBackResult.EXIT_ACTIVITY, controller.onBack())
        assertTrue(repository.closed)
        assertTrue(controller.awaitSetupForTest())
    }

    @Test
    fun repeated_and_out_of_session_handler_inputs_are_noops() {
        val repository = RecordingRoomRepository()
        var commands = 0
        val controller = createController(repository, onStartOrNext = { commands++ })
        val observed = mutableListOf<HostingState>()
        controller.collectStatesForTest(observed::add)

        controller.setCommandPending(true)
        controller.onStartOrNext()
        controller.onInvite()
        assertEquals(LiveRoomBackResult.IGNORED, controller.onBack())

        assertEquals(0, commands)
        assertEquals(1, observed.size)

        createAndEnter(controller)
        val countBeforeDuplicateInputs = observed.size
        controller.setCommandPending(false)
        controller.onInvite()
        controller.onInvite()
        controller.setCommandPending(false)

        assertEquals(countBeforeDuplicateInputs + 1, observed.size)
    }

    @Test
    fun update_for_a_different_room_is_ignored() {
        val repository = RecordingRoomRepository()
        val controller = createController(repository)
        createAndEnter(controller)
        val initial = controller.state

        repository.publish(
            RoomSyncState.Active(
                "WXYZ",
                RoomState("WXYZ", null, emptyList()),
                Freshness.FRESH,
                LiveConnection.CONNECTED,
            ),
        )

        assertEquals(initial, controller.state)
    }

    @Test
    fun returned_subscription_is_closed_when_a_synchronous_callback_ends_the_session() {
        val repository = SynchronousRoomRepository()
        lateinit var controller: HostSessionController
        controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",

            roomRepositoryFactory = { repository },
            roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
        )
        controller.collectStatesForTest { state ->
            val active = (state as? HostingState.LiveRoom)?.synchronization as? RoomSyncState.Active
            if (active?.freshness == Freshness.FRESH) controller.onBack()
        }

        createAndEnter(controller)

        assertTrue(repository.closed)
        assertTrue(controller.awaitSetupForTest())
    }

    @Test
    fun replaced_same_code_session_rejects_late_callback_from_old_repository() {
        val first = RecordingRoomRepository()
        val second = RecordingRoomRepository()
        val repositories = ArrayDeque(listOf(first, second))
        val controller = HostSessionController(
            OkHttpClient(),
            initialBackendUrl = server.url("/").toString(),
            initialGuestOrigin = "https://guest.example",

            roomRepositoryFactory = { repositories.removeFirst() },
            roomCollectionScope = roomScope,
            roomCollectionContext = Dispatchers.Unconfined,
        )

        createAndEnter(controller)
        controller.endRoom()
        createAndEnter(controller)
        val secondInitialState = controller.state
        first.publish(active(RoomState("ABCD", null, emptyList())))

        assertEquals(secondInitialState, controller.state)
        assertTrue(first.closed)
        assertFalse(second.closed)
    }

    @Test
    fun reentrant_and_failing_observers_cannot_reorder_or_block_delivery() {
        val repository = RecordingRoomRepository()
        val controller = createController(repository)
        val delivered = mutableListOf<Boolean>()
        val delivery = java.util.concurrent.CountDownLatch(1)
        var madePending = false
        controller.collectStatesForTest { state ->
            val active = (state as? HostingState.LiveRoom)?.synchronization as? RoomSyncState.Active
            if (active?.freshness == Freshness.FRESH && !madePending) {
                madePending = true
                controller.setCommandPending(true)
            }
        }
        controller.collectStatesForTest { state ->
            val active = (state as? HostingState.LiveRoom)?.synchronization as? RoomSyncState.Active
            if (active?.freshness == Freshness.FRESH) {
                delivered += state.commandPending
                delivery.countDown()
            }
        }

        createAndEnter(controller)
        controller.collectStatesForTest { state -> if (state is HostingState.LiveRoom) error("observer failure") }
        repository.publish(active(RoomState("ABCD", null, emptyList())))

        assertTrue(delivery.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf(true), delivered)
        assertTrue((controller.state as HostingState.LiveRoom).commandPending)
    }

    private fun createController(
        repository: RecordingRoomRepository,
        onStartOrNext: () -> Unit = {},
    ): HostSessionController = HostSessionController(
        OkHttpClient(),
        initialBackendUrl = server.url("/").toString(),
        initialGuestOrigin = "https://guest.example",

        roomRepositoryFactory = { repository },
        roomCollectionScope = roomScope,
        roomCollectionContext = Dispatchers.Unconfined,
        primaryActionHandler = onStartOrNext,

    )

    private fun createAndEnter(controller: HostSessionController) {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
        )
        assertTrue(controller.createRoom())
        controller.awaitCreatedForTest()
        controller.enterRoom()
    }

    private fun active(
        room: RoomState?,
        freshness: Freshness = Freshness.FRESH,
        connection: LiveConnection = LiveConnection.CONNECTED,
    ): RoomSyncState.Active = RoomSyncState.Active("ABCD", room, freshness, connection)

    private class RecordingRoomRepository : RoomRepository {
        var closed = false
        private val states = Channel<RoomSyncState>(Channel.UNLIMITED)

        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            assertEquals("ABCD", roomCode)
            try {
                for (state in states) emit(state)
            } finally {
                closed = true
            }
        }

        fun publish(state: RoomSyncState) {
            states.trySend(state)
        }
    }

    private class SynchronousRoomRepository : RoomRepository {
        var closed = false

        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            try {
                emit(
                    RoomSyncState.Active(
                        roomCode,
                        RoomState(roomCode, null, emptyList()),
                        Freshness.FRESH,
                        LiveConnection.CONNECTED,
                    ),
                )
            } finally {
                closed = true
            }
        }
    }
}
