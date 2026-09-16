package com.qmix.tv

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSessionLifecycleTest {
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
        server.start()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var controller: HostSessionController
        var observation: AutoCloseable? = null
        val invitationReady = CountDownLatch(1)
        try {
            scenario.onActivity { activity ->
                controller = (activity.application as QMixApplication).hostSession
                controller.updateSettings(server.url("/").toString(), "https://guest.example")
                observation = controller.observe { state ->
                    if (state is HostingState.Invitation) invitationReady.countDown()
                }
                server.enqueue(
                    MockResponse().setResponseCode(201)
                        .setBody("""{"code":"ABCD","host_token":"host-secret","url":"/r/ABCD"}"""),
                )
                assertTrue(controller.createRoom())
            }
            assertTrue(invitationReady.await(5, TimeUnit.SECONDS))

            scenario.onActivity {
                controller.enterRoom()
                assertTrue(controller.state is HostingState.LiveRoom)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue(activity.isFinishing)
            }

            assertTrue(controller.state is HostingState.Setup)
        } finally {
            observation?.close()
            scenario.close()
            server.shutdown()
        }
    }
}
