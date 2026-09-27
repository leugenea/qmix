package com.qmix.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomFetchSuspendTest {
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        adapterScope.cancel()
        server.shutdown()
    }

    @Test
    fun async_fetch_decodes_success_and_classifies_not_found() {
        val client = RoomApiClient(OkHttpClient(), server.url("/").toString())
        server.enqueue(MockResponse().setBody("""{"code":"ABCD","current":null,"queue":[]}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        val results = mutableListOf<RoomFetchResult>()
        awaitCompletion(adapterScope.launch { results += client.fetchRoom("ABCD") })
        awaitCompletion(adapterScope.launch { results += client.fetchRoom("MISSING") })

        assertTrue(results.contains(RoomFetchResult.Success(RoomState("ABCD", null, emptyList()))))
        assertTrue(results.contains(RoomFetchResult.Missing))
    }

    @Test
    fun response_body_disconnect_is_reported_as_a_failure() {
        val client = RoomApiClient(OkHttpClient(), server.url("/").toString())
        server.enqueue(
            MockResponse()
                .setBody("""{"code":"ABCD","current":null,"queue":[]}""" + " ".repeat(8_192))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        var result: RoomFetchResult? = null

        awaitCompletion(adapterScope.launch { result = client.fetchRoom("ABCD") })
        assertEquals(RoomFetchResult.Failure, result)
    }

    @Test
    fun cancel_aborts_the_underlying_http_call() {
        val startedCall = AtomicReference<Call>()
        val httpClient = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { startedCall.set(call) }
        }).build()
        val client = RoomApiClient(httpClient, server.url("/").toString())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val completed = CountDownLatch(1)

        val request = adapterScope.launch { client.fetchRoom("ABCD"); completed.countDown() }
        // This timeout guards a hang; request arrival, not elapsed time, establishes readiness.
        assertEquals("/rooms/ABCD", server.takeRequest(60, TimeUnit.SECONDS)?.path)
        request.cancel()
        awaitCompletion(request)

        assertTrue(request.isCancelled)
        assertTrue("Coroutine cancellation must cancel the OkHttp call", startedCall.get()?.isCanceled() == true)
        assertEquals(1L, completed.count)
    }

    private fun awaitCompletion(request: Job) = runBlocking {
        // Only a safety limit; assertions run after the coroutine has finished.
        withTimeout(60_000) { request.join() }
    }
}
