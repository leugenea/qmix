package com.qmix.tv

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
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
) {
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
        return execute(request, 201) { body ->
            val json = parseObject(body)
            RoomCredentials(
                code = json.requiredString("code"),
                hostToken = json.requiredString("host_token"),
                relativeGuestUrl = json.requiredString("url"),
            )
        }
    }

    fun getRoom(code: String): RoomState {
        val request = Request.Builder().url(
            backend.newBuilder().addPathSegment("rooms").addPathSegment(code).build(),
        ).get().build()
        return execute(request, 200) { body ->
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
            RoomState(json.requiredString("code"), current, queue)
        }
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
        throw RoomApiException("The server timed out. Try again.")
    } catch (_: IOException) {
        throw RoomApiException("Could not reach the server.")
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

class RoomApiException(message: String) : Exception(message) {
    companion object {
        fun forStatus(status: Int): RoomApiException = when (status) {
            403 -> RoomApiException("Host access was denied.")
            404 -> RoomApiException("The room was not found.")
            in 500..599 -> RoomApiException("The server is temporarily unavailable.")
            else -> RoomApiException("The server rejected the request.")
        }
    }
}
