package com.qmix.tv

import android.app.Application
import okhttp3.OkHttpClient
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class QMixApplication : Application() {
    val hostSession: HostSessionController by lazy {
        val syncExecutor = ScheduledThreadPoolExecutor(1) { command ->
            Thread(command, "qmix-room-sync").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        val client = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        HostSessionController(
            httpClient = client,
            initialBackendUrl = BuildConfig.DEFAULT_BACKEND_URL,
            initialGuestOrigin = BuildConfig.DEFAULT_GUEST_ORIGIN,
            roomRepositoryFactory = { backendUrl ->
                SequentialRoomRepository(
                    fetcher = RoomApiClient(client, backendUrl),
                    eventStreams = OkHttpRoomEventStreamFactory(client, backendUrl),
                    scheduler = ExecutorRoomSyncScheduler(syncExecutor),
                    dispatcher = syncExecutor,
                )
            },
        )
    }
}
