package com.qmix.tv

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

sealed interface HostingState {
    data class Setup(val backendUrl: String, val guestOrigin: String) : HostingState
    data object Ending : HostingState
    data class HttpWarning(val backendUrl: String, val guestOrigin: String) : HostingState
    data class Pending(val backendUrl: String, val guestOrigin: String) : HostingState
    data class Invitation(
        val invite: GuestInvite,
        val roomReplacementNotice: Boolean = false,
        val foregroundRecoveryPending: Boolean = false,
    ) : HostingState
    data class LiveRoom(
        val invite: GuestInvite,
        val synchronization: RoomSyncState,
        val commandPending: Boolean = false,
        val playback: LocalPlaybackState = LocalPlaybackState(),
        val invitationVisible: Boolean = false,
        val foregroundRecoveryPending: Boolean = false,
        val replacementError: UserMessage? = null,
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
                return !commandPending && !foregroundRecoveryPending &&
                    active.room?.queue?.isNotEmpty() == true &&
                    active.freshness == Freshness.FRESH && active.connection == LiveConnection.CONNECTED
            }
    }
    data class Error(val message: UserMessage, val backendUrl: String, val guestOrigin: String) : HostingState
}

enum class LiveRoomPrimaryAction { START, NEXT }

typealias QueueCoordinatorFactory = (
    backendUrl: String,
    credentials: RoomCredentials,
    observer: (QueueAdvancementState) -> Unit,
    sessionScope: CoroutineScope,
) -> QueueAdvancementCoordinator

typealias PlaybackCoordinatorFactory = (
    backendUrl: String,
    credentials: RoomCredentials,
    observer: (LocalPlaybackState) -> Unit,
    advanceAfterEnded: (String) -> Unit,
    sessionScope: CoroutineScope,
) -> AuthoritativePlaybackCoordinator

fun interface RoomCloseCommand {
    suspend fun close(roomCode: String, hostToken: String)
}

interface LiveRoomHandler {
    fun onStartOrNext()
    fun onPlayPause() = Unit
    fun onPlay() = Unit
    fun onPause() = Unit
    fun onSeekBy(offsetMs: Long) = Unit
    fun onRetryCurrent() = Unit
    fun onInvite()
    fun onNewRoom() = Unit
    fun onBack(onExit: () -> Unit = {})
}

/** Resource handles and identities, not another host-state authority. */
private class HostSession(parent: Job?, val settings: EndpointSettings) {
    val job = SupervisorJob(parent)
    var worker: Job? = null
    var operation = Any()
    var credentials: RoomCredentials? = null
    var queue: QueueAdvancementCoordinator? = null
    var playback: AuthoritativePlaybackCoordinator? = null
    var observedTrack = false
    var unvalidatedAutomaticReplacement = false
}

private sealed interface HostEvent
private sealed interface SetupEvent : HostEvent {
    data class Settings(val backend: String, val guest: String) : SetupEvent
    data object Create : SetupEvent
    data object Confirm : SetupEvent
    data object CancelWarning : SetupEvent
}
private sealed interface RoomEvent : HostEvent {
    data object Enter : RoomEvent
    data object Start : RoomEvent
    data object Stop : RoomEvent
    data class End(val explicit: Boolean) : RoomEvent
    data class Back(val onExit: () -> Unit) : RoomEvent
    data object NewRoom : RoomEvent
    data object Invite : RoomEvent
    data class Pending(val value: Boolean) : RoomEvent
}
private enum class PlaybackCommand { TOGGLE, PLAY, PAUSE, RETRY }
private sealed interface ControlEvent : HostEvent {
    data object Advance : ControlEvent
    data class Ended(val owner: HostSession?, val trackId: String) : ControlEvent
    data class Playback(val command: PlaybackCommand) : ControlEvent
    data class Seek(val offset: Long) : ControlEvent
}
private sealed interface ResultEvent : HostEvent {
    data class Created(val owner: HostSession, val operation: Any,
        val credentials: RoomCredentials, val invite: GuestInvite, val replacement: GuestInvite?) : ResultEvent
    data class CreateFailed(val owner: HostSession, val operation: Any,
        val message: UserMessage, val replacement: GuestInvite?) : ResultEvent
    data class Sync(val owner: HostSession, val operation: Any, val value: RoomSyncState) : ResultEvent
    data class Recovered(val owner: HostSession, val operation: Any, val value: RoomFetchResult) : ResultEvent
    data class Queue(val owner: HostSession, val value: QueueAdvancementState) : ResultEvent
    data class Playback(val owner: HostSession, val value: LocalPlaybackState) : ResultEvent
}

