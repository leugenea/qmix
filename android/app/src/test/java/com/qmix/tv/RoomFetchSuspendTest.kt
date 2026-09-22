package com.qmix.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
        val firstCompleted = CountDownLatch(1)
        adapterScope.launch { results += client.fetchRoom("ABCD"); firstCompleted.countDown() }
        assertTrue(firstCompleted.await(5, TimeUnit.SECONDS))

        val secondCompleted = CountDownLatch(1)
        adapterScope.launch { results += client.fetchRoom("MISSING"); secondCompleted.countDown() }
        assertTrue(secondCompleted.await(5, TimeUnit.SECONDS))

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
        val completed = CountDownLatch(1)
        var result: RoomFetchResult? = null

        adapterScope.launch { result = client.fetchRoom("ABCD"); completed.countDown() }

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(RoomFetchResult.Failure, result)
    }

    @Test
    fun cancel_aborts_the_underlying_http_call() {
        val client = RoomApiClient(OkHttpClient(), server.url("/").toString())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val completed = CountDownLatch(1)

        val request = adapterScope.launch { client.fetchRoom("ABCD"); completed.countDown() }
        assertEquals("/rooms/ABCD", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        request.cancel()

        assertFalse(completed.await(200, TimeUnit.MILLISECONDS))
    }
}
