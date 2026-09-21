package com.qmix.tv

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

/** Deterministic delivery races that a real socket cannot reliably schedule (qmix#178). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OkHttpSuspendTest {
    @Test
    fun cancellation_before_enqueue_never_starts_a_call() = runTest {
        val call = ControlledCall()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            call.awaitDecoded { error("Canceled call must not decode") }
        }
        job.join()
        assertFalse(call.enqueued)
        assertTrue(call.canceled)
    }

    @Test
    fun cancellation_waiting_for_headers_cancels_and_late_response_is_closed_without_decode() = runTest {
        val call = ControlledCall()
        var decoded = false
        val job = launch { call.awaitDecoded { decoded = true } }
        runCurrent()
        job.cancel()
        val body = ClosingBody()
        call.deliver(body)
        runCurrent()
        assertTrue(call.canceled)
        assertTrue(body.closed)
        assertFalse(decoded)
    }

    @Test
    fun cancellation_waiting_for_headers_ignores_late_failure_and_completes_once() = runTest {
        val call = ControlledCall()
        var completions = 0
        val job = launch {
            call.awaitDecoded<Unit> { error("Canceled call must not decode") }
            error("Canceled call must not return")
        }
        job.invokeOnCompletion { completions++ }
        runCurrent()
        assertTrue(call.enqueued)
        job.cancel()
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1, completions)

        call.fail(IOException("late failure"))
        runCurrent()
        assertTrue(call.isCanceled())
        assertTrue(job.isCancelled)
        assertTrue(job.isCompleted)
        assertEquals(1, completions)
    }

    @Test
    fun cancellation_inside_decode_closes_response_and_discards_result() = runTest {
        val call = ControlledCall()
        var delivered = false
        lateinit var job: kotlinx.coroutines.Job
        job = launch { call.awaitDecoded { job.cancel(); "decoded" }; delivered = true }
        runCurrent()
        val body = ClosingBody()
        call.deliver(body)
        runCurrent()
        assertTrue(body.closed)
        assertTrue(call.canceled)
        assertFalse(delivered)
    }

    @Test
    fun cancellation_after_callback_before_dispatch_discards_result_with_no_leaked_response() = runTest {
        val call = ControlledCall()
        var delivered = false
        val job = launch(StandardTestDispatcher(testScheduler)) {
            call.awaitDecoded { "decoded" }
            delivered = true
        }
        runCurrent()
        val body = ClosingBody()
        call.deliver(body)
        assertTrue(body.closed)
        job.cancel()
        runCurrent()
        assertFalse(delivered)
        assertTrue(call.canceled)
    }

    @Test
    fun failure_during_decode_closes_response_and_success_completes_once() = runTest {
        val call = ControlledCall()
        var completions = 0
        val job = launch {
            try { call.awaitDecoded { throw IOException("fixture") } }
            catch (_: IOException) { completions++ }
        }
        runCurrent()
        val body = ClosingBody()
        call.deliver(body)
        runCurrent()
        job.join()
        assertTrue(body.closed)
        assertEquals(1, completions)
    }

    private class ClosingBody : ResponseBody() {
        private val delegate = "{}".toResponseBody()
        var closed = false
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source(): BufferedSource = delegate.source()
        override fun close() { closed = true; delegate.close() }
    }

    private class ControlledCall : Call by okhttp3.OkHttpClient().newCall(
        Request.Builder().url("https://example.test").build(),
    ) {
        var enqueued = false
        var canceled = false
        private lateinit var callback: Callback
        override fun request() = Request.Builder().url("https://example.test").build()
        override fun execute(): Response = error("Suspend transport must enqueue")
        override fun enqueue(responseCallback: Callback) { enqueued = true; callback = responseCallback }
        override fun cancel() { canceled = true }
        override fun isExecuted() = enqueued
        override fun isCanceled() = canceled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = ControlledCall()
        fun fail(failure: IOException) = callback.onFailure(this, failure)
        fun deliver(body: ResponseBody) = callback.onResponse(this, Response.Builder()
            .request(request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build())
    }
}
