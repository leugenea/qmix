package com.qmix.tv

import okhttp3.OkHttpClient
import java.util.ArrayDeque
import java.util.concurrent.Executor

sealed interface HostingState {
    data class Setup(val backendUrl: String, val guestOrigin: String) : HostingState
    data class HttpWarning(val backendUrl: String, val guestOrigin: String) : HostingState
    data class Pending(val backendUrl: String, val guestOrigin: String) : HostingState
    data class Invitation(val invite: GuestInvite) : HostingState
    data class LiveRoom(
        val invite: GuestInvite,
        val synchronization: RoomSyncState,
        val commandPending: Boolean = false,
        val playback: LocalPlaybackState = LocalPlaybackState(),
        val invitationVisible: Boolean = false,
        val foregroundRecoveryPending: Boolean = false,
    ) : HostingState {
        val primaryAction: LiveRoomPrimaryAction
            get() = if ((synchronization as? RoomSyncState.Active)?.room?.current == null) {
                LiveRoomPrimaryAction.START
            } else {
                LiveRoomPrimaryAction.NEXT
            }

        val isPrimaryActionEnabled: Boolean
            get() {
                val active = synchronization as? RoomSyncState.Active ?: return false
                return !commandPending &&
                    !foregroundRecoveryPending &&
                    active.room?.queue?.isNotEmpty() == true &&
                    active.freshness == Freshness.FRESH &&
                    active.connection == LiveConnection.CONNECTED
            }
    }
    data class Error(val message: UserMessage, val backendUrl: String, val guestOrigin: String) : HostingState
}

enum class LiveRoomPrimaryAction { START, NEXT }

enum class LiveRoomBackResult { HANDLED, EXIT_ACTIVITY, IGNORED }

typealias QueueCoordinatorFactory = (
    backendUrl: String,
    credentials: RoomCredentials,
    observer: (QueueAdvancementState) -> Unit,
) -> QueueAdvancementCoordinator

typealias PlaybackCoordinatorFactory = (
    backendUrl: String,
    credentials: RoomCredentials,
    observer: (LocalPlaybackState) -> Unit,
    advanceAfterEnded: (String) -> Boolean,
) -> AuthoritativePlaybackCoordinator

interface LiveRoomHandler {
    fun onStartOrNext()
    fun onPlayPause() = Unit
    fun onPlay() = Unit
    fun onPause() = Unit
    fun onSeekBy(offsetMs: Long) = Unit
    fun onRetryCurrent() = Unit
    fun onInvite()
    fun onBack(): LiveRoomBackResult
}

