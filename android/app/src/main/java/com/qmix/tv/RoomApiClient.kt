package com.qmix.tv

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

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
) : RoomStateFetcher {
    private val httpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val backend = backendUrl.trimEnd('/').toHttpUrl()

    fun createRoom(): RoomCredentials {
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

    fun getRoom(code: String): RoomState {
        val request = roomRequest(code)
        return execute(request, 200, ::decodeRoom)
    }

    override fun fetch(roomCode: String, callback: (RoomFetchResult) -> Unit): Cancelable {
        val call = httpClient.newCall(roomRequest(roomCode))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(RoomFetchResult.Failure)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        when (it.code) {
                            200 -> RoomFetchResult.Success(decodeRoom(it.body.string()))
                            404 -> RoomFetchResult.Missing
                            else -> RoomFetchResult.Failure
                        }
                    }
                } catch (_: IOException) {
                    RoomFetchResult.Failure
                } catch (_: RoomApiException) {
                    RoomFetchResult.Failure
                }
                callback(result)
            }
        })
        return Cancelable(call::cancel)
    }

    private fun roomRequest(code: String): Request = Request.Builder().url(
        backend.newBuilder().addPathSegment("rooms").addPathSegment(code).build(),
    ).get().build()

    private fun decodeRoom(body: String): RoomState {
        val json = parseObject(body)
        if (!json.has("current")) {
            throw RoomApiException("The server returned an invalid response.")
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
            else -> throw RoomApiException("The server returned an invalid response.")
        }
        val queueJson = json.optJSONArray("queue")
            ?: throw RoomApiException("The server returned an invalid response.")
        val queue = (0 until queueJson.length()).map { index ->
            val track = queueJson.optJSONObject(index)
                ?: throw RoomApiException("The server returned an invalid response.")
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

    fun skip(code: String, hostToken: String): CurrentTrack {
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
                ?: throw RoomApiException("The server returned an invalid response.")
            CurrentTrack(
                trackId = current.requiredString("track_id"),
                positionSeconds = current.requiredInt("pos_sec"),
                state = current.requiredString("state"),
                title = current.requiredString("title"),
                artist = current.requiredString("artist", allowBlank = true),
            )
        }
    }

    private fun <T> execute(request: Request, expectedStatus: Int, decode: (String) -> T): T = try {
        httpClient.newCall(request).execute().use { response ->
            if (response.code != expectedStatus) throw RoomApiException.forStatus(response.code)
            decode(response.body.string())
        }
    } catch (_: SocketTimeoutException) {
        throw RoomApiException("The server timed out. Try again.", QMixLogCause.NETWORK)
    } catch (_: IOException) {
        throw RoomApiException("Could not reach the server.", QMixLogCause.NETWORK)
    }

    private fun parseObject(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: JSONException) {
        throw RoomApiException("The server returned an invalid response.")
    }

    private fun JSONObject.requiredString(name: String, allowBlank: Boolean = false): String {
        val value = opt(name) as? String
            ?: throw RoomApiException("The server returned an invalid response.")
        if (!allowBlank && value.isBlank()) {
            throw RoomApiException("The server returned an invalid response.")
        }
        return value
    }

    private fun JSONObject.requiredInt(name: String): Int {
        val value = when (val raw = opt(name)) {
            is Int -> raw
            is Long -> raw.toInt().takeIf { it.toLong() == raw }
            else -> null
        }
        return value ?: throw RoomApiException("The server returned an invalid response.")
    }
}

class RoomApiException(
    message: String,
    internal val logCause: QMixLogCause = QMixLogCause.UNKNOWN,
) : Exception(message) {
    companion object {
        fun forStatus(status: Int): RoomApiException = when (status) {
            403 -> RoomApiException("Host access was denied.", QMixLogCause.HTTP_STATUS)
            404 -> RoomApiException("The room was not found.", QMixLogCause.HTTP_STATUS)
            in 500..599 -> RoomApiException("The server is temporarily unavailable.", QMixLogCause.HTTP_STATUS)
            else -> RoomApiException("The server rejected the request.", QMixLogCause.HTTP_STATUS)
        }
    }
}
