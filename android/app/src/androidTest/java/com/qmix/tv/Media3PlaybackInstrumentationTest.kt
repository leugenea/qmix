package com.qmix.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.N)
class Media3PlaybackInstrumentationTest {
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @org.junit.After
    fun cancelQueueScope() { queueScope.cancel() }

    private lateinit var targetContext: Context
    private lateinit var fixtureContext: Context
    private lateinit var server: RangeAssetServer
    private var engine: PlaybackEngine? = null

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        targetContext = instrumentation.targetContext
        fixtureContext = instrumentation.context
        listOf("tone.webm", "tone.m4a", "corrupt.webm").forEach { fixture ->
            fixtureContext.assets.open(fixture).use { input ->
                assertTrue("fixture $fixture must be readable from the instrumentation APK", input.read() >= 0)
            }
        }
        server = RangeAssetServer(fixtureContext).also { it.start() }
    }

    @After
    fun tearDown() {
        onMain { engine?.release() }
        server.close()
        server.assertHealthy()
    }

    @Test
    fun pause_before_prepare_and_release_are_safe_and_idempotent() {
        val playback = createEngine()

        onMain {
            playback.pause()
            playback.release()
            playback.release()
        }

        assertEquals(PlaybackStatus.RELEASED, playback.state.status)
    }

    @Test
    fun decodes_webm_opus_to_ready_and_ended() {
        val playback = createEngine()
        prepare(playback, "opus", server.url("tone.webm"))
        awaitStatus(playback, PlaybackStatus.READY)
        onMain { playback.play() }
        await { playback.state.positionMs > 0 }
        awaitStatus(playback, PlaybackStatus.ENDED, timeoutMs = 20_000)
    }

    @Test
    fun decodes_m4a_aac_plays_and_advances_after_seek_using_a_new_range() {
        val playback = createEngine()
        prepare(playback, "aac", server.url("tone.m4a"))
        awaitStatus(playback, PlaybackStatus.READY)
        assertTrue(playback.state.isSeekable)
        onMain { playback.play() }
        await { playback.state.positionMs > 0 }

        val requestsBeforeSeek = server.requestsSnapshot().size
        val target = playback.state.durationMs!! * 3 / 4
        onMain { playback.seekTo(target) }
        await { playback.state.positionMs > target + 200 }
        await {
            server.requestsSnapshot().drop(requestsBeforeSeek).any { request -> request.rangeStart != null && request.rangeStart > 0 }
        }
    }

    @Test
    fun branch_coordinators_start_next_complete_and_honor_local_controls_with_decodable_audio() {
        val playback = createEngine()
        var authoritative = acceptanceRoom(currentId = null, queueIds = listOf("one", "two"))
        val commandCallbacks = ArrayDeque<(QueueAdvanceCommandResult) -> Unit>()
        val fetcher = TestRoomFetcher { _, callback ->
            callback(RoomFetchResult.Success(authoritative))
            Cancelable { }
        }
        val advancement = testQueueCoordinator(
        queueScope,
            "ABCD",
            "host-secret",
            testQueueCommand { _, _, callback -> commandCallbacks.addLast(callback) },
            fetcher,
            // Keep this media-decoding scenario's original inline queue fixture semantics.
            mutationContext = QueueMutationContext(Dispatchers.Unconfined) { true },
        )
        val coordinatorRef = AtomicReference<AuthoritativePlaybackCoordinator>()
        onMain {
            coordinatorRef.set(
                AuthoritativePlaybackCoordinator(
                    roomCode = "ABCD",
                    streamUrl = server.url("tone.webm"),
                    playbackEngine = playback,
                    reconciler = fetcher::fetchRoom,
                    parentScope = queueScope,
                    mutationContext = QueueMutationContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        Looper.myLooper() === Looper.getMainLooper()
                    },
                    advanceAfterEnded = advancement::onPlaybackEnded,
                ),
            )
        }
        val coordinator = coordinatorRef.get()
        try {
            advancement.onAuthoritativeRoom(authoritative)
            assertTrue(advancement.requestExplicitAdvance())
            authoritative = acceptanceRoom(currentId = "one", queueIds = listOf("two"))
            commandCallbacks.removeFirst()(QueueAdvanceCommandResult.Success)
            coordinator.onSynchronization(freshAcceptance(authoritative))

            await { playback.state.isPlaying && playback.state.positionMs > 0 }
            val mediaBeforePause = playback.state.positionMs
            coordinator.pause()
            await { coordinator.state.status == LocalPlaybackStatus.PAUSED && !playback.state.isPlaying }
            coordinator.resume()
            await { playback.state.isPlaying && playback.state.positionMs > mediaBeforePause }

            coordinator.pause()
            await { coordinator.state.status == LocalPlaybackStatus.PAUSED && !playback.state.isPlaying }
            val mediaBeforeSeek = playback.state.positionMs
            val seekTarget = mediaBeforeSeek + 1_000
            coordinator.seekBy(1_000)
            await { !playback.state.isPlaying && playback.state.positionMs >= seekTarget - 100 }
            assertTrue("seek must advance paused media time", playback.state.positionMs > mediaBeforeSeek)
            coordinator.resume()
            await { playback.state.isPlaying }

            assertTrue(advancement.requestExplicitAdvance())
            authoritative = acceptanceRoom(currentId = "two", queueIds = emptyList())
            commandCallbacks.removeFirst()(QueueAdvanceCommandResult.Success)
            coordinator.onSynchronization(freshAcceptance(authoritative))
            val secondWallStart = SystemClock.elapsedRealtime()
            await { playback.state.mediaId == "two" && playback.state.isPlaying }
            await(timeoutMs = 15_000) { coordinator.state.status == LocalPlaybackStatus.COMPLETED }
            val secondWallElapsed = SystemClock.elapsedRealtime() - secondWallStart

            assertTrue("completion must consume real wall time", secondWallElapsed >= 1_000)
            assertTrue("completion must report decoded media time", playback.state.positionMs >= 3_000)
            assertTrue(server.requestsSnapshot().count { it.path == "tone.webm" } >= 2)
            assertTrue(commandCallbacks.isEmpty())
        } finally {
            coordinator.close()
            advancement.close()
        }
    }

    @Test
    fun exposes_structured_http_decode_and_deterministic_range_failures_then_recovers() {
        val playback = createEngine()
        prepare(playback, "http", server.url("missing"))
        awaitError(playback, PlaybackErrorKind.HTTP)
        assertEquals(404, playback.state.error?.httpResponseCode)

        prepare(playback, "http", server.url("bad-gateway"))
        awaitError(playback, PlaybackErrorKind.HTTP)
        assertEquals(502, playback.state.error?.httpResponseCode)

        prepare(playback, "decode", server.url("corrupt.webm"))
        awaitError(playback, PlaybackErrorKind.DECODE)

        prepare(playback, "range", server.url("range-failure.m4a"))
        awaitStatus(playback, PlaybackStatus.READY)
        val requestsBeforeSeek = server.requestsSnapshot().size
        val rejectedBeforeSeek = server.rejectedRanges.get()
        server.rejectNonZeroRanges.set(true)
        onMain { playback.seekTo(playback.state.durationMs!! * 3 / 4) }
        awaitError(playback, PlaybackErrorKind.RANGE)
        assertTrue(server.rejectedRanges.get() > rejectedBeforeSeek)
        assertTrue(
            server.requestsSnapshot().drop(requestsBeforeSeek)
                .any { request -> request.rangeStart != null && request.rangeStart > 0 },
        )

        prepare(playback, "recovery", server.url("tone.webm"))
        awaitStatus(playback, PlaybackStatus.READY)
    }

    @Test
    fun interrupted_network_is_an_error_and_same_track_new_url_recovers() {
        val playback = createEngine()
        prepare(playback, "interrupted", server.url("interrupted.m4a"))
        awaitError(playback, PlaybackErrorKind.NETWORK)
        prepare(playback, "interrupted", server.url("tone.webm"))
        awaitStatus(playback, PlaybackStatus.READY)
    }

    @Test
    fun media3_managed_audio_focus_suppresses_playback_for_competing_transient_focus() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val playback = createEngine()
            prepare(playback, "focus", server.url("tone.m4a"))
            awaitStatus(playback, PlaybackStatus.READY)
            onMain { playback.play() }
            await { playback.state.isPlaying }
            val playingPosition = playback.state.positionMs
            await { playback.state.positionMs >= playingPosition + AUDIO_FOCUS_SETTLE_PLAYBACK_MS }

            val audioManager = fixtureContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val listener = AudioManager.OnAudioFocusChangeListener { }
            val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener(listener)
                    .build()
            } else {
                null
            }

            val result = if (focusRequest != null) {
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                )
            }
            try {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, result)
                await { !playback.state.isPlaying }
            } finally {
                if (focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(listener)
                }
            }
        }
    }

    private fun createEngine(): PlaybackEngine {
        val ref = AtomicReference<PlaybackEngine>()
        onMain { ref.set(PlaybackEngines.create(targetContext)) }
        return ref.get().also { engine = it }
    }

    private fun acceptanceRoom(currentId: String?, queueIds: List<String>) = RoomState(
        code = "ABCD",
        current = currentId?.let { CurrentTrack(it, 0, "playing", it, "Artist") },
        queue = queueIds.map { QueuedTrack(it, "https://example/$it", it, "Artist", 4, "fixture") },
    )

    private fun freshAcceptance(room: RoomState) = RoomSyncState.Active(
        roomCode = room.code,
        room = room,
        freshness = Freshness.FRESH,
        connection = LiveConnection.CONNECTED,
    )

    private fun prepare(playback: PlaybackEngine, id: String, url: String) =
        onMain { playback.prepare(PlaybackMedia(id, url)) }

    private fun awaitStatus(playback: PlaybackEngine, status: PlaybackStatus, timeoutMs: Long = 10_000) =
        await(timeoutMs) { playback.state.status == status }

    private fun awaitError(playback: PlaybackEngine, kind: PlaybackErrorKind) =
        await { playback.state.status == PlaybackStatus.ERROR && playback.state.error?.kind == kind }

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            server.assertHealthy()
            if (condition()) return
            SystemClock.sleep(25)
        }
        server.assertHealthy()
        assertTrue("condition not met; state=${engine?.state}", condition())
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private companion object {
        const val AUDIO_FOCUS_SETTLE_PLAYBACK_MS = 500L
    }
}

