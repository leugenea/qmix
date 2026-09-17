package com.qmix.tv

import android.app.Application
import okhttp3.OkHttpClient
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class QMixApplication : Application() {
    private val logger: QMixLogger
        get() = QMixLogging.process

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
            logger = logger.component(QMixLogComponent.APP_HOST_SESSION),
            roomApiLogger = logger.component(QMixLogComponent.ROOM_API_CREATION),
            roomRepositoryFactory = { backendUrl ->
                SequentialRoomRepository(
                    fetcher = RoomApiClient(
                        client,
                        backendUrl,
                        logger.component(QMixLogComponent.ROOM_API_CREATION),
                    ),
                    eventStreams = OkHttpRoomEventStreamFactory(client, backendUrl),
                    scheduler = ExecutorRoomSyncScheduler(syncExecutor),
                    dispatcher = syncExecutor,
                    logger = logger.component(QMixLogComponent.ROOM_SYNC_SSE_RECONNECT),
                )
            },
            queueCoordinatorFactory = { backendUrl, credentials, observer ->
                val api = RoomApiClient(
                    client,
                    backendUrl,
                    logger.component(QMixLogComponent.ROOM_API_CREATION),
                )
                QueueAdvancementCoordinator(
                    roomCode = credentials.code,
                    hostToken = credentials.hostToken,
                    command = AsyncRoomAdvanceCommand(api) { command ->
                        Thread(command, "qmix-room-command").apply { isDaemon = true }.start()
                    },
                    reconciler = api,
                    observer = observer,
                )
            },
        )
    }
}
