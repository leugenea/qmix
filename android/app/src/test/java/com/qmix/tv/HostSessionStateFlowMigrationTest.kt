package com.qmix.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** qmix#182: the application session publishes one authoritative state stream. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSessionStateFlowMigrationTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun settings_and_warning_are_visible_to_independent_collectors() = runTest {
        val controller = HostSessionController(OkHttpClient(), settingsPersistence = object : EndpointSettingsPersistence {
            override fun load() = EndpointSettings.EMPTY
            override fun save(settings: EndpointSettings) = Unit
            override fun isHttpWarningAcknowledged() = false
            override fun acknowledgeHttpWarning() = Unit
        })
        val first = mutableListOf<HostingState>()
        val second = mutableListOf<HostingState>()
        backgroundScope.launch(Dispatchers.Unconfined) { controller.states.take(3).toList(first) }
        backgroundScope.launch(Dispatchers.Unconfined) { controller.states.take(3).toList(second) }
        controller.updateSettings("http://192.168.1.20:8180", "https://guest.example")
        controller.createRoom()
        runCurrent()
        val expected = listOf(
            HostingState.Setup("", ""),
            HostingState.Setup("http://192.168.1.20:8180", "https://guest.example"),
            HostingState.HttpWarning("http://192.168.1.20:8180", "https://guest.example"),
        )
        assertEquals(expected, first)
        assertEquals(expected, second)
    }

    @Test fun cancelled_warning_never_creates_a_request_or_allows_duplicate_admission() = runTest {
        val persistence = object : EndpointSettingsPersistence {
            override fun load() = EndpointSettings.EMPTY
            override fun save(settings: EndpointSettings) = Unit
            override fun isHttpWarningAcknowledged() = false
            override fun acknowledgeHttpWarning() = Unit
        }
        val controller = HostSessionController(OkHttpClient(), settingsPersistence = persistence)
        val seen = mutableListOf<HostingState>()
        backgroundScope.launch(Dispatchers.Unconfined) { controller.states.collect { seen += it } }
        controller.updateSettings("http://192.168.1.20:8180", "https://guest.example")
        assertTrue(controller.createRoom())
        assertFalse(controller.createRoom())
        controller.cancelHttpWarning()
        assertTrue(controller.state is HostingState.Setup)
        assertEquals(1, seen.count { it is HostingState.HttpWarning })
        assertFalse(seen.any { it is HostingState.Pending || it is HostingState.Invitation })
    }

    @Test fun failing_collector_does_not_stop_state_production_or_another_collector() = runTest {
        val controller = HostSessionController(OkHttpClient())
        var failures = 0
        backgroundScope.launch(Dispatchers.Unconfined) {
            try { controller.states.collect { throw IllegalStateException("collector failed") } }
            catch (_: IllegalStateException) { failures++ }
        }
        val seen = mutableListOf<HostingState>()
        backgroundScope.launch(Dispatchers.Unconfined) { controller.states.take(2).toList(seen) }
        controller.updateSettings("https://api.example", "https://guest.example")
        assertEquals(1, failures)
        assertEquals(listOf(HostingState.Setup("", ""), controller.state), seen)
    }

    @Test fun ending_then_creating_a_new_session_preserves_only_the_new_invitation() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(201)
                .setBody("""{"code":"ABCD","host_token":"first","url":"/r/ABCD"}"""))
            server.enqueue(MockResponse().setResponseCode(201)
                .setBody("""{"code":"WXYZ","host_token":"second","url":"/r/WXYZ"}"""))
            val controller = HostSessionController(
                OkHttpClient(), initialBackendUrl = server.url("/").toString(),
                initialGuestOrigin = "https://guest.example", roomCollectionScope = backgroundScope,
            )
            assertTrue(controller.createRoom())
            assertFalse(controller.createRoom())
            withContext(Dispatchers.IO) {
                withTimeout(5_000) { controller.states.first { it is HostingState.Invitation } }
            }
            controller.endRoom()
            assertTrue(controller.state is HostingState.Ending || controller.state is HostingState.Setup)
            withContext(Dispatchers.IO) {
                withTimeout(5_000) { controller.states.first { it is HostingState.Setup } }
            }
            assertTrue(controller.createRoom())
            val second = withContext(Dispatchers.IO) {
                withTimeout(5_000) {
                    controller.states.first { it is HostingState.Invitation && it.invite.code == "WXYZ" }
                }
            }
            assertEquals("WXYZ", (second as HostingState.Invitation).invite.code)
            assertEquals(2, server.requestCount)
            controller.endRoom()
        } finally {
            server.shutdown()
        }
    }
}
