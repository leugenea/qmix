package com.qmix.tv

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSessionLifecycleTest {
    @Test
    fun media_keys_dispatch_once_while_dpad_left_and_right_remain_unhandled() {
        val actions = mutableListOf<String>()
        val handler = object : LiveRoomHandler {
            override fun onStartOrNext() = Unit
            override fun onPlayPause() { actions += "toggle" }
            override fun onPlay() { actions += "play" }
            override fun onPause() { actions += "pause" }
            override fun onInvite() = Unit
            override fun onBack(onExit: () -> Unit) = Unit
        }

        assertTrue(dispatchPlaybackMediaKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), handler))
        assertTrue(dispatchPlaybackMediaKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), handler))
        assertTrue(
            dispatchPlaybackMediaKey(
                KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1),
                handler,
            ),
        )
        assertTrue(dispatchPlaybackMediaKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY), handler))
        assertTrue(dispatchPlaybackMediaKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE), handler))
        assertFalse(dispatchPlaybackMediaKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT), handler))
        assertFalse(dispatchPlaybackMediaKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT), handler))

        assertEquals(listOf("toggle", "play", "pause"), actions)
    }

    @Test
    fun activity_host_session_provider_lease_restores_application_state() {
        val application = ApplicationProvider.getApplicationContext<QMixApplication>()
        val first = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Unconfined), httpClient = OkHttpClient())
        val second = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Unconfined), httpClient = OkHttpClient())
        val firstLease = application.installActivityHostSessionProvider { first }
        try {
            assertSame(first, application.hostSessionForActivity())
        } finally {
            firstLease.close()
        }

        val secondLease = application.installActivityHostSessionProvider { second }
        try {
            assertSame(second, application.hostSessionForActivity())
        } finally {
            secondLease.close()
        }
    }

    @Test
    fun activity_teardown_ends_sync_only_when_the_host_session_actually_finishes() {
        assertEquals(true, shouldEndHostSession(isFinishing = true, isChangingConfigurations = false))
        assertEquals(false, shouldEndHostSession(isFinishing = false, isChangingConfigurations = false))
        assertEquals(false, shouldEndHostSession(isFinishing = true, isChangingConfigurations = true))
    }

    @Test
    fun in_memory_session_survives_activity_recreation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var before: HostSessionController
        scenario.onActivity { activity ->
            before = (activity.application as QMixApplication).hostSession
            before.updateSettings("https://api.example", "https://guest.example")
        }

        scenario.recreate()

        scenario.onActivity { activity ->
            val after = (activity.application as QMixApplication).hostSession
            assertSame(before, after)
            assertEquals(
                HostingState.Setup("https://api.example", "https://guest.example"),
                after.state,
            )
        }
        scenario.close()
    }

    @Test
    fun back_from_the_live_room_finishes_the_activity_and_ends_the_host_session() {
        val server = MockWebServer()
        val creations = LinkedBlockingQueue<RecordedRequest>()
        val deletions = LinkedBlockingQueue<RecordedRequest>()
        val deleteCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method to request.path) {
                "POST" to "/rooms" -> {
                    creations.put(request)
                    MockResponse().setResponseCode(201)
                        .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}""")
                }
                "GET" to "/rooms/ABCD" -> MockResponse().setResponseCode(200)
                    .setBody("""{"code":"ABCD","current":null,"queue":[]}""")
                "GET" to "/rooms/ABCD/events" -> MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBodyDelay(10, TimeUnit.SECONDS)
                    .setBody(":\n\n")
                "DELETE" to "/rooms/ABCD" -> {
                    deleteCount.incrementAndGet()
                    deletions.put(request)
                    MockResponse().setResponseCode(204)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var controller: HostSessionController
        var observation: AutoCloseable? = null
        val invitationReady = CountDownLatch(1)
        try {
            scenario.onActivity { activity ->
                controller = (activity.application as QMixApplication).hostSession
                controller.updateSettings(server.url("/").toString(), "https://guest.example")
                observation = controller.collectStatesForTest { state ->
                    if (state is HostingState.Invitation) invitationReady.countDown()
                }
                controller.createRoom()
            }
            awaitMain("creation security decision") {
                controller.state is HostingState.HttpWarning || controller.state is HostingState.Invitation
            }
            if (controller.state is HostingState.HttpWarning) {
                scenario.onActivity { controller.confirmHttpWarning() }
            }
            awaitMain("invitation ready") { invitationReady.count == 0L }
            scenario.onActivity { controller.enterRoom() }
            awaitMain("live room entered") { controller.state is HostingState.LiveRoom }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            lateinit var shownActivity: MainActivity
            scenario.onActivity { activity ->
                shownActivity = activity
                activity.onBackPressedDispatcher.onBackPressed()
            }
            awaitMain("Back UI exit effect") { shownActivity.isFinishing }
            awaitMain("host cleanup Setup") { controller.state is HostingState.Setup }
            val creation = checkNotNull(creations.poll(5, TimeUnit.SECONDS))
            val deletion = checkNotNull(deletions.poll(5, TimeUnit.SECONDS))
            assertEquals("POST", creation.method)
            assertEquals("/rooms", creation.path)
            assertEquals("DELETE", deletion.method)
            assertEquals("/rooms/ABCD", deletion.path)
            assertEquals("host-secret", deletion.getHeader("X-Host-Token"))
            // The application owns the close job; count only after that scope has settled.
            val closeScope = QMixApplication::class.java.getDeclaredField("finalNetworkScope")
                .apply { isAccessible = true }
                .get(ApplicationProvider.getApplicationContext<QMixApplication>()) as CoroutineScope
            runBlocking {
                withTimeout(5_000) { closeScope.coroutineContext[Job]!!.children.toList().joinAll() }
            }
            assertEquals(1, deleteCount.get())
        } finally {
            observation?.close()
            scenario.close()
            server.shutdown()
        }
    }

    private fun awaitMain(step: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("$step: Android Main boundary not reached", predicate())
    }

}
