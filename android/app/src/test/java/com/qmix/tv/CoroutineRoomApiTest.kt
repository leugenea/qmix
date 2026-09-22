package com.qmix.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** qmix#178: cancellation owns the underlying request, including response decoding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoroutineRoomApiTest {
    @Test
    fun room_fetch_suspend_call_cancels_with_its_parent_and_delivers_no_result() = runBlocking {
        val parent = kotlinx.coroutines.Job(coroutineContext[kotlinx.coroutines.Job])
        val owned = kotlinx.coroutines.CoroutineScope(coroutineContext + parent + Dispatchers.Default)
        val canceled = CountDownLatch(1)
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.countDown() }
        }).build()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
                val api = RoomApiClient(client, server.url("/").toString())
                owned.launch { api.fetchRoom("ABCD"); callbacks.incrementAndGet() }
                org.junit.Assert.assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                parent.cancel()
                parent.join()
                assertTrue(canceled.await(5, TimeUnit.SECONDS))
                org.junit.Assert.assertEquals(0, callbacks.get())
            }
        } finally { parent.cancel() }
    }

    @Test
    fun every_suspend_operation_cancels_while_waiting_for_a_response() = runBlocking {
        val operations: List<suspend (RoomApiClient) -> Unit> = listOf(
            { it.createRoom(); Unit },
            { it.getRoom("ABCD"); Unit },
            { it.skip("ABCD", "fixture"); Unit },
            { it.reportPlayer("ABCD", "fixture", PlayerReport("one", PlayerReportState.PAUSED, 0)); Unit },
        )
        operations.forEach { operation ->
            val canceled = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { canceled.countDown() }
            }).build()
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
                val request = launch(Dispatchers.Default) { operation(RoomApiClient(client, server.url("/").toString())) }
                org.junit.Assert.assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                request.cancel()
                assertTrue(canceled.await(5, TimeUnit.SECONDS))
                request.join()
                org.junit.Assert.assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun cancellation_while_decoding_cancels_the_call() = runBlocking {
        val canceled = CountDownLatch(1)
        val headers = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.countDown() }
            override fun responseHeadersEnd(call: Call, response: okhttp3.Response) { headers.countDown() }
        }).build()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"ABCD","current":null,"queue":[]}""")
                .setBodyDelay(2, TimeUnit.SECONDS))
            val api = RoomApiClient(client, server.url("/").toString())
            val request = launch(Dispatchers.Default) {
                try { api.getRoom("ABCD") } catch (failure: RoomApiException) {
                    if (isActive) throw failure
                }
            }
            assertTrue(headers.await(5, TimeUnit.SECONDS))
            request.cancel()
            assertTrue("Coroutine cancellation must cancel OkHttp during body decoding", canceled.await(1, TimeUnit.SECONDS))
            request.join()
        }
    }
}
