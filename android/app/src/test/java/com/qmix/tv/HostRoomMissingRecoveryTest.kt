package com.qmix.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** qmix#264: definitive Missing is a session boundary, not a reconnectable failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostRoomMissingRecoveryTest {
    private val guestOrigin = "https://guest.example"

    @Test fun never_used_room_replaces_once_after_old_worker_stops_and_shows_new_invite() = withHost { host ->
        host.server.enqueue(created("WXYZ", "replacement-token"))
        host.startLiveRoom()
        host.repository.publish("ABCD", active("ABCD", emptyRoom()))
        host.controller.awaitRoomStateForTest { (it.synchronization as? RoomSyncState.Active)?.room == emptyRoom() }

        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        assertTrue("old collection did not begin cancellation", host.repository.oldCleanupStarted.await(5, TimeUnit.SECONDS))
        // The new POST must wait for the old collection's full cleanup, not just cancel().
        host.mutation.mutationContext.awaitLaneIdleForTest()
        assertEquals("replacement started before worker join", 1, host.server.requestCount)
        host.repository.releaseOldCleanup.complete(Unit)

        val replacement = host.awaitState("replacement invitation") {
            it is HostingState.Invitation && it.invite.code == "WXYZ"
        } as HostingState.Invitation
        assertTrue("old worker still running at invitation", host.repository.oldCleanupDone.get())
        assertTrue(replacement.roomReplacementNotice)
        assertEquals(GuestInvite("WXYZ", "$guestOrigin/r/WXYZ"), replacement.invite)
        assertEquals(2, host.server.requestCount)
        val initialRequest = host.server.takeRequest(5, TimeUnit.SECONDS)
        val replacementRequest = host.server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("POST", initialRequest?.method)
        assertEquals("/rooms", initialRequest?.path)
        assertEquals("POST", replacementRequest?.method)
        assertEquals("/rooms", replacementRequest?.path)
        assertFalse(host.controller.createRoom())
        assertEquals(2, host.server.requestCount)
        host.controller.enterRoom()
        assertEquals("WXYZ", (host.controller.state as HostingState.LiveRoom).invite.code)
    }

    @Test fun observed_queue_track_stays_missing_until_explicit_new_room() = withHost { host ->
        host.server.enqueue(created("WXYZ", "replacement-token"))
        host.startLiveRoom()
        val queued = RoomState("ABCD", null, listOf(track()))
        host.repository.publish("ABCD", active("ABCD", queued))
        host.controller.awaitRoomStateForTest {
            (it.synchronization as? RoomSyncState.Active)?.room == queued
        }
        host.repository.publish("ABCD", active("ABCD", emptyRoom()))
        host.controller.awaitRoomStateForTest {
            (it.synchronization as? RoomSyncState.Active)?.room == emptyRoom()
        }

        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        val missing = host.controller.awaitRoomStateForTest { it.synchronization == RoomSyncState.Missing("ABCD") }
        assertEquals(null, missing.replacementError)
        assertFalse(missing.isPrimaryActionEnabled)
        assertTrue(host.repository.oldCleanupStarted.await(5, TimeUnit.SECONDS))
        host.repository.releaseOldCleanup.complete(Unit)
        assertTrue(host.repository.oldCleanupDoneSignal.await(5, TimeUnit.SECONDS))
        host.mutation.mutationContext.awaitLaneIdleForTest()
        assertEquals("a used room was silently replaced", 1, host.server.requestCount)

        host.controller.onNewRoom()
        val invitation = host.awaitState("explicit New room invitation") {
            it is HostingState.Invitation && it.invite.code == "WXYZ"
        } as HostingState.Invitation
        assertEquals("WXYZ", invitation.invite.code)
        assertEquals(2, host.server.requestCount)
    }

    @Test fun failed_automatic_creation_shows_error_without_a_second_automatic_post() = withHost { host ->
        host.server.enqueue(MockResponse().setResponseCode(503)
            .setBody("""{"error":"room_capacity_exhausted","message":"room capacity is temporarily exhausted"}"""))
        host.startLiveRoom()
        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        host.repository.releaseOldCleanup.complete(Unit)
        val failed = host.controller.awaitRoomStateForTest { it.replacementError != null }
        assertNotNull("replacement failure must be visible and recoverable", failed.replacementError)
        assertEquals(RoomSyncState.Missing("ABCD"), failed.synchronization)
        assertTrue(host.repository.oldCleanupDone.get())
        host.mutation.mutationContext.awaitLaneIdleForTest()
        assertEquals("automatic create retried after a definitive failure", 2, host.server.requestCount)
        assertFalse(host.controller.createRoom())
        assertEquals(2, host.server.requestCount)

        host.server.enqueue(created("WXYZ", "manual-token"))
        host.controller.onNewRoom()
        val manual = host.awaitState("manual retry after failed replacement") {
            it is HostingState.Invitation && it.invite.code == "WXYZ"
        } as HostingState.Invitation
        assertTrue(manual.roomReplacementNotice)
        assertEquals(3, host.server.requestCount)
    }

    @Test fun immediate_404_on_automatic_replacement_shows_missing_without_post_loop() = withHost { host ->
        host.server.enqueue(created("WXYZ", "replacement-token"))
        host.startInvitation()
        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        host.repository.releaseOldCleanup.complete(Unit)
        val replacement = host.awaitState("automatic replacement invitation") {
            it is HostingState.Invitation && it.invite.code == "WXYZ"
        } as HostingState.Invitation
        assertTrue(replacement.roomReplacementNotice)
        host.repository.awaitSubscriber("WXYZ")
        host.repository.publish("WXYZ", RoomSyncState.Missing("WXYZ"))
        val missing = host.controller.awaitRoomStateForTest { it.invite.code == "WXYZ" &&
            it.synchronization == RoomSyncState.Missing("WXYZ") }
        assertFalse(missing.isPrimaryActionEnabled)
        host.mutation.mutationContext.awaitLaneIdleForTest()
        assertEquals("unvalidated automatic replacement looped", 2, host.server.requestCount)
        host.controller.onHostStopped()
        host.controller.onHostStarted()
        assertEquals("missing room retried after lifecycle change", 2, host.server.requestCount)
        host.server.enqueue(created("CDEF", "manual-token"))
        host.controller.onNewRoom()
        host.awaitState("manual replacement after unvalidated room") {
            it is HostingState.Invitation && it.invite.code == "CDEF"
        }
        assertEquals(3, host.server.requestCount)
    }

    @Test fun wrong_room_code_cannot_validate_an_automatic_replacement() = withHost { host ->
        host.server.enqueue(created("WXYZ", "replacement-token"))
        host.startLiveRoom()
        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        host.repository.releaseOldCleanup.complete(Unit)
        host.awaitState("replacement invitation") {
            it is HostingState.Invitation && it.invite.code == "WXYZ"
        }
        host.repository.awaitSubscriber("WXYZ", generation = 1)
        host.controller.enterRoom()
        host.repository.awaitSubscriber("WXYZ", generation = 2)
        val wrongRoom = RoomState("ABCD", null, emptyList())
        host.repository.publish("WXYZ", active("WXYZ", wrongRoom))
        host.repository.awaitDelivered("WXYZ", generation = 2, count = 1)
        val live = host.controller.state as HostingState.LiveRoom
        assertEquals("WXYZ", live.invite.code)
        assertEquals(null, (live.synchronization as RoomSyncState.Active).room)
        host.repository.publish("WXYZ", RoomSyncState.Missing("WXYZ"))
        host.controller.awaitRoomStateForTest {
            it.invite.code == "WXYZ" && it.synchronization == RoomSyncState.Missing("WXYZ")
        }
        assertEquals("mismatched read admitted another automatic POST", 2, host.server.requestCount)
    }

    @Test fun wrong_room_code_on_invitation_cannot_validate_replacement() = withHost { host ->
        host.server.enqueue(created("WXYZ", "replacement-token"))
        host.startInvitation()
        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        host.repository.releaseOldCleanup.complete(Unit)
        host.awaitState("replacement invitation") {
            it is HostingState.Invitation && it.invite.code == "WXYZ"
        }
        host.repository.awaitSubscriber("WXYZ")
        host.repository.publish("WXYZ", active("WXYZ", RoomState("ABCD", null, emptyList())))
        host.repository.awaitDelivered("WXYZ", generation = 1, count = 1)
        host.repository.publish("WXYZ", RoomSyncState.Missing("WXYZ"))
        host.controller.awaitRoomStateForTest {
            it.invite.code == "WXYZ" && it.synchronization == RoomSyncState.Missing("WXYZ")
        }
        assertEquals("mismatched invitation read admitted another automatic POST", 2, host.server.requestCount)
    }

    @Test fun validated_automatic_replacement_can_recover_if_it_later_expires() = withHost { host ->
        host.server.enqueue(created("WXYZ", "replacement-token"))
        host.server.enqueue(created("CDEF", "later-token"))
        host.startInvitation()
        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        host.repository.releaseOldCleanup.complete(Unit)
        host.awaitState("replacement invitation") { it is HostingState.Invitation && it.invite.code == "WXYZ" }
        host.repository.awaitSubscriber("WXYZ")
        host.repository.publish("WXYZ", active("WXYZ", RoomState("WXYZ", null, emptyList())))
        host.repository.awaitDelivered("WXYZ", generation = 1, count = 1)
        host.repository.publish("WXYZ", RoomSyncState.Missing("WXYZ"))
        host.awaitState("validated replacement expires") { it is HostingState.Invitation && it.invite.code == "CDEF" }
        assertEquals(3, host.server.requestCount)
    }

    @Test fun home_during_old_worker_join_defers_replacement_post_until_return() = withHost { host ->
        host.server.enqueue(created("WXYZ", "replacement-token"))
        host.startLiveRoom()
        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        assertTrue(host.repository.oldCleanupStarted.await(5, TimeUnit.SECONDS))
        host.controller.onHostStopped()
        host.repository.releaseOldCleanup.complete(Unit)
        assertTrue(host.repository.oldCleanupDoneSignal.await(5, TimeUnit.SECONDS))
        host.mutation.mutationContext.awaitLaneIdleForTest()
        assertEquals("backgrounded old-worker finalizer posted a replacement", 1, host.server.requestCount)
        host.controller.onHostStarted()
        host.awaitState("deferred foreground replacement") {
            it is HostingState.Invitation && it.invite.code == "WXYZ"
        }
        assertEquals(2, host.server.requestCount)
    }

    @Test fun home_during_pending_replacement_post_checks_new_room_only_on_return() = withHost { host ->
        val releasePost = CountDownLatch(1)
        host.startInvitation()
        assertEquals("POST", host.server.takeRequest(5, TimeUnit.SECONDS)?.method)
        host.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                check(releasePost.await(5, TimeUnit.SECONDS)) { "replacement POST was not released" }
                return created("WXYZ", "replacement-token")
            }
        }
        try {
            host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
            host.repository.releaseOldCleanup.complete(Unit)
            host.awaitState("replacement POST pending") { it is HostingState.Pending }
            val request = host.server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("POST", request?.method)
            host.controller.onHostStopped()
            releasePost.countDown()
            val invitation = host.awaitState("background replacement invitation") {
                it is HostingState.Invitation && it.invite.code == "WXYZ"
            } as HostingState.Invitation
            assertTrue(invitation.foregroundRecoveryPending)
            assertEquals("backgrounded replacement created a monitor", 1, host.repository.factoryCalls.get())
            assertEquals("replacement was monitored in background", 0, host.repository.observationCount("WXYZ"))
            assertEquals(2, host.server.requestCount)
            host.server.dispatcher = okhttp3.mockwebserver.QueueDispatcher()
            host.server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"code":"WXYZ","current":null,"queue":[]}"""))
            host.controller.onHostStarted()
            host.awaitState("new invitation reconciled") {
                it is HostingState.Invitation && it.invite.code == "WXYZ" && !it.foregroundRecoveryPending
            }
            host.repository.awaitSubscriber("WXYZ", generation = 1)
            assertEquals(2, host.repository.factoryCalls.get())
            assertEquals(1, host.repository.observationCount("WXYZ"))
            assertEquals(3, host.server.requestCount)
            assertEquals("GET", host.server.takeRequest(5, TimeUnit.SECONDS)?.method)
        } finally {
            releasePost.countDown()
        }
    }

    @Test fun stale_missing_for_previous_code_cannot_replace_the_new_session() = withHost { host ->
        host.server.enqueue(created("WXYZ", "replacement-token"))
        host.startLiveRoom()
        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        host.repository.releaseOldCleanup.complete(Unit)
        host.awaitState("replacement invitation") {
            it is HostingState.Invitation && it.invite.code == "WXYZ"
        }
        host.repository.awaitSubscriber("WXYZ", generation = 1)
        host.controller.enterRoom()
        host.repository.awaitSubscriber("WXYZ", generation = 2)
        host.repository.awaitUnsubscribed("ABCD", generation = 2)
        // The new observer receives a delayed event carrying the old room's code.
        // A removed room-code guard would turn this into a second replacement.
        host.repository.publish("WXYZ", RoomSyncState.Missing("ABCD"))
        host.repository.awaitDelivered("WXYZ", generation = 2, count = 1)
        host.repository.publish("WXYZ", active("WXYZ", RoomState("WXYZ", null, emptyList())))
        host.controller.awaitRoomStateForTest {
            it.invite.code == "WXYZ" &&
                (it.synchronization as? RoomSyncState.Active)?.freshness == Freshness.FRESH
        }
        host.mutation.mutationContext.awaitLaneIdleForTest()
        assertEquals("WXYZ", (host.controller.state as HostingState.LiveRoom).invite.code)
        assertEquals(2, host.server.requestCount)
    }

    @Test fun foreground_reconciliation_404_replaces_an_unused_room_only_after_return() = withHost { host ->
        host.server.enqueue(MockResponse().setResponseCode(404)
            .setBody("""{"error":"room_not_found","message":"room not found"}"""))
        host.server.enqueue(created("WXYZ", "replacement-token"))
        host.startLiveRoom()
        host.repository.publish("ABCD", active("ABCD", emptyRoom()))
        host.controller.awaitRoomStateForTest { (it.synchronization as? RoomSyncState.Active)?.room == emptyRoom() }

        host.controller.onHostStopped()
        host.controller.awaitRoomStateForTest { it.foregroundRecoveryPending }
        assertEquals("background created a room", 1, host.server.requestCount)
        host.repository.releaseOldCleanup.complete(Unit)
        host.controller.onHostStarted()
        val replacement = host.awaitState("foreground missing replacement") {
            it is HostingState.Invitation && it.invite.code == "WXYZ"
        } as HostingState.Invitation
        assertTrue(replacement.roomReplacementNotice)
        assertEquals(3, host.server.requestCount)
        val initialRequest = host.server.takeRequest(5, TimeUnit.SECONDS)
        val recoveryRequest = host.server.takeRequest(5, TimeUnit.SECONDS)
        val replacementRequest = host.server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("POST", initialRequest?.method)
        assertEquals("/rooms", initialRequest?.path)
        assertEquals("GET", recoveryRequest?.method)
        assertEquals("/rooms/ABCD", recoveryRequest?.path)
        assertEquals("POST", replacementRequest?.method)
        assertEquals("/rooms", replacementRequest?.path)
    }

    @Test fun transient_foreground_failure_retains_old_code_and_never_creates_a_room() = withHost { host ->
        host.server.enqueue(MockResponse().setResponseCode(503)
            .setBody("""{"error":"unavailable","message":"temporarily unavailable"}"""))
        host.startLiveRoom()
        host.repository.publish("ABCD", active("ABCD", emptyRoom()))
        host.controller.awaitRoomStateForTest { (it.synchronization as? RoomSyncState.Active)?.room == emptyRoom() }
        host.controller.onHostStopped()
        host.repository.releaseOldCleanup.complete(Unit)
        assertTrue(host.repository.oldCleanupDoneSignal.await(5, TimeUnit.SECONDS))
        host.controller.onHostStarted()
        host.repository.awaitSubscriber("ABCD", generation = 3)
        assertEquals(3, host.repository.observationCount("ABCD"))
        host.repository.publish("ABCD", RoomSyncState.Active("ABCD", null, Freshness.STALE, LiveConnection.RECONNECTING))
        val stale = host.controller.awaitRoomStateForTest {
            val sync = it.synchronization as? RoomSyncState.Active
            it.invite.code == "ABCD" && sync?.freshness == Freshness.STALE
        }
        assertEquals("ABCD", stale.invite.code)
        assertEquals(emptyRoom(), (stale.synchronization as RoomSyncState.Active).room)
        assertEquals(null, stale.replacementError)
        assertEquals(2, host.server.requestCount)
    }

    @Test fun previously_observed_current_track_alone_prevents_automatic_replacement() = withHost { host ->
        host.startLiveRoom()
        val playing = RoomState("ABCD", CurrentTrack("one", 0, "playing", "One", "Artist"), emptyList())
        host.repository.publish("ABCD", active("ABCD", playing))
        host.controller.awaitRoomStateForTest { (it.synchronization as? RoomSyncState.Active)?.room == playing }
        host.repository.publish("ABCD", active("ABCD", emptyRoom()))
        host.controller.awaitRoomStateForTest { (it.synchronization as? RoomSyncState.Active)?.room == emptyRoom() }
        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        host.controller.awaitRoomStateForTest { it.synchronization == RoomSyncState.Missing("ABCD") }
        host.repository.releaseOldCleanup.complete(Unit)
        assertTrue(host.repository.oldCleanupDoneSignal.await(5, TimeUnit.SECONDS))
        host.mutation.mutationContext.awaitLaneIdleForTest()
        assertEquals("current-only history was forgotten", 1, host.server.requestCount)
    }

    @Test fun used_invitation_missing_hides_dead_qr_and_requires_manual_new_room() = withHost { host ->
        host.server.enqueue(created("WXYZ", "replacement-token"))
        host.startInvitation()
        host.repository.publish("ABCD", active("ABCD", RoomState("ABCD", null, listOf(track()))))
        assertTrue(host.repository.trackDelivered.await(5, TimeUnit.SECONDS))
        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        val missing = host.controller.awaitRoomStateForTest { it.synchronization is RoomSyncState.Missing }
        assertEquals("ABCD", missing.invite.code)
        assertEquals(1, host.server.requestCount)
        host.repository.releaseOldCleanup.complete(Unit)
        host.controller.onNewRoom()
        val newInvite = host.awaitState("used invitation manual replacement") {
            it is HostingState.Invitation && it.invite.code == "WXYZ"
        } as HostingState.Invitation
        assertTrue(newInvite.roomReplacementNotice)
        assertEquals(2, host.server.requestCount)
    }

    @Test fun invitation_replacement_failure_shows_recoverable_action_without_retry() = withHost { host ->
        host.server.enqueue(MockResponse().setResponseCode(503)
            .setBody("""{"error":"room_capacity_exhausted","message":"room capacity is temporarily exhausted"}"""))
        host.startInvitation()
        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        host.repository.releaseOldCleanup.complete(Unit)
        val failed = host.controller.awaitRoomStateForTest { it.replacementError != null }
        assertEquals(RoomSyncState.Missing("ABCD"), failed.synchronization)
        assertEquals(2, host.server.requestCount)
        host.mutation.mutationContext.awaitLaneIdleForTest()
        assertEquals(2, host.server.requestCount)
    }

    @Test fun invitation_missing_replaces_once_after_observer_cleanup() = withHost { host ->
        host.server.enqueue(created("WXYZ", "replacement-token"))
        host.startInvitation()
        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        assertTrue(host.repository.oldCleanupStarted.await(5, TimeUnit.SECONDS))
        assertEquals(1, host.server.requestCount)
        host.repository.releaseOldCleanup.complete(Unit)
        val replacement = host.awaitState("invitation replacement") {
            it is HostingState.Invitation && it.invite.code == "WXYZ"
        } as HostingState.Invitation
        assertTrue(replacement.roomReplacementNotice)
        host.repository.awaitSubscriber("WXYZ")
        host.repository.publish("WXYZ", RoomSyncState.Missing("ABCD"))
        host.repository.awaitDelivered("WXYZ", generation = 1, count = 1)
        assertTrue(host.controller.state is HostingState.Invitation)
        host.mutation.mutationContext.awaitLaneIdleForTest()
        assertEquals(2, host.server.requestCount)
    }

    @Test fun track_seen_on_invitation_prevents_silent_replacement_even_after_enter() = withHost { host ->
        host.startInvitation()
        host.repository.publish("ABCD", active("ABCD", RoomState("ABCD", null, listOf(track()))))
        assertTrue(host.repository.trackDelivered.await(5, TimeUnit.SECONDS))
        host.controller.enterRoom()
        assertTrue("invitation worker was not cancelled on Enter", host.repository.oldCleanupDoneSignal.await(5, TimeUnit.SECONDS))
        host.repository.awaitSubscriber("ABCD", generation = 2)
        host.repository.publish("ABCD", RoomSyncState.Missing("ABCD"))
        host.controller.awaitRoomStateForTest { it.synchronization is RoomSyncState.Missing }
        host.mutation.mutationContext.awaitLaneIdleForTest()
        assertEquals(1, host.server.requestCount)
    }

    @Test fun invitation_stops_in_background_and_checks_on_return() = withHost { host ->
        host.server.enqueue(MockResponse().setResponseCode(404)
            .setBody("""{"error":"room_not_found","message":"room not found"}"""))
        host.server.enqueue(created("WXYZ", "replacement-token"))
        host.startInvitation()
        host.controller.onHostStopped()
        host.repository.awaitUnsubscribed("ABCD")
        assertEquals(1, host.server.requestCount)
        host.controller.onHostStarted()
        val newInvite = host.awaitState("foreground invitation replacement") {
            it is HostingState.Invitation && it.invite.code == "WXYZ"
        } as HostingState.Invitation
        assertTrue(newInvite.roomReplacementNotice)
        assertEquals(3, host.server.requestCount)
    }

    @Test fun invitation_transient_recovery_failure_restarts_monitor_without_post() = withHost { host ->
        host.server.enqueue(MockResponse().setResponseCode(503)
            .setBody("""{"error":"unavailable","message":"temporarily unavailable"}"""))
        host.startInvitation()
        host.controller.onHostStopped()
        host.repository.awaitUnsubscribed("ABCD")
        host.controller.onHostStarted()
        host.repository.awaitSubscriber("ABCD", generation = 2)
        assertEquals(2, host.repository.observationCount("ABCD"))
        assertEquals("ABCD", (host.controller.state as HostingState.Invitation).invite.code)
        assertEquals(2, host.server.requestCount)
    }

    @Test fun ending_invitation_cancels_its_observer_before_setup() = withHost { host ->
        host.startInvitation()
        host.server.enqueue(MockResponse().setResponseCode(204))
        host.controller.endRoom()
        host.controller.awaitSetupForTest()
        host.repository.awaitUnsubscribed("ABCD", generation = 1)
        assertTrue(host.repository.oldCleanupDone.get())
        assertEquals(1, host.repository.observationCount("ABCD"))
        assertTrue(host.controller.state is HostingState.Setup)
        val requests = (1..2).map { checkNotNull(host.server.takeRequest(5, TimeUnit.SECONDS)) }
        assertEquals(1, requests.count { it.method == "POST" && it.path == "/rooms" })
        assertEquals(1, requests.count { it.method == "DELETE" && it.path == "/rooms/ABCD" })
        assertEquals(2, host.server.requestCount)
    }

    private fun emptyRoom() = RoomState("ABCD", null, emptyList())
    private fun track() = QueuedTrack("one", "https://example/one", "One", "Artist", 60, "fixture")
    private fun active(code: String, room: RoomState) =
        RoomSyncState.Active(code, room, Freshness.FRESH, LiveConnection.CONNECTED)

    private fun created(code: String, token: String) = MockResponse().setResponseCode(201)
        .setBody("""{"code":"$code","host_token":"$token","url":"/r/$code"}""")

    private fun withHost(body: (Host) -> Unit) {
        val server = MockWebServer()
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mutation = SerializedTestMutationContext("missing-recovery-mutation")
        val repository = ControlledRepository()
        server.enqueue(created("ABCD", "first-token"))
        val client = OkHttpClient()
        val controller = HostSessionController(
            client, initialBackendUrl = server.url("/").toString(), initialGuestOrigin = guestOrigin,
            roomCollectionScope = scope, roomCollectionContext = Dispatchers.IO,
            queueMutationContext = mutation.mutationContext,
            roomRepositoryFactory = { repository.factoryCalls.incrementAndGet(); repository },
            foregroundReconcilerFactory = { backend -> { code -> RoomApiClient(client, backend).fetchRoom(code) } },
        )
        val host = Host(server, mutation, repository, controller)
        try {
            body(host)
        } finally {
            repository.releaseOldCleanup.complete(Unit)
            controller.abandonRoom()
            controller.awaitSetupForTest()
            scope.cancel()
            mutation.close()
            server.shutdown()
        }
    }

    private class Host(
        val server: MockWebServer,
        val mutation: SerializedTestMutationContext,
        val repository: ControlledRepository,
        val controller: HostSessionController,
    ) {
        fun startInvitation() {
            assertTrue(controller.createRoom())
            controller.awaitCreatedForTest()
            assertEquals("ABCD", (controller.state as HostingState.Invitation).invite.code)
            repository.awaitSubscriber("ABCD")
        }

        fun startLiveRoom() {
            startInvitation()
            controller.enterRoom()
            assertTrue("invitation observer did not stop on Enter", repository.oldCleanupDoneSignal.await(5, TimeUnit.SECONDS))
            repository.resetOldCleanupForLiveRoom()
            repository.awaitSubscriber("ABCD", generation = 2)
        }

        fun awaitState(step: String, predicate: (HostingState) -> Boolean): HostingState = runBlocking {
            try {
                withTimeout(5_000) { controller.states.first(predicate) }
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("$step: last state=${controller.state}", failure)
            }
        }
    }

    private class ControlledRepository : RoomRepository {
        private val streams = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, MutableSharedFlow<RoomSyncState>>()
        private val starts = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<Int>>()
        private val deliveries = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, MutableStateFlow<Int>>()
        val factoryCalls = AtomicInteger()
        var oldCleanupStarted = CountDownLatch(1)
            private set
        var oldCleanupDoneSignal = CountDownLatch(1)
            private set
        var oldCleanupDone = AtomicBoolean(false)
            private set
        var releaseOldCleanup = CompletableDeferred<Unit>()
            private set
        val trackDelivered = CountDownLatch(1)
        private val blockOldCleanup = AtomicBoolean(false)
        private var oldCleanupGeneration = 1

        fun resetOldCleanupForLiveRoom() {
            oldCleanupStarted = CountDownLatch(1)
            oldCleanupDoneSignal = CountDownLatch(1)
            oldCleanupDone = AtomicBoolean(false)
            releaseOldCleanup = CompletableDeferred()
            oldCleanupGeneration = 2
            blockOldCleanup.set(false)
        }

        private fun stream(code: String, generation: Int): MutableSharedFlow<RoomSyncState> =
            streams.computeIfAbsent(code to generation) { MutableSharedFlow(extraBufferCapacity = 16) }

        private fun latestGeneration(code: String) = starts[code]?.value ?: 1

        fun observationCount(code: String): Int = starts[code]?.value ?: 0

        fun awaitDelivered(code: String, generation: Int, count: Int) = runBlocking {
            try {
                withTimeout(5_000) {
                    deliveries.computeIfAbsent(code to generation) { MutableStateFlow(0) }.first { it >= count }
                }
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("$code collection $generation did not deliver update $count", failure)
            }
        }

        fun awaitSubscriber(code: String, generation: Int = 1) = runBlocking {
            try {
                withTimeout(5_000) {
                    starts.computeIfAbsent(code) { MutableStateFlow(0) }.first { it >= generation }
                    stream(code, generation).subscriptionCount.first { it > 0 }
                }
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("$code collection $generation did not subscribe", failure)
            }
        }

        fun awaitUnsubscribed(code: String, generation: Int = latestGeneration(code)) = runBlocking {
            try {
                withTimeout(5_000) { stream(code, generation).subscriptionCount.first { it == 0 } }
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("$code collection $generation did not stop", failure)
            }
        }

        fun publish(code: String, state: RoomSyncState) {
            val generation = latestGeneration(code)
            awaitSubscriber(code, generation)
            if (code == "ABCD" && state is RoomSyncState.Missing) blockOldCleanup.set(true)
            assertTrue("$code generation $generation rejected update", stream(code, generation).tryEmit(state))
        }

        override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
            val count = starts.computeIfAbsent(roomCode) { MutableStateFlow(0) }
            val generation = count.value + 1
            count.value = generation
            val delivered = deliveries.computeIfAbsent(roomCode to generation) { MutableStateFlow(0) }
            try {
                stream(roomCode, generation).collect { update ->
                    emit(update)
                    delivered.value += 1
                    val room = (update as? RoomSyncState.Active)?.room
                    if (room?.current != null || room?.queue?.isNotEmpty() == true) trackDelivered.countDown()
                }
            } finally {
                if (roomCode == "ABCD" && generation == oldCleanupGeneration) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        oldCleanupStarted.countDown()
                        if (blockOldCleanup.get()) releaseOldCleanup.await()
                        oldCleanupDone.set(true)
                        oldCleanupDoneSignal.countDown()
                    }
                }
            }
        }
    }
}
