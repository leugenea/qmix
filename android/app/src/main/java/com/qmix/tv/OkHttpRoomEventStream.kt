package com.qmix.tv

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

class OkHttpRoomEventStreamFactory(
    client: OkHttpClient,
    backendUrl: String,
) : RoomEventStreamFactory {
    private val backend = backendUrl.trimEnd('/').toHttpUrl()
    private val client = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun connect(roomCode: String, listener: RoomEventListener): Cancelable {
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
        val eventSource = EventSources.createFactory(client).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    listener.onOpen()
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    listener.onEvent(type)
                }

                override fun onClosed(eventSource: EventSource) {
                    listener.onClosed()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    listener.onFailure(response?.code)
                }
            },
        )
        return Cancelable(eventSource::cancel)
    }
}
