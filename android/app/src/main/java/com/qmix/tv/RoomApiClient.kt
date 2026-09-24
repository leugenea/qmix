package com.qmix.tv

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

data class CurrentTrack(
    val trackId: String,
    val positionSeconds: Int,
    val state: String,
    val title: String,
    val artist: String,
)

data class QueuedTrack(
    val id: String,
    val url: String,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val resolvedBy: String,
)

data class RoomState(
    val code: String,
    val current: CurrentTrack?,
    val queue: List<QueuedTrack>,
)

enum class PlayerReportState(val wireValue: String) {
    PLAYING("playing"),
    PAUSED("paused"),
    ERROR("error"),
    ENDED("ended"),
}

data class PlayerReport(
    val trackId: String,
    val state: PlayerReportState,
    val positionSeconds: Int,
)

enum class PlayerReportResult { ACCEPTED, FORBIDDEN, MISSING, CONFLICT, FAILED }

/** Credentials returned once by POST /rooms. Never include [hostToken] in presentation objects. */
data class RoomCredentials(
    val code: String,
    val hostToken: String,
    val relativeGuestUrl: String,
) {
    override fun toString(): String =
        "RoomCredentials(code=$code, hostToken=<redacted>, relativeGuestUrl=<server-provided>)"
}

class RoomApiClient(
    httpClient: OkHttpClient,
    backendUrl: String,
    private val logger: QMixComponentLogger = QMixComponentLogger.noOp(QMixLogComponent.ROOM_API_CREATION),
) {
    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val backend = backendUrl.trimEnd('/').toHttpUrl()


    suspend fun createRoom(): RoomCredentials {
        val request = Request.Builder()
            .url(backend.newBuilder().addPathSegment("rooms").build())
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return try {
            execute(request, 201) { body ->
                val json = parseObject(body)
                RoomCredentials(
                    code = json.requiredString("code"),
                    hostToken = json.requiredString("host_token"),
                    relativeGuestUrl = json.requiredString("url"),
                )
            }
        } catch (failure: RoomApiException) {
            logger.error(QMixLogOperation.CREATE_ROOM, failure.logCause)
            throw failure
        }
    }

    suspend fun getRoom(code: String): RoomState {
        val request = roomRequest(code)
        return execute(request, 200, ::decodeRoom)
    }

    suspend fun fetchRoom(roomCode: String): RoomFetchResult = try {
        RoomFetchResult.Success(getRoom(roomCode))
    } catch (failure: RoomApiException) {
        if (failure.userMessage == UserMessage.ROOM_NOT_FOUND) RoomFetchResult.Missing else RoomFetchResult.Failure
    }

    suspend fun reportPlayer(
        roomCode: String,
        hostToken: String,
        report: PlayerReport,
    ): PlayerReportResult = try {
        val body = JSONObject()
            .put("track_id", report.trackId)
            .put("state", report.state.wireValue)
            .put("pos_sec", report.positionSeconds)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(
                backend.newBuilder()
                    .addPathSegment("rooms")
                    .addPathSegment(roomCode)
                    .addPathSegment("player")
                    .build(),
            )
            .header("X-Host-Token", hostToken)
            .patch(body)
            .build()
        httpClient.newCall(request).awaitDecoded { response ->
            when (response.code) {
                200 -> PlayerReportResult.ACCEPTED
                403 -> PlayerReportResult.FORBIDDEN
                404 -> PlayerReportResult.MISSING
                409 -> PlayerReportResult.CONFLICT
                else -> PlayerReportResult.FAILED
            }
        }
    } catch (_: IOException) {
        PlayerReportResult.FAILED
    } catch (_: IllegalArgumentException) {
        // Invalid server-issued header data must not escape a coroutine with credential-bearing diagnostics.
        PlayerReportResult.FAILED
    }

    private fun roomRequest(code: String): Request = Request.Builder().url(
        backend.newBuilder().addPathSegment("rooms").addPathSegment(code).build(),
    ).get().build()

    private fun decodeRoom(body: String): RoomState {
        val json = parseObject(body)
        if (!json.has("current")) {
            throw RoomApiException(UserMessage.INVALID_RESPONSE)
        }
        val current = when (val value = json.opt("current")) {
            JSONObject.NULL -> null
            is JSONObject -> CurrentTrack(
                trackId = value.requiredString("track_id"),
                positionSeconds = value.requiredInt("pos_sec"),
                state = value.requiredString("state"),
                title = value.requiredString("title"),
                artist = value.requiredString("artist", allowBlank = true),
            )
            else -> throw RoomApiException(UserMessage.INVALID_RESPONSE)
        }
        val queueJson = json.optJSONArray("queue")
            ?: throw RoomApiException(UserMessage.INVALID_RESPONSE)
        val queue = (0 until queueJson.length()).map { index ->
            val track = queueJson.optJSONObject(index)
                ?: throw RoomApiException(UserMessage.INVALID_RESPONSE)
            QueuedTrack(
                id = track.requiredString("id"),
                url = track.requiredString("url"),
                title = track.requiredString("title"),
                artist = track.requiredString("artist", allowBlank = true),
                durationSeconds = track.requiredInt("duration_sec"),
                resolvedBy = track.requiredString("resolved_by", allowBlank = true),
            )
        }
        return RoomState(json.requiredString("code"), current, queue)
    }

    suspend fun skip(code: String, hostToken: String): CurrentTrack {
        val url = backend.newBuilder()
            .addPathSegment("rooms")
            .addPathSegment(code)
            .addPathSegment("skip")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("X-Host-Token", hostToken)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return execute(request, 200) { body ->
            val current = parseObject(body).optJSONObject("current")
                ?: throw RoomApiException(UserMessage.INVALID_RESPONSE)
            CurrentTrack(
                trackId = current.requiredString("track_id"),
                positionSeconds = current.requiredInt("pos_sec"),
                state = current.requiredString("state"),
                title = current.requiredString("title"),
                artist = current.requiredString("artist", allowBlank = true),
            )
        }
    }

    private suspend fun <T> execute(request: Request, expectedStatus: Int, decode: (String) -> T): T = try {
        httpClient.newCall(request).awaitDecoded { response ->
            if (response.code != expectedStatus) throw RoomApiException.forStatus(response.code)
            decode(response.body.string())
        }
    } catch (_: SocketTimeoutException) {
        throw RoomApiException(UserMessage.SERVER_TIMEOUT, QMixLogCause.NETWORK)
    } catch (_: IOException) {
        throw RoomApiException(UserMessage.SERVER_UNREACHABLE, QMixLogCause.NETWORK)
    }

    private fun parseObject(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: JSONException) {
        throw RoomApiException(UserMessage.INVALID_RESPONSE)
    }

    private fun JSONObject.requiredString(name: String, allowBlank: Boolean = false): String {
        val value = opt(name) as? String
            ?: throw RoomApiException(UserMessage.INVALID_RESPONSE)
        if (!allowBlank && value.isBlank()) {
            throw RoomApiException(UserMessage.INVALID_RESPONSE)
        }
        return value
    }

    private fun JSONObject.requiredInt(name: String): Int {
        val value = when (val raw = opt(name)) {
            is Int -> raw
            is Long -> raw.toInt().takeIf { it.toLong() == raw }
            else -> null
        }
        return value ?: throw RoomApiException(UserMessage.INVALID_RESPONSE)
    }
}

class RoomApiException(
    val userMessage: UserMessage,
    internal val logCause: QMixLogCause = QMixLogCause.UNKNOWN,
) : Exception(userMessage.name) {
    companion object {
        fun forStatus(status: Int): RoomApiException = when (status) {
            403 -> RoomApiException(UserMessage.HOST_ACCESS_DENIED, QMixLogCause.HTTP_STATUS)
            404 -> RoomApiException(UserMessage.ROOM_NOT_FOUND, QMixLogCause.HTTP_STATUS)
            in 500..599 -> RoomApiException(UserMessage.SERVER_UNAVAILABLE, QMixLogCause.HTTP_STATUS)
            else -> RoomApiException(UserMessage.REQUEST_REJECTED, QMixLogCause.HTTP_STATUS)
        }
    }
}

/** qmix#178: decode under response ownership, never transfer an open response to a continuation. */
internal suspend fun <T> Call.awaitDecoded(decode: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    if (!continuation.isActive) return@suspendCancellableCoroutine
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use {
                    continuation.context.ensureActive()
                    decode(it)
                }
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
                return
            }
            continuation.resume(result)
        }
    })
}