/** qmix#312: a single coroutine handles a typed FIFO inbox; observers only offer events. */
class HostSessionController(
    private val httpClient: OkHttpClient,
    initialBackendUrl: String = "",
    initialGuestOrigin: String = initialBackendUrl,
    private val settingsPersistence: EndpointSettingsPersistence = InitialEndpointSettingsPersistence(
        EndpointSettings(initialBackendUrl, initialGuestOrigin),
    ),
    private val roomRepositoryFactory: ((String) -> RoomRepository)? = null,
    roomCollectionScope: CoroutineScope? = null,
    private val roomCollectionContext: CoroutineContext = Dispatchers.IO,
    private val foregroundReconcilerFactory: ((String) -> suspend (String) -> RoomFetchResult)? = null,
    private val queueMutationContext: QueueMutationContext = QueueMutationContext(Dispatchers.Main),
    private val queueCoordinatorFactory: QueueCoordinatorFactory? = null,
    private val playbackCoordinatorFactory: PlaybackCoordinatorFactory? = null,
    private val primaryActionHandler: () -> Unit = {},
    private val roomApiLogger: QMixComponentLogger = QMixComponentLogger.noOp(QMixLogComponent.ROOM_API_CREATION),
    private val foregroundRecoveryContext: CoroutineContext = roomCollectionContext,
    roomCloseScope: CoroutineScope? = null,
    private val roomCloseCommandFactory: (String) -> RoomCloseCommand = { backend ->
        RoomCloseCommand(RoomApiClient(httpClient, backend, roomApiLogger)::deleteRoom)
    },
) : LiveRoomHandler {
    private val ownerScope = roomCollectionScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeScope = roomCloseScope ?: CoroutineScope(ownerScope.coroutineContext + Dispatchers.IO)
    private val initialSettings = settingsPersistence.load()
    private val mutableStates = MutableStateFlow<HostingState>(
        HostingState.Setup(initialSettings.backendUrl, initialSettings.guestOrigin))
    val states: StateFlow<HostingState> = mutableStates.asStateFlow()
    val state: HostingState get() = states.value
    val roomSyncState: RoomSyncState? get() = (state as? HostingState.LiveRoom)?.synchronization
    private val inbox = Channel<HostEvent>(Channel.UNLIMITED)
    private var session: HostSession? = null
    private var foreground = true
    private var httpAcknowledgementQuarantined = false

    init {
        ownerScope.launch(queueMutationContext.coroutineContext) {
            try { for (event in inbox) handle(event) }
            finally { inbox.close(); session?.job?.cancel() }
        }
    }

    private fun offer(event: HostEvent) { inbox.trySend(event) }
    private fun publish(value: HostingState) { mutableStates.value = value }
    private suspend fun handle(event: HostEvent) {
        when (event) {
            is SetupEvent -> handleSetup(event)
            is RoomEvent -> handleRoom(event)
            is ControlEvent -> handleControl(event)
            is ResultEvent -> handleResult(event)
        }
    }

    fun updateSettings(backendUrl: String, guestOrigin: String) = offer(SetupEvent.Settings(backendUrl, guestOrigin))
    fun createRoom() = offer(SetupEvent.Create)
    fun confirmHttpWarning() = offer(SetupEvent.Confirm)
    fun cancelHttpWarning() = offer(SetupEvent.CancelWarning)
    fun enterRoom() = offer(RoomEvent.Enter)
    fun onHostStopped() = offer(RoomEvent.Stop)
    fun onHostStarted() = offer(RoomEvent.Start)
    fun endRoom() = offer(RoomEvent.End(explicit = true))
    fun abandonRoom() = offer(RoomEvent.End(explicit = false))
    fun setCommandPending(pending: Boolean) = offer(RoomEvent.Pending(pending))
    override fun onBack(onExit: () -> Unit) = offer(RoomEvent.Back(onExit))
    override fun onInvite() = offer(RoomEvent.Invite)
    override fun onNewRoom() = offer(RoomEvent.NewRoom)
    override fun onStartOrNext() = offer(ControlEvent.Advance)
    fun onPlaybackEnded(trackId: String) = offer(ControlEvent.Ended(null, trackId))
    override fun onPlayPause() = offer(ControlEvent.Playback(PlaybackCommand.TOGGLE))
    override fun onPlay() = resumePlayback()
    override fun onPause() = pausePlayback()
    fun resumePlayback() = offer(ControlEvent.Playback(PlaybackCommand.PLAY))
    fun pausePlayback() = offer(ControlEvent.Playback(PlaybackCommand.PAUSE))
    override fun onRetryCurrent() = retryCurrent()
    fun retryCurrent() = offer(ControlEvent.Playback(PlaybackCommand.RETRY))
    override fun onSeekBy(offsetMs: Long) = offer(ControlEvent.Seek(offsetMs))

    private fun handleSetup(event: SetupEvent) {
        when (event) {
            is SetupEvent.Settings -> if (state is HostingState.Setup || state is HostingState.Error) {
                publish(HostingState.Setup(event.backend, event.guest))
            }
            SetupEvent.Create -> requestCreate()
            SetupEvent.Confirm -> confirmWarning()
            SetupEvent.CancelWarning -> (state as? HostingState.HttpWarning)?.let {
                publish(HostingState.Setup(it.backendUrl, it.guestOrigin))
            }
        }
    }

    private fun requestCreate() {
        val candidate = when (val current = state) {
            is HostingState.Setup -> EndpointSettings(current.backendUrl, current.guestOrigin)
            is HostingState.Error -> EndpointSettings(current.backendUrl, current.guestOrigin)
            else -> return
        }
        val settings = EndpointSettings.validate(candidate.backendUrl, candidate.guestOrigin)
        if (settings == null) {
            publish(HostingState.Error(UserMessage.INVALID_ENDPOINT, "", ""))
        } else if (settings.usesHttp && (httpAcknowledgementQuarantined || !settingsPersistence.isHttpWarningAcknowledged())) {
            publish(HostingState.HttpWarning(settings.backendUrl, settings.guestOrigin))
        } else beginCreate(settings)
    }

    private fun confirmWarning() {
        val warning = state as? HostingState.HttpWarning ?: return
        httpAcknowledgementQuarantined = true
        try {
            settingsPersistence.acknowledgeHttpWarning()
        } catch (_: IllegalStateException) {
            publish(HostingState.Error(UserMessage.PERSISTENCE_ERROR, warning.backendUrl, warning.guestOrigin))
            return
        }
        httpAcknowledgementQuarantined = false
        beginCreate(EndpointSettings(warning.backendUrl, warning.guestOrigin))
    }

    private fun beginCreate(settings: EndpointSettings, replacement: GuestInvite? = null, automatic: Boolean = false) {
        val owner = HostSession(ownerScope.coroutineContext[Job], settings)
        owner.unvalidatedAutomaticReplacement = automatic
        session = owner
        publish(HostingState.Pending(settings.backendUrl, settings.guestOrigin))
        val operation = owner.operation
        owner.worker = workerScope(owner).launch(roomCollectionContext) {
            createWorker(owner, operation, replacement)
        }
    }

    private fun workerScope(owner: HostSession) = CoroutineScope(ownerScope.coroutineContext + owner.job)
    private suspend fun createWorker(owner: HostSession, operation: Any, replacement: GuestInvite?) {
        val settings = owner.settings
        try {
            val credentials = RoomApiClient(httpClient, settings.backendUrl, roomApiLogger).createRoom()
            val invite = GuestInvite.create(credentials, settings.guestOrigin)
            settingsPersistence.save(settings)
            coroutineContext.ensureActive()
            offer(ResultEvent.Created(owner, operation, credentials, invite, replacement))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RoomApiException) {
            offer(ResultEvent.CreateFailed(owner, operation, failure.userMessage, replacement))
        } catch (_: IllegalArgumentException) {
            offer(ResultEvent.CreateFailed(owner, operation, UserMessage.INVALID_ENDPOINT, replacement))
        } catch (_: IllegalStateException) {
            offer(ResultEvent.CreateFailed(owner, operation, UserMessage.PERSISTENCE_ERROR, replacement))
        }
    }

    private suspend fun handleRoom(event: RoomEvent) {
        when (event) {
            RoomEvent.Enter -> enter()
            RoomEvent.Start -> start()
            RoomEvent.Stop -> stop()
            is RoomEvent.End -> end(event.explicit)
            is RoomEvent.Back -> back(event.onExit)
            RoomEvent.NewRoom -> newRoom()
            RoomEvent.Invite -> updateLive { if (it.synchronization is RoomSyncState.Missing) it else it.copy(invitationVisible = true) }
            is RoomEvent.Pending -> updateLive { it.copy(commandPending = event.value) }
        }
    }

    private fun updateLive(update: (HostingState.LiveRoom) -> HostingState.LiveRoom) {
        (state as? HostingState.LiveRoom)?.let { publish(update(it)) }
    }

    private suspend fun enter() {
        val current = state as? HostingState.Invitation ?: return
        val owner = session ?: return
        publish(HostingState.LiveRoom(current.invite,
            RoomSyncState.Active(current.invite.code, null, Freshness.LOADING, LiveConnection.CONNECTING),
            foregroundRecoveryPending = current.foregroundRecoveryPending))
        cancelWorker(owner)
        installCoordinators(owner)
        if (current.foregroundRecoveryPending) {
            suspendCoordinators(owner)
            if (foreground) startRecovery(owner, current.invite)
        } else startCollection(owner, current.invite)
    }

    private fun installCoordinators(owner: HostSession) {
        val credentials = owner.credentials ?: return
        val scope = CoroutineScope(ownerScope.coroutineContext + owner.job + roomCollectionContext)
        owner.queue = queueCoordinatorFactory?.invoke(owner.settings.backendUrl, credentials,
            { offer(ResultEvent.Queue(owner, it)) }, scope)
        owner.playback = playbackCoordinatorFactory?.invoke(owner.settings.backendUrl, credentials,
            { offer(ResultEvent.Playback(owner, it)) },
            { offer(ControlEvent.Ended(owner, it)) }, scope)
    }

    private suspend fun cancelWorker(owner: HostSession) {
        owner.operation = Any()
        val worker = owner.worker
        owner.worker = null
        worker?.cancelAndJoin()
    }

    private fun startCollection(owner: HostSession, invite: GuestInvite) {
        if (!foreground) return
        val repository = roomRepositoryFactory?.invoke(owner.settings.backendUrl) ?: return
        val operation = Any().also { owner.operation = it }
        owner.worker = workerScope(owner).launch(roomCollectionContext) {
            repository.observe(invite.code).collect { offer(ResultEvent.Sync(owner, operation, it)) }
        }
    }

    private suspend fun stop() {
        if (!foreground) return
        foreground = false
        val owner = session ?: return
        when (val current = state) {
            is HostingState.Invitation -> publish(current.copy(foregroundRecoveryPending = true))
            is HostingState.LiveRoom -> if (current.synchronization !is RoomSyncState.Missing) {
                publish(current.copy(foregroundRecoveryPending = true, commandPending = false))
            }
            else -> return // Creation remains owned, publishing a recovery-pending invitation if it succeeds.
        }
        val jobs = suspendCoordinators(owner)
        updateLive { it.copy(playback = owner.playback?.state ?: it.playback) }
        cancelWorker(owner)
        jobs.forEach { it.join() }
    }

    private fun suspendCoordinators(owner: HostSession): List<Job> {
        val jobs = owner.queue?.foregroundWorkers().orEmpty() + owner.playback?.foregroundWorkers().orEmpty()
        owner.queue?.onForegroundLost()
        owner.playback?.onForegroundLost()
        return jobs
    }

    private fun start() {
        if (foreground) return
        foreground = true
        session?.let { owner -> currentInvite()?.let { startRecovery(owner, it) } }
    }

    private fun currentInvite(): GuestInvite? = when (val current = state) {
        is HostingState.Invitation -> current.invite
        is HostingState.LiveRoom -> current.invite
        else -> null
    }

    private fun startRecovery(owner: HostSession, invite: GuestInvite) {
        val fetch = foregroundReconcilerFactory?.invoke(owner.settings.backendUrl) ?: return
        val operation = Any().also { owner.operation = it }
        owner.worker = workerScope(owner).launch(foregroundRecoveryContext) {
            val result = try {
                fetch(invite.code)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RoomFetchResult.Failure
            }
            offer(ResultEvent.Recovered(owner, operation, result))
        }
    }

    private fun eligibleSession(): HostSession? {
        val current = state as? HostingState.LiveRoom ?: return null
        return session?.takeIf { foreground && !current.foregroundRecoveryPending &&
            current.synchronization !is RoomSyncState.Missing }
    }

    private fun handleControl(event: ControlEvent) {
        val owner = eligibleSession() ?: return
        when (event) {
            ControlEvent.Advance -> if ((state as HostingState.LiveRoom).isPrimaryActionEnabled) {
                owner.queue?.requestExplicitAdvance() ?: primaryActionHandler()
            }
            is ControlEvent.Ended -> if (event.owner == null || event.owner === owner) {
                owner.queue?.onPlaybackEnded(event.trackId)
            }
            is ControlEvent.Seek -> owner.playback?.seekBy(event.offset)
            is ControlEvent.Playback -> playbackControl(owner.playback, event.command)
        }
    }

    private fun playbackControl(playback: AuthoritativePlaybackCoordinator?, command: PlaybackCommand) {
        when (command) {
            PlaybackCommand.TOGGLE -> playback?.togglePlayPause()
            PlaybackCommand.PLAY -> playback?.resume()
            PlaybackCommand.PAUSE -> playback?.pause()
            PlaybackCommand.RETRY -> playback?.retryCurrent()
        }
    }

    private suspend fun handleResult(event: ResultEvent) {
        when (event) {
            is ResultEvent.Created -> created(event)
            is ResultEvent.CreateFailed -> createFailed(event)
            is ResultEvent.Sync -> synchronize(event)
            is ResultEvent.Recovered -> recovered(event)
            is ResultEvent.Queue -> queueUpdate(event)
            is ResultEvent.Playback -> playbackUpdate(event)
        }
    }

    private fun owns(owner: HostSession, operation: Any) = session === owner && owner.operation === operation
    private suspend fun created(event: ResultEvent.Created) {
        if (!owns(event.owner, event.operation)) return
        val owner = event.owner
        cancelWorker(owner)
        owner.credentials = event.credentials
        publish(HostingState.Invitation(event.invite, event.replacement != null, !foreground))
        startCollection(owner, event.invite)
    }

    private suspend fun createFailed(event: ResultEvent.CreateFailed) {
        if (!owns(event.owner, event.operation)) return
        session = null
        event.owner.job.cancelAndJoin()
        val settings = event.owner.settings
        if (event.replacement == null) publish(HostingState.Error(event.message, settings.backendUrl, settings.guestOrigin))
        else publish(HostingState.LiveRoom(event.replacement, RoomSyncState.Missing(event.replacement.code),
            replacementError = event.message))
    }

    private fun queueUpdate(event: ResultEvent.Queue) {
        if (session === event.owner && event.owner.queue?.state === event.value) {
            updateLive { it.copy(commandPending = event.value.pending) }
        }
    }

    private fun playbackUpdate(event: ResultEvent.Playback) {
        if (session === event.owner && event.owner.playback?.state === event.value) {
            updateLive { it.copy(playback = event.value) }
        }
    }

    private suspend fun synchronize(event: ResultEvent.Sync) {
        if (!owns(event.owner, event.operation) || !foreground) return
        val owner = event.owner
        val sync = event.value
        val invite = currentInvite() ?: return
        if (!matchesRoom(invite.code, sync)) return
        if (sync is RoomSyncState.Missing) { missing(owner, invite); return }
        val active = sync as RoomSyncState.Active
        active.room?.let { recordRoom(owner, it, active.freshness == Freshness.FRESH) }
        val current = state as? HostingState.LiveRoom ?: return recoverInvitation(owner, active)
        if (current.synchronization is RoomSyncState.Missing) return
        publish(current.copy(synchronization = retainLastKnownRoom(current.synchronization, sync)))
        followSynchronization(owner, active, current)
    }

    private suspend fun followSynchronization(
        owner: HostSession, active: RoomSyncState.Active, current: HostingState.LiveRoom,
    ) {
        if (current.foregroundRecoveryPending) {
            freshRecovery(owner, active, current.invite)
        } else {
            owner.playback?.onSynchronization(active)
            if (active.freshness == Freshness.FRESH) active.room?.let { owner.queue?.onAuthoritativeRoom(it) }
        }
    }

    private suspend fun recoverInvitation(owner: HostSession, active: RoomSyncState.Active) {
        val current = state as? HostingState.Invitation ?: return
        if (current.foregroundRecoveryPending) freshRecovery(owner, active, current.invite)
    }

    private suspend fun freshRecovery(owner: HostSession, active: RoomSyncState.Active, invite: GuestInvite) {
        if (active.freshness == Freshness.FRESH && active.room != null) {
            cancelWorker(owner)
            startRecovery(owner, invite)
        }
    }

    private fun matchesRoom(code: String, sync: RoomSyncState): Boolean =
        sync.roomCode == code && ((sync as? RoomSyncState.Active)?.room?.code ?: code) == code

    private fun recordRoom(owner: HostSession, room: RoomState, fresh: Boolean) {
        if (fresh) owner.unvalidatedAutomaticReplacement = false
        if (room.current != null || room.queue.isNotEmpty()) owner.observedTrack = true
    }

    private fun retainLastKnownRoom(previous: RoomSyncState, update: RoomSyncState): RoomSyncState {
        if (update !is RoomSyncState.Active || update.freshness != Freshness.STALE || update.room != null) return update
        return update.copy(room = (previous as? RoomSyncState.Active)?.room)
    }

    private suspend fun recovered(event: ResultEvent.Recovered) {
        if (!owns(event.owner, event.operation) || !foreground) return
        val owner = event.owner
        val result = event.value
        val invite = currentInvite() ?: return
        cancelWorker(owner)
        when (result) {
            RoomFetchResult.Missing -> { missing(owner, invite); return }
            is RoomFetchResult.Success -> if (result.room.code == invite.code) applyRecovery(owner, result.room)
            RoomFetchResult.Failure -> Unit
        }
        startCollection(owner, invite)
    }

    private fun applyRecovery(owner: HostSession, room: RoomState) {
        recordRoom(owner, room, fresh = true)
        when (val current = state) {
            is HostingState.Invitation -> publish(current.copy(foregroundRecoveryPending = false))
            is HostingState.LiveRoom -> {
                val connection = (current.synchronization as? RoomSyncState.Active)?.connection ?: LiveConnection.CONNECTING
                publish(current.copy(synchronization = RoomSyncState.Active(room.code, room, Freshness.FRESH, connection),
                    foregroundRecoveryPending = false))
                owner.queue?.onForegroundReconciled(room)
                owner.playback?.onForegroundReconciled(room)
            }
            else -> Unit
        }
    }

    private suspend fun missing(owner: HostSession, invite: GuestInvite) {
        if (!owner.observedTrack && !owner.unvalidatedAutomaticReplacement) {
            replace(owner, invite, automatic = true)
        } else {
            val previous = state as? HostingState.LiveRoom ?: HostingState.LiveRoom(invite, RoomSyncState.Missing(invite.code))
            publish(previous.copy(synchronization = RoomSyncState.Missing(invite.code), commandPending = false,
                foregroundRecoveryPending = false, invitationVisible = false))
            // No final PAUSED report for a room that no longer exists.
            cleanup(owner)
        }
    }

    private suspend fun newRoom() {
        val current = state as? HostingState.LiveRoom ?: return
        if (!foreground || current.synchronization !is RoomSyncState.Missing) return
        val settings = settingsForCurrent()
        session?.let { cleanup(it) }
        beginCreate(settings, current.invite)
    }

    private suspend fun replace(owner: HostSession, invite: GuestInvite, automatic: Boolean) {
        val settings = owner.settings
        publish(HostingState.Ending)
        cleanup(owner)
        // Missing is admitted only in foreground. A queued Stop does not preempt this handler.
        beginCreate(settings, invite, automatic)
    }

    private suspend fun back(onExit: () -> Unit) {
        val current = state as? HostingState.LiveRoom ?: return
        if (current.invitationVisible) publish(current.copy(invitationVisible = false))
        else { end(explicit = true); runCatching { onExit() } }
    }

    private suspend fun end(explicit: Boolean) {
        val settings = settingsForCurrent()
        val owner = session
        publish(HostingState.Ending)
        if (explicit) owner?.credentials?.let { closeBackendRoom(owner.settings.backendUrl, it) }
        if (owner != null) cleanup(owner)
        publish(HostingState.Setup(settings.backendUrl, settings.guestOrigin))
    }

    private suspend fun cleanup(owner: HostSession) {
        session = null
        owner.job.cancel()
        runCatching { owner.queue?.close() }
        runCatching { owner.playback?.close() }
        owner.job.join()
    }

    private fun settingsForCurrent(): EndpointSettings = session?.settings ?: when (val current = state) {
        is HostingState.Setup -> EndpointSettings(current.backendUrl, current.guestOrigin)
        is HostingState.HttpWarning -> EndpointSettings(current.backendUrl, current.guestOrigin)
        is HostingState.Error -> EndpointSettings(current.backendUrl, current.guestOrigin)
        else -> settingsPersistence.load()
    }

    private fun closeBackendRoom(backend: String, credentials: RoomCredentials) {
        closeScope.launch(Dispatchers.IO) {
            try {
                withTimeout(3_000L) { roomCloseCommandFactory(backend).close(credentials.code, credentials.hostToken) }
            } catch (failure: Exception) {
                roomApiLogger.warn(QMixLogOperation.DELETE_ROOM,
                    if (failure is RoomApiException) failure.logCause else QMixLogCause.NETWORK)
            }
        }
    }
}