private data class AssetRequest(val path: String, val rangeStart: Int?)

private class RangeAssetServer(private val fixtureContext: Context) : AutoCloseable {
    private val socket = ServerSocket(0).apply { soTimeout = ACCEPT_TIMEOUT_MS }
    private val registrationLock = Any()
    private val workers = ConcurrentLinkedQueue<Thread>()
    private val clients = ConcurrentLinkedQueue<Socket>()
    private val failure = AtomicReference<Throwable?>()
    private val requests = Collections.synchronizedList(mutableListOf<AssetRequest>())
    @Volatile private var running = true
    val rejectNonZeroRanges = AtomicBoolean(false)
    val rejectedRanges = AtomicInteger(0)

    fun start() {
        worker("range-asset-accept") {
            while (running) {
                try {
                    val client = socket.accept()
                    synchronized(registrationLock) {
                        if (!running) {
                            client.close()
                        } else {
                            clients += client
                            worker("range-asset-client") {
                                try {
                                    handle(client)
                                } finally {
                                    clients -= client
                                }
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Wake periodically so close cannot strand the accept thread.
                } catch (error: SocketException) {
                    if (running) throw error
                }
            }
        }
    }

    fun url(path: String) = "http://127.0.0.1:${socket.localPort}/$path"

    fun requestsSnapshot(): List<AssetRequest> {
        assertHealthy()
        return synchronized(requests) { requests.toList() }
    }

    fun assertHealthy() {
        failure.get()?.let { error -> throw AssertionError("RangeAssetServer worker failed", error) }
    }

    private fun worker(name: String, block: () -> Unit) {
        Thread({
            try {
                block()
            } catch (error: Throwable) {
                if (!isExpectedDisconnect(error)) failure.compareAndSet(null, error)
            }
        }, name).also { thread -> workers += thread; thread.start() }
    }

    private fun handle(client: Socket) = client.use { connection ->
        connection.soTimeout = READ_TIMEOUT_MS
        val requestText = readHeaders(BufferedInputStream(connection.getInputStream()))
        val lines = requestText.split("\r\n")
        val requestLine = lines.firstOrNull() ?: throw EOFException("empty HTTP request")
        val match = REQUEST_PATTERN.matchEntire(requestLine)
            ?: throw IllegalArgumentException("invalid request line: $requestLine")
        val rawPath = match.groupValues[1]
        require(!rawPath.contains("?") && !rawPath.contains("..")) { "invalid asset path: $rawPath" }
        val path = rawPath.removePrefix("/")
        val rangeValue = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val parsedRange = rangeValue?.let { value ->
            RANGE_PATTERN.matchEntire(value)
                ?: return@use respond(connection, 416, "Range Not Satisfiable", ByteArray(0), path)
        }
        val rangeStart = parsedRange?.groupValues?.get(1)?.toLongOrNull()
        require(rangeStart == null || rangeStart <= Int.MAX_VALUE) { "range start is too large" }
        requests += AssetRequest(path, rangeStart?.toInt())

        when (path) {
            "missing" -> respond(connection, 404, "Not Found", ByteArray(0), path)
            "bad-gateway" -> respond(connection, 502, "Bad Gateway", ByteArray(0), path)
            else -> serveAsset(connection, path, parsedRange)
        }
    }

    private fun serveAsset(connection: Socket, path: String, range: MatchResult?) {
        val asset = if (path == "range-failure.m4a" || path == "interrupted.m4a") "tone.m4a" else path
        val bytes = fixtureContext.assets.open(asset).use { it.readBytes() }
        val start = range?.groupValues?.get(1)?.toInt() ?: 0
        val requestedEnd = range?.groupValues?.get(2)?.takeIf(String::isNotEmpty)?.toIntOrNull()
        if (start >= bytes.size || requestedEnd != null && requestedEnd < start) {
            respond(connection, 416, "Range Not Satisfiable", ByteArray(0), path, "Content-Range: bytes */${bytes.size}\r\n")
            return
        }
        if (path == "range-failure.m4a" && start > 0 && rejectNonZeroRanges.get()) {
            rejectedRanges.incrementAndGet()
            respond(connection, 416, "Range Not Satisfiable", ByteArray(0), path, "Content-Range: bytes */${bytes.size}\r\n")
            return
        }
        if (path == "interrupted.m4a") {
            respondInterrupted(connection, bytes, path)
            return
        }
        val end = minOf(requestedEnd ?: bytes.lastIndex, bytes.lastIndex)
        val body = bytes.copyOfRange(start, end + 1)
        val extra = if (range != null) "Content-Range: bytes $start-$end/${bytes.size}\r\n" else ""
        respond(
            connection,
            if (range == null) 200 else 206,
            if (range == null) "OK" else "Partial Content",
            body,
            path,
            extra,
            slow = path.endsWith(".m4a"),
        )
    }

    private fun readHeaders(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (bytes.size < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw EOFException("request ended before headers")
            val byte = value.toByte()
            bytes += byte
            matched = if (byte == terminator[matched]) matched + 1 else if (byte == terminator[0]) 1 else 0
            if (matched == terminator.size) return bytes.toByteArray().toString(Charsets.US_ASCII)
        }
        throw IllegalArgumentException("HTTP headers exceed $MAX_HEADER_BYTES bytes")
    }

    private fun respond(
        socket: Socket,
        code: Int,
        reason: String,
        body: ByteArray,
        path: String,
        extra: String = "",
        slow: Boolean = false,
    ) {
        BufferedOutputStream(socket.getOutputStream()).use { output ->
            output.write(
                ("HTTP/1.1 $code $reason\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Content-Type: ${mimeType(path)}\r\n" +
                    "Connection: close\r\n" + extra + "\r\n").toByteArray(Charsets.US_ASCII),
            )
            if (slow) {
                var offset = 0
                while (offset < body.size) {
                    val count = minOf(CHUNK_SIZE, body.size - offset)
                    output.write(body, offset, count)
                    output.flush()
                    offset += count
                    SystemClock.sleep(CHUNK_DELAY_MS)
                }
            } else {
                output.write(body)
            }
        }
    }

    private fun respondInterrupted(socket: Socket, body: ByteArray, path: String) {
        BufferedOutputStream(socket.getOutputStream()).use { output ->
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Content-Type: ${mimeType(path)}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            output.write(body, 0, minOf(INTERRUPTED_BYTES, body.size))
            output.flush()
        }
    }

    private fun mimeType(path: String) = when {
        path.endsWith(".webm") -> "audio/webm"
        path.endsWith(".m4a") -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private fun isExpectedDisconnect(error: Throwable) =
        error is SocketException && (error.message?.contains("Broken pipe", ignoreCase = true) == true ||
            error.message?.contains("Connection reset", ignoreCase = true) == true ||
            error.message?.contains("Socket closed", ignoreCase = true) == true)

    override fun close() {
        val clientsToClose = synchronized(registrationLock) {
            running = false
            socket.close()
            clients.toList()
        }
        clientsToClose.forEach { client ->
            try {
                client.close()
            } catch (_: IOException) {
                // A peer may have closed between the queue snapshot and this call.
            }
        }
        workers.forEach { worker -> worker.join(WORKER_JOIN_MS) }
        workers.firstOrNull { it.isAlive }?.let { worker ->
            failure.compareAndSet(null, AssertionError("worker ${worker.name} did not stop"))
        }
    }

    private companion object {
        val REQUEST_PATTERN = Regex("GET (/[^ ]*) HTTP/1\\.[01]")
        val RANGE_PATTERN = Regex("bytes=(\\d+)-(\\d*)")
        const val ACCEPT_TIMEOUT_MS = 250
        const val READ_TIMEOUT_MS = 2_000
        const val MAX_HEADER_BYTES = 16_384
        const val CHUNK_SIZE = 2_048
        const val CHUNK_DELAY_MS = 20L
        const val INTERRUPTED_BYTES = 8_192
        const val WORKER_JOIN_MS = 3_000L
    }
}
