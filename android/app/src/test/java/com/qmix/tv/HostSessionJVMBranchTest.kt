package com.qmix.tv

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** qmix#217: controller admission and setup branches exercised without Robolectric. */
class HostSessionJVMBranchTest {
    @Test fun invalid_endpoint_rejects_create_and_clears_untrusted_input() {
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Unconfined), httpClient = OkHttpClient())
        controller.updateSettings("https://user:secret@host.example", "https://guest.example")
        controller.createRoom()
        controller.awaitStateForTest("invalid endpoint rejected") { it is HostingState.Error }
        assertEquals(HostingState.Error(UserMessage.INVALID_ENDPOINT, "", ""), controller.state)
        assertFalse(controller.state.toString().contains("secret"))
        controller.confirmHttpWarning()
        controller.cancelHttpWarning()
        assertEquals(HostingState.Error(UserMessage.INVALID_ENDPOINT, "", ""), controller.state)
        controller.enterRoom()
        assertEquals(HostingState.Error(UserMessage.INVALID_ENDPOINT, "", ""), controller.state)
    }

    @Test fun warning_cancellation_does_not_acknowledge_or_admit_network_creation() {
        val acknowledgements = AtomicInteger()
        val persistence = object : EndpointSettingsPersistence {
            override fun load() = EndpointSettings("http://backend.example", "https://guest.example")
            override fun save(settings: EndpointSettings) = error("no request permitted")
            override fun isHttpWarningAcknowledged() = false
            override fun acknowledgeHttpWarning() { acknowledgements.incrementAndGet() }
        }
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Unconfined), httpClient = OkHttpClient(), settingsPersistence = persistence)
        controller.createRoom()
        controller.awaitStateForTest("HTTP warning published") { it is HostingState.HttpWarning }
        assertEquals(HostingState.HttpWarning("http://backend.example", "https://guest.example"), controller.state)
        controller.createRoom()
        controller.enterRoom()
        controller.setCommandPending(true)
        controller.onPlaybackEnded("unknown")
        controller.cancelHttpWarning()
        controller.awaitSetupForTest()
        assertEquals(HostingState.Setup("http://backend.example", "https://guest.example"), controller.state)
        assertEquals(0, acknowledgements.get())
        controller.confirmHttpWarning()
        controller.createRoom()
        controller.awaitStateForTest("HTTP warning published") { it is HostingState.HttpWarning }
        assertEquals(HostingState.HttpWarning("http://backend.example", "https://guest.example"), controller.state)
        assertEquals(0, acknowledgements.get())
    }

    @Test fun failed_warning_persistence_reprompts_despite_an_in_memory_acknowledgement() {
        val attempts = AtomicInteger()
        val acknowledged = java.util.concurrent.atomic.AtomicBoolean(false)
        val persistence = object : EndpointSettingsPersistence {
            override fun load() = EndpointSettings("http://backend.example", "https://guest.example")
            override fun save(settings: EndpointSettings) = error("no request permitted")
            override fun isHttpWarningAcknowledged() = acknowledged.get()
            override fun acknowledgeHttpWarning() {
                attempts.incrementAndGet()
                acknowledged.set(true) // A failed commit may already be visible in memory.
                throw IllegalStateException("disk unavailable")
            }
        }
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Unconfined), httpClient = OkHttpClient(), settingsPersistence = persistence)
        controller.createRoom()
        controller.confirmHttpWarning()
        controller.awaitStateForTest("durable warning persistence failed") { it is HostingState.Error }
        assertEquals(HostingState.Error(UserMessage.PERSISTENCE_ERROR, "http://backend.example", "https://guest.example"), controller.state)
        controller.createRoom()
        controller.awaitStateForTest("HTTP warning published") { it is HostingState.HttpWarning }
        assertEquals(HostingState.HttpWarning("http://backend.example", "https://guest.example"), controller.state)
        assertEquals(1, attempts.get())
    }

    @Test fun no_session_actions_leave_setup_unchanged() {
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Unconfined), httpClient = OkHttpClient())
        val initial = controller.state
        controller.confirmHttpWarning()
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
        controller.onPlaybackEnded("unknown")
        controller.onBack()
        controller.updateSettings("https://backend.example", "https://guest.example")
        controller.awaitStateForTest("ineligible inputs processed before settings") { it is HostingState.Setup && it.backendUrl == "https://backend.example" }
        assertEquals(HostingState.Setup("https://backend.example", "https://guest.example"), controller.state)
    }

    @Test fun removed_last_queue_item_restores_focus_to_the_previous_survivor() {
        val focus = LiveRoomFocusMemory()
        assertEquals(LiveRoomFocusTarget.Primary, focus.reconcile(listOf("one", "two"), true).target)
        focus.record(LiveRoomFocusTarget.Track("two"))
        assertEquals(LiveRoomFocusTarget.Track("one"), focus.reconcile(listOf("one"), true).target)
        assertEquals(LiveRoomFocusTarget.Track("one"), focus.reconcile(listOf("one"), true).target)
    }

    @Test fun focus_falls_back_to_action_when_last_track_disappears() {
        val focus = LiveRoomFocusMemory()
        focus.reconcile(listOf("only"), primaryEnabled = true)
        focus.record(LiveRoomFocusTarget.Track("only"))
        assertEquals(LiveRoomFocusTarget.Primary, focus.reconcile(emptyList(), primaryEnabled = true).target)
        focus.record(LiveRoomFocusTarget.Track("missing"))
        assertEquals(LiveRoomFocusTarget.Invite, focus.reconcile(emptyList(), primaryEnabled = false).target)
    }

    @Test fun vanished_playback_control_falls_back_to_available_control_then_invite() {
        val focus = LiveRoomFocusMemory()
        focus.record(LiveRoomFocusTarget.SeekForward)
        assertEquals(LiveRoomFocusTarget.PlayPause, focus.reconcile(emptyList(), false,
            listOf(LiveRoomFocusTarget.PlayPause)).target)
        assertEquals(LiveRoomFocusTarget.Invite, focus.reconcile(emptyList(), false).target)
        focus.record(LiveRoomFocusTarget.Retry)
        assertEquals(LiveRoomFocusTarget.Primary, focus.reconcile(emptyList(), true).target)
        focus.record(LiveRoomFocusTarget.Primary)
        assertEquals(LiveRoomFocusTarget.Invite, focus.reconcile(emptyList(), false).target)
    }

    @Test fun invalid_reconnect_bounds_fail_before_a_retry_can_be_scheduled() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ReconnectBackoff(initialMillis = 0) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ReconnectBackoff(initialMillis = 100, maximumMillis = 99)
        }
        assertEquals(50L, ReconnectBackoff(initialMillis = 100, maximumMillis = 100,
            randomFraction = { 0.0 }).delayMillis(1))
    }

    @Test fun default_live_room_handler_playback_methods_have_no_side_effects() {
        val commands = AtomicInteger()
        val handler = object : LiveRoomHandler {
            override fun onStartOrNext() { commands.incrementAndGet() }
            override fun onInvite() { commands.incrementAndGet() }
            override fun onBack(onExit: () -> Unit) = Unit
        }
        handler.onPlayPause()
        handler.onPlay()
        handler.onPause()
        handler.onSeekBy(12_000)
        handler.onRetryCurrent()
        assertEquals(0, commands.get())
        handler.onBack()
    }
}
