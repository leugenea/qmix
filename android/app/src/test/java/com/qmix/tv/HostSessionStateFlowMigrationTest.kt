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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)), roomCollectionScope = backgroundScope, httpClient = OkHttpClient(), settingsPersistence = object : EndpointSettingsPersistence {
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
        runCurrent()
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancelled_warning_never_creates_a_request_or_allows_duplicate_admission() = runTest {
        val persistence = object : EndpointSettingsPersistence {
            override fun load() = EndpointSettings.EMPTY
            override fun save(settings: EndpointSettings) = Unit
            override fun isHttpWarningAcknowledged() = false
            override fun acknowledgeHttpWarning() = Unit
        }
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)), roomCollectionScope = backgroundScope, httpClient = OkHttpClient(), settingsPersistence = persistence)
        val seen = mutableListOf<HostingState>()
        backgroundScope.launch(Dispatchers.Unconfined) { controller.states.collect { seen += it } }
        controller.updateSettings("http://192.168.1.20:8180", "https://guest.example")
        runCurrent()
        controller.createRoom()
        controller.createRoom()
        controller.cancelHttpWarning()
        runCurrent()
        assertTrue(controller.state is HostingState.Setup)
        assertEquals(1, seen.count { it is HostingState.HttpWarning })
        assertFalse(seen.any { it is HostingState.Pending || it is HostingState.Invitation })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun failing_collector_does_not_stop_state_production_or_another_collector() = runTest {
        val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)), roomCollectionScope = backgroundScope, httpClient = OkHttpClient())
        var failures = 0
        backgroundScope.launch(Dispatchers.Unconfined) {
            try { controller.states.collect { throw IllegalStateException("collector failed") } }
            catch (_: IllegalStateException) { failures++ }
        }
        val seen = mutableListOf<HostingState>()
        backgroundScope.launch(Dispatchers.Unconfined) { controller.states.take(2).toList(seen) }
        controller.updateSettings("https://api.example", "https://guest.example")
        runCurrent()
        assertEquals(1, failures)
        assertEquals(listOf(HostingState.Setup("", ""), controller.state), seen)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun ending_then_creating_a_new_session_preserves_only_the_new_invitation() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val creations = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "DELETE" && request.path == "/rooms/ABCD" ->
                        MockResponse().setResponseCode(204)
                    request.method == "DELETE" && request.path == "/rooms/WXYZ" ->
                        MockResponse().setResponseCode(204)
                    request.method == "POST" && request.path == "/rooms" -> {
                        val code = if (creations.getAndIncrement() == 0) "ABCD" else "WXYZ"
                        MockResponse().setResponseCode(201)
                            .setBody("""{"code":"$code","host_token":"host","url":"/r/$code"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val controller = HostSessionController(queueMutationContext = QueueMutationContext(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)),
                httpClient = OkHttpClient(), initialBackendUrl = server.url("/").toString(),
                initialGuestOrigin = "https://guest.example", roomCollectionScope = backgroundScope,
            )
            controller.createRoom()
            controller.createRoom()
            runCurrent()
            withContext(Dispatchers.IO) {
                withTimeout(5_000) { controller.states.first { it is HostingState.Invitation } }
            }
            controller.endRoom()
            runCurrent()
            assertTrue(controller.state is HostingState.Ending || controller.state is HostingState.Setup)
            withContext(Dispatchers.IO) {
                withTimeout(5_000) { controller.states.first { it is HostingState.Setup } }
            }
            controller.createRoom()
            runCurrent()
            val second = withContext(Dispatchers.IO) {
                withTimeout(5_000) {
                    controller.states.first { it is HostingState.Invitation && it.invite.code == "WXYZ" }
                }
            }
            assertEquals("WXYZ", (second as HostingState.Invitation).invite.code)
            val requests = (1..3).map {
                checkNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            assertEquals(2, requests.count { it.method == "POST" && it.path == "/rooms" })
            assertEquals(1, requests.count { it.method == "DELETE" && it.path == "/rooms/ABCD" })
            assertEquals(3, server.requestCount)
            controller.endRoom()
        } finally {
            server.shutdown()
        }
    }
}
