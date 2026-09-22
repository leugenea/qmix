package com.qmix.tv

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class OkHttpRoomEventStreamFactory(
    client: OkHttpClient,
    backendUrl: String,
    private val connect: ((Request, EventSourceListener) -> EventSource)? = null,
) : RoomEventStreamFactory {
    private val backend = backendUrl.trimEnd('/').toHttpUrl()
    private val client = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun observe(roomCode: String): Flow<RoomEventStreamEvent> = callbackFlow {
        val request = Request.Builder()
            .url(
                backend.newBuilder()
                    .addPathSegment("rooms")
                    .addPathSegment(roomCode)
                    .addPathSegment("events")
                    .build(),
            )
            .header("Accept", "text/event-stream")
            .build()
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(RoomEventStreamEvent.Opened)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                trySend(RoomEventStreamEvent.Event(type))
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(RoomEventStreamEvent.Closed)
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(RoomEventStreamEvent.Failure(response?.code))
                close()
            }
        }
        val eventSource = connect?.invoke(request, listener)
            ?: EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }
}
