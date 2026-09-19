package com.qmix.tv

import android.app.Application
import android.os.Handler
import android.os.Looper
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun currentPlaybackStreamUrl(backendUrl: String, roomCode: String): String =
    backendUrl.toHttpUrl().newBuilder()
        .addPathSegment("rooms")
        .addPathSegment(roomCode)
        .addPathSegment("current")
        .addPathSegment("stream")
        .build()
        .toString()

class QMixApplication : Application() {
    private data class ActivityHostSessionOverride(
        val token: Any,
        val provider: () -> HostSessionController,
    )

    private val activityHostSessionLock = Any()
    private var activityHostSessionOverride: ActivityHostSessionOverride? = null

    private val logger: QMixLogger
        get() = QMixLogging.process

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val playbackEngine by lazy { PlaybackEngines.create(this) }
    private val playbackDispatcher = Executor { command ->
        if (Looper.myLooper() === Looper.getMainLooper()) command.run() else mainHandler.post(command)
    }

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
            settingsPersistence = EndpointSettingsStore(this),
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
            playbackCoordinatorFactory = { backendUrl, credentials, observer, advanceAfterEnded ->
                val api = RoomApiClient(
                    client,
                    backendUrl,
                    logger.component(QMixLogComponent.ROOM_API_CREATION),
                )
                val streamUrl = currentPlaybackStreamUrl(backendUrl, credentials.code)
                createPlaybackCoordinatorOnMainThread {
                    AuthoritativePlaybackCoordinator(
                        roomCode = credentials.code,
                        streamUrl = streamUrl,
                        playbackEngine = playbackEngine,
                        reconciler = api,
                        dispatcher = playbackDispatcher,
                        advanceAfterEnded = advanceAfterEnded,
                        observer = observer,
                    )
                }
            },
        )
    }

    internal fun hostSessionForActivity(): HostSessionController {
        val provider = synchronized(activityHostSessionLock) {
            activityHostSessionOverride?.provider
        }
        return provider?.invoke() ?: hostSession
    }

    internal fun installActivityHostSessionProvider(
        provider: () -> HostSessionController,
    ): AutoCloseable {
        val token = Any()
        synchronized(activityHostSessionLock) {
            check(activityHostSessionOverride == null) { "An activity host-session provider is already installed" }
            activityHostSessionOverride = ActivityHostSessionOverride(token, provider)
        }
        return AutoCloseable {
            synchronized(activityHostSessionLock) {
                if (activityHostSessionOverride?.token === token) {
                    activityHostSessionOverride = null
                }
            }
        }
    }

    private fun <T> createPlaybackCoordinatorOnMainThread(factory: () -> T): T {
        if (Looper.myLooper() === Looper.getMainLooper()) return factory()
        val task = FutureTask(factory)
        check(mainHandler.post(task)) { "Main playback thread is unavailable" }
        return task.get()
    }
}