class HostSessionController(
    private val httpClient: OkHttpClient,
    initialBackendUrl: String = "",
    initialGuestOrigin: String = initialBackendUrl,
    private val executor: Executor = Executor { command ->
        Thread(command, "qmix-room-request").apply { isDaemon = true }.start()
    },
    private val settingsPersistence: EndpointSettingsPersistence = InitialEndpointSettingsPersistence(
        EndpointSettings(initialBackendUrl, initialGuestOrigin),
    ),
    private val roomRepositoryFactory: ((String) -> RoomRepository)? = null,
    private val foregroundReconcilerFactory: ((String) -> RoomStateFetcher)? = null,
    private val queueCoordinatorFactory: QueueCoordinatorFactory? = null,
    private val playbackCoordinatorFactory: PlaybackCoordinatorFactory? = null,
    private val primaryActionHandler: () -> Unit = {},
    private val observerFailureHandler: (Throwable) -> Unit = {},
    private val logger: QMixComponentLogger = QMixComponentLogger.noOp(QMixLogComponent.APP_HOST_SESSION),
    private val roomApiLogger: QMixComponentLogger = QMixComponentLogger.noOp(QMixLogComponent.ROOM_API_CREATION),
) : LiveRoomHandler {
    private data class Notification(
        val state: HostingState,
        val recipients: List<(HostingState) -> Unit>,
    )

    private val initialSettings = settingsPersistence.load()
    private val foregroundTransitionLock = Any()
    private val observers = linkedSetOf<(HostingState) -> Unit>()
    private val notifications = ArrayDeque<Notification>()
    private var deliveringNotifications = false
    private var setupState = HostingState.Setup(initialSettings.backendUrl, initialSettings.guestOrigin)
    private var credentials: RoomCredentials? = null
    private var activeBackendUrl: String? = null
    private var roomSubscription: AutoCloseable? = null
    private var queueCoordinator: QueueAdvancementCoordinator? = null
    private var playbackCoordinator: AuthoritativePlaybackCoordinator? = null
    private var foreground = true
    private var foregroundRecoveryGeneration = 0L
    private var foregroundRecoveryRequest: Cancelable? = null
    private var httpAcknowledgementQuarantined = false
    private var createGeneration = 0L
    private var syncGeneration = 0L
    private var roomObservationGeneration = 0L

    @Volatile
    var state: HostingState = setupState
        private set

    val roomSyncState: RoomSyncState?
        get() = (state as? HostingState.LiveRoom)?.synchronization

    fun observe(observer: (HostingState) -> Unit): AutoCloseable {
        synchronized(this) {
            observers += observer
            notifications.addLast(Notification(state, listOf(observer)))
        }
        drainNotifications()
        return AutoCloseable { synchronized(this) { observers -= observer } }
    }

    fun updateSettings(backendUrl: String, guestOrigin: String) {
        val changed = synchronized(this) {
            if (state is HostingState.Setup || state is HostingState.Error) {
                setupState = HostingState.Setup(backendUrl, guestOrigin)
                publishLocked(setupState)
                true
            } else {
                false
            }
        }
        if (changed) drainNotifications()
    }

    fun createRoom(): Boolean {
        lateinit var settings: EndpointSettings
        var generation = 0L
        var executeNow = false
        var accepted = false
        synchronized(this) {
            val candidate = when (val current = state) {
                is HostingState.Setup -> current
                is HostingState.Error -> HostingState.Setup(current.backendUrl, current.guestOrigin)
                else -> return false
            }
            val validated = EndpointSettings.validate(candidate.backendUrl, candidate.guestOrigin)
            if (validated == null) {
                setupState = HostingState.Setup("", "")
                publishLocked(
                    HostingState.Error(
                        UserMessage.INVALID_ENDPOINT,
                        "",
                        "",
                    ),
                )
            } else {
                settings = validated
                setupState = HostingState.Setup(validated.backendUrl, validated.guestOrigin)
                if (
                    validated.usesHttp &&
                    (httpAcknowledgementQuarantined || !settingsPersistence.isHttpWarningAcknowledged())
                ) {
                    publishLocked(HostingState.HttpWarning(validated.backendUrl, validated.guestOrigin))
                } else {
                    generation = ++createGeneration
                    publishLocked(HostingState.Pending(validated.backendUrl, validated.guestOrigin))
                    executeNow = true
                }
                accepted = true
            }
        }
        drainNotifications()
        if (executeNow) executeCreate(settings, generation)
        return accepted
    }

    fun confirmHttpWarning(): Boolean {
        lateinit var settings: EndpointSettings
        var generation = 0L
        val confirmed = synchronized(this) {
            val warning = state as? HostingState.HttpWarning ?: return false
            settings = EndpointSettings(warning.backendUrl, warning.guestOrigin)
            httpAcknowledgementQuarantined = true
            try {
                settingsPersistence.acknowledgeHttpWarning()
            } catch (_: IllegalStateException) {
                publishLocked(
                    HostingState.Error(UserMessage.PERSISTENCE_ERROR, warning.backendUrl, warning.guestOrigin),
                )
                return@synchronized false
            }
            httpAcknowledgementQuarantined = false
            setupState = HostingState.Setup(settings.backendUrl, settings.guestOrigin)
            generation = ++createGeneration
            publishLocked(HostingState.Pending(settings.backendUrl, settings.guestOrigin))
            true
        }
        drainNotifications()
        if (!confirmed) return false
        executeCreate(settings, generation)
        return true
    }

    fun cancelHttpWarning() {
        val changed = synchronized(this) {
            val warning = state as? HostingState.HttpWarning ?: return
            setupState = HostingState.Setup(warning.backendUrl, warning.guestOrigin)
            publishLocked(setupState)
            true
        }
        if (changed) drainNotifications()
    }

    private fun executeCreate(settings: EndpointSettings, generation: Long) {
        executor.execute {
            try {
                val created = RoomApiClient(httpClient, settings.backendUrl, roomApiLogger).createRoom()
                val invite = GuestInvite.create(created, settings.guestOrigin)
                val changed = synchronized(this) {
                    if (generation != createGeneration) {
                        false
                    } else {
                        settingsPersistence.save(settings)
                        credentials = created
                        activeBackendUrl = settings.backendUrl
                        publishLocked(HostingState.Invitation(invite))
                        true
                    }
                }
                if (changed) drainNotifications()
            } catch (error: RoomApiException) {
                publishCreateError(generation, settings, error.userMessage)
            } catch (_: IllegalArgumentException) {
                publishCreateError(generation, settings, UserMessage.INVALID_ENDPOINT)
            } catch (_: IllegalStateException) {
                publishCreateError(generation, settings, UserMessage.PERSISTENCE_ERROR)
            }
        }
    }

    private fun publishCreateError(generation: Long, settings: EndpointSettings, message: UserMessage) {
        val changed = synchronized(this) {
            if (generation != createGeneration) {
                false
            } else {
                publishLocked(HostingState.Error(message, settings.backendUrl, settings.guestOrigin))
                true
            }
        }
        if (changed) drainNotifications()
    }

    fun enterRoom() {
        lateinit var invite: GuestInvite
        var repositoryFactory: ((String) -> RoomRepository)? = null
        var coordinatorFactory: QueueCoordinatorFactory? = null
        var localPlaybackFactory: PlaybackCoordinatorFactory? = null
        var backendUrl: String? = null
        var sessionCredentials: RoomCredentials? = null
        var previousSubscription: AutoCloseable? = null
        var previousCoordinator: QueueAdvancementCoordinator? = null
        var previousPlayback: AuthoritativePlaybackCoordinator? = null
        var generation = 0L
        synchronized(this) {
            val invitation = state as? HostingState.Invitation ?: return
            invite = invitation.invite
            publishLocked(
                HostingState.LiveRoom(
                    invite,
                    RoomSyncState.Active(
                        invite.code,
                        room = null,
                        freshness = Freshness.LOADING,
                        connection = LiveConnection.CONNECTING,
                    ),
                ),
            )
            repositoryFactory = roomRepositoryFactory
            coordinatorFactory = queueCoordinatorFactory
            localPlaybackFactory = playbackCoordinatorFactory
            backendUrl = activeBackendUrl
            sessionCredentials = credentials
            if (repositoryFactory != null && backendUrl != null) {
                previousSubscription = roomSubscription
                previousCoordinator = queueCoordinator
                previousPlayback = playbackCoordinator
                roomSubscription = null
                queueCoordinator = null
                playbackCoordinator = null
                generation = ++syncGeneration
            }
        }
        drainNotifications()
        previousSubscription?.close()
        previousCoordinator?.close()
        previousPlayback?.close()

        val activeRepositoryFactory = repositoryFactory ?: return
        val activeUrl = backendUrl ?: return
        val activeCredentials = sessionCredentials
        val createdCoordinator = if (coordinatorFactory != null && activeCredentials != null) {
            coordinatorFactory.invoke(activeUrl, activeCredentials) { advancement ->
                val changed = synchronized(this@HostSessionController) {
                    val current = state as? HostingState.LiveRoom
                    if (generation != syncGeneration || current?.invite?.code != invite.code ||
                        current.commandPending == advancement.pending
                    ) {
                        false
                    } else {
                        publishLocked(current.copy(commandPending = advancement.pending))
                        true
                    }
                }
                if (changed) drainNotifications()
            }
        } else {
            null
        }
        val closeCoordinatorImmediately = synchronized(this) {
            val current = state as? HostingState.LiveRoom
            if (generation == syncGeneration && current?.invite?.code == invite.code) {
                queueCoordinator = createdCoordinator
                false
            } else {
                true
            }
        }
        if (closeCoordinatorImmediately) {
            createdCoordinator?.close()
            return
        }

        val createdPlayback = if (localPlaybackFactory != null && activeCredentials != null) {
            localPlaybackFactory.invoke(
                activeUrl,
                activeCredentials,
                { playback ->
                    val changed = synchronized(this@HostSessionController) {
                        val current = state as? HostingState.LiveRoom
                        if (generation != syncGeneration || current?.invite?.code != invite.code ||
                            current.playback == playback
                        ) {
                            false
                        } else {
                            publishLocked(current.copy(playback = playback))
                            true
                        }
                    }
                    if (changed) drainNotifications()
                },
                { trackId ->
                    val active = synchronized(this@HostSessionController) {
                        val current = state as? HostingState.LiveRoom
                        if (generation == syncGeneration && current?.invite?.code == invite.code) {
                            queueCoordinator
                        } else {
                            null
                        }
                    }
                    active?.onPlaybackEnded(trackId) == true
                },
            )
        } else {
            null
        }
        val closePlaybackImmediately = synchronized(this) {
            val current = state as? HostingState.LiveRoom
            if (generation == syncGeneration && current?.invite?.code == invite.code) {
                playbackCoordinator = createdPlayback
                false
            } else {
                true
            }
        }
        if (closePlaybackImmediately) {
            createdPlayback?.close()
            createdCoordinator?.close()
            return
        }

        val observationGeneration = synchronized(this) { ++roomObservationGeneration }
        startRoomObservation(activeRepositoryFactory, activeUrl, invite, observationGeneration)
    }

    private fun startRoomObservation(
        repositoryFactory: (String) -> RoomRepository,
        backendUrl: String,
        invite: GuestInvite,
        generation: Long,
    ) {
        val subscription = repositoryFactory(backendUrl).observe(invite.code) { syncState ->
            var authoritativeRoom: RoomState? = null
            var activeCoordinator: QueueAdvancementCoordinator? = null
            var activePlayback: AuthoritativePlaybackCoordinator? = null
            var retryForegroundRecovery = false
            val changed = synchronized(this@HostSessionController) {
                val current = state as? HostingState.LiveRoom
                if (generation != roomObservationGeneration || current?.invite?.code != syncState.roomCode) {
                    false
                } else {
                    publishLocked(current.copy(synchronization = retainLastKnownRoom(current.synchronization, syncState)))
                    activePlayback = playbackCoordinator
                    val active = syncState as? RoomSyncState.Active
                    if (!current.foregroundRecoveryPending &&
                        active?.freshness == Freshness.FRESH && active.room != null
                    ) {
                        authoritativeRoom = active.room
                        activeCoordinator = queueCoordinator
                    } else if (current.foregroundRecoveryPending && foreground &&
                        active?.freshness == Freshness.FRESH && active.room != null &&
                        foregroundRecoveryRequest == null
                    ) {
                        retryForegroundRecovery = true
                    }
                    true
                }
            }
            if (changed) {
                activePlayback?.onSynchronization(syncState)
                activeCoordinator?.onAuthoritativeRoom(checkNotNull(authoritativeRoom))
                drainNotifications()
                if (retryForegroundRecovery) startForegroundRecovery()
            }
        }
        val closeImmediately = synchronized(this) {
            val current = state as? HostingState.LiveRoom
            if (generation == roomObservationGeneration && current?.invite?.code == invite.code) {
                roomSubscription = subscription
                false
            } else {
                true
            }
        }
        if (closeImmediately) subscription.close()
    }

    private fun retainLastKnownRoom(previous: RoomSyncState, update: RoomSyncState): RoomSyncState {
        if (update !is RoomSyncState.Active || update.freshness != Freshness.STALE || update.room != null) {
            return update
        }
        val lastKnown = (previous as? RoomSyncState.Active)?.room ?: return update
        return update.copy(room = lastKnown)
    }

    fun setCommandPending(pending: Boolean) {
        val changed = synchronized(this) {
            val current = state as? HostingState.LiveRoom ?: return
            if (current.commandPending == pending) {
                false
            } else {
                publishLocked(current.copy(commandPending = pending))
                true
            }
        }
        if (changed) drainNotifications()
    }

    fun onHostStopped(): Unit = synchronized(foregroundTransitionLock) {
        var queue: QueueAdvancementCoordinator? = null
        var playback: AuthoritativePlaybackCoordinator? = null
        var request: Cancelable? = null
        var subscription: AutoCloseable? = null
        var lifecycleToken = 0L
        val changed = synchronized(this) {
            val current = state as? HostingState.LiveRoom ?: return
            if (!foreground) return
            foreground = false
            foregroundRecoveryGeneration++
            lifecycleToken = foregroundRecoveryGeneration
            roomObservationGeneration++
            request = foregroundRecoveryRequest
            foregroundRecoveryRequest = null
            subscription = roomSubscription
            roomSubscription = null
            queue = queueCoordinator
            playback = playbackCoordinator
            publishLocked(current.copy(foregroundRecoveryPending = true, commandPending = false))
            true
        }
        request?.cancel()
        subscription?.close()
        queue?.onForegroundLost()
        playback?.onForegroundLost(lifecycleToken)
        if (changed) drainNotifications()
    }

    fun onHostStarted(): Unit = synchronized(foregroundTransitionLock) {
        val shouldRecover = synchronized(this) {
            if (foreground) return
            foreground = true
            state is HostingState.LiveRoom
        }
        if (shouldRecover) startForegroundRecovery()
    }

    private fun startForegroundRecovery() {
        lateinit var code: String
        lateinit var fetcher: RoomStateFetcher
        var token = 0L
        var staleObservation: AutoCloseable? = null
        synchronized(this) {
            val current = state as? HostingState.LiveRoom ?: return
            if (!foreground || !current.foregroundRecoveryPending || foregroundRecoveryRequest != null) return
            val backend = activeBackendUrl ?: return
            fetcher = foregroundReconcilerFactory?.invoke(backend) ?: return
            code = current.invite.code
            token = ++foregroundRecoveryGeneration
            roomObservationGeneration++
            staleObservation = roomSubscription
            roomSubscription = null
        }
        staleObservation?.close()
        val request = fetcher.fetch(code) { result -> completeForegroundRecovery(token, code, result) }
        val cancel = synchronized(this) {
            val current = state as? HostingState.LiveRoom
            if (!foreground || token != foregroundRecoveryGeneration ||
                current?.invite?.code != code || !current.foregroundRecoveryPending
            ) {
                true
            } else {
                foregroundRecoveryRequest = request
                false
            }
        }
        if (cancel) request.cancel()
    }

    private fun completeForegroundRecovery(token: Long, code: String, result: RoomFetchResult): Unit =
        synchronized(foregroundTransitionLock) {
            var queue: QueueAdvancementCoordinator? = null
            var playback: AuthoritativePlaybackCoordinator? = null
            var room: RoomState? = null
            var repositoryFactory: ((String) -> RoomRepository)? = null
            var backendUrl: String? = null
            var invite: GuestInvite? = null
            var observationGeneration = 0L
            var restartObservation = false
            var changed = false
            synchronized(this) {
                val current = state as? HostingState.LiveRoom
                if (!foreground || token != foregroundRecoveryGeneration || current?.invite?.code != code) return
                foregroundRecoveryRequest = null
                foregroundRecoveryGeneration++
                when (result) {
                    RoomFetchResult.Failure -> restartObservation = true
                    RoomFetchResult.Missing -> {
                        publishLocked(current.copy(synchronization = RoomSyncState.Missing(code)))
                        changed = true
                    }
                    is RoomFetchResult.Success -> {
                        val fresh = result.room
                        if (fresh.code != code) {
                            restartObservation = true
                        } else {
                            room = fresh
                            queue = queueCoordinator
                            playback = playbackCoordinator
                            val connection = (current.synchronization as? RoomSyncState.Active)?.connection
                                ?: LiveConnection.CONNECTING
                            publishLocked(
                                current.copy(
                                    synchronization = RoomSyncState.Active(
                                        code,
                                        fresh,
                                        Freshness.FRESH,
                                        connection,
                                    ),
                                    foregroundRecoveryPending = false,
                                ),
                            )
                            changed = true
                            restartObservation = true
                        }
                    }
                }
                if (restartObservation) {
                    repositoryFactory = roomRepositoryFactory
                    backendUrl = activeBackendUrl
                    invite = current.invite
                    observationGeneration = ++roomObservationGeneration
                }
            }
            room?.let { fresh ->
                queue?.onForegroundReconciled(fresh)
                playback?.onForegroundReconciled(token, fresh)
            }
            if (changed) drainNotifications()
            if (repositoryFactory != null && backendUrl != null && invite != null) {
                startRoomObservation(
                    checkNotNull(repositoryFactory),
                    checkNotNull(backendUrl),
                    checkNotNull(invite),
                    observationGeneration,
                )
            }
        }

    override fun onStartOrNext() {
        var coordinator: QueueAdvancementCoordinator? = null
        val enabled = synchronized(this) {
            val allowed = (state as? HostingState.LiveRoom)?.isPrimaryActionEnabled == true
            if (allowed) coordinator = queueCoordinator
            allowed
        }
        if (!enabled) return
        val activeCoordinator = coordinator
        if (activeCoordinator != null) {
            activeCoordinator.requestExplicitAdvance()
        } else {
            primaryActionHandler()
        }
    }

    fun onPlaybackEnded(trackId: String): Boolean {
        val coordinator = synchronized(this) { queueCoordinator }
        return coordinator?.onPlaybackEnded(trackId) == true
    }

    override fun onPlayPause() {
        synchronized(this) { playbackCoordinator }?.togglePlayPause()
    }

    override fun onSeekBy(offsetMs: Long) {
        synchronized(this) { playbackCoordinator }?.seekBy(offsetMs)
    }

    override fun onPlay() = resumePlayback()

    override fun onPause() = pausePlayback()

    fun pausePlayback() {
        synchronized(this) { playbackCoordinator }?.pause()
    }

    fun resumePlayback() {
        synchronized(this) { playbackCoordinator }?.resume()
    }

    override fun onRetryCurrent() = retryCurrent()

    fun retryCurrent() {
        synchronized(this) { playbackCoordinator }?.retryCurrent()
    }

    override fun onInvite() {
        val changed = synchronized(this) {
            val current = state as? HostingState.LiveRoom ?: return
            if (current.invitationVisible) {
                false
            } else {
                publishLocked(current.copy(invitationVisible = true))
                true
            }
        }
        if (changed) drainNotifications()
    }

    override fun onBack(): LiveRoomBackResult {
        val result = synchronized(this) {
            val current = state as? HostingState.LiveRoom ?: return LiveRoomBackResult.IGNORED
            if (current.invitationVisible) {
                publishLocked(current.copy(invitationVisible = false))
                LiveRoomBackResult.HANDLED
            } else {
                LiveRoomBackResult.EXIT_ACTIVITY
            }
        }
        if (result == LiveRoomBackResult.HANDLED) {
            drainNotifications()
        } else {
            endRoom()
        }
        return result
    }

    fun endRoom() {
        var coordinator: QueueAdvancementCoordinator? = null
        var localPlayback: AuthoritativePlaybackCoordinator? = null
        var recoveryRequest: Cancelable? = null
        val subscription = synchronized(this) {
            createGeneration++
            syncGeneration++
            roomObservationGeneration++
            foregroundRecoveryGeneration++
            recoveryRequest = foregroundRecoveryRequest
            foregroundRecoveryRequest = null
            val owned = roomSubscription
            roomSubscription = null
            coordinator = queueCoordinator
            queueCoordinator = null
            localPlayback = playbackCoordinator
            playbackCoordinator = null
            credentials = null
            activeBackendUrl = null
            foreground = true
            publishLocked(setupState)
            owned
        }
        try {
            subscription?.close()
            recoveryRequest?.cancel()
        } finally {
            try {
                coordinator?.close()
            } finally {
                try {
                    localPlayback?.close()
                } finally {
                    drainNotifications()
                }
            }
        }
    }

    private fun publishLocked(newState: HostingState) {
        state = newState
        notifications.addLast(Notification(newState, observers.toList()))
    }

    private fun drainNotifications() {
        synchronized(this) {
            if (deliveringNotifications) return
            deliveringNotifications = true
        }
        while (true) {
            val notification = synchronized(this) {
                if (notifications.isEmpty()) {
                    deliveringNotifications = false
                    return
                }
                notifications.removeFirst()
            }
            notification.recipients.forEach { observer ->
                try {
                    observer(notification.state)
                } catch (failure: Throwable) {
                    try {
                        logger.error(QMixLogOperation.OBSERVER_NOTIFICATION, QMixLogCause.CALLBACK_FAILURE)
                        observerFailureHandler(failure)
                    } catch (_: Throwable) {
                        // One observer must not block later state delivery or lifecycle cleanup.
                    }
                }
            }
        }
    }
}
