package com.qmix.tv

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

sealed interface HostingState {
    data class Setup(val backendUrl: String, val guestOrigin: String) : HostingState
    data object Ending : HostingState
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
                return !commandPending && !foregroundRecoveryPending &&
                    active.room?.queue?.isNotEmpty() == true &&
                    active.freshness == Freshness.FRESH && active.connection == LiveConnection.CONNECTED
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
    sessionScope: CoroutineScope,
) -> QueueAdvancementCoordinator

typealias PlaybackCoordinatorFactory = (
    backendUrl: String,
    credentials: RoomCredentials,
    observer: (LocalPlaybackState) -> Unit,
    advanceAfterEnded: (String) -> Boolean,
    sessionScope: CoroutineScope,
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

/** qmix#217: the room tree remains owned through creation, invitation, and live playback. */
private class HostSession(parent: Job?) {
    val job = SupervisorJob(parent)
    var worker: Job? = null
    var stopping: Job? = null
    var recovering = false
    var credentials: RoomCredentials? = null
    var backendUrl: String? = null
    var queue: QueueAdvancementCoordinator? = null
    var playback: AuthoritativePlaybackCoordinator? = null
}

/** qmix#182: one mutation context owns every admission and state transition. */
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
    private val queueMutationContext: QueueMutationContext = QueueMutationContext(Dispatchers.Default.limitedParallelism(1)),
    private val queueCoordinatorFactory: QueueCoordinatorFactory? = null,
    private val playbackCoordinatorFactory: PlaybackCoordinatorFactory? = null,
    private val primaryActionHandler: () -> Unit = {},
    private val roomApiLogger: QMixComponentLogger = QMixComponentLogger.noOp(QMixLogComponent.ROOM_API_CREATION),
    private val foregroundRecoveryContext: CoroutineContext = roomCollectionContext,
) : LiveRoomHandler {
    private val ownerScope = roomCollectionScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialSettings = settingsPersistence.load()
    private var setupState = HostingState.Setup(initialSettings.backendUrl, initialSettings.guestOrigin)
    private val mutableStates = MutableStateFlow<HostingState>(setupState)
    val states: StateFlow<HostingState> = mutableStates.asStateFlow()
    val state: HostingState get() = states.value
    val roomSyncState: RoomSyncState? get() = (state as? HostingState.LiveRoom)?.synchronization

    private var session: HostSession? = null
    private var foreground = true
    private var httpAcknowledgementQuarantined = false
    private var endingRoom = false
    private fun publish(newState: HostingState) { mutableStates.value = newState }
    private fun deliverCoordinatorUpdate(owner: HostSession, action: () -> Unit) {
        if (queueMutationContext.isOnContext()) {
            if (owner.job.isActive) action()
        } else CoroutineScope(ownerScope.coroutineContext + owner.job + roomCollectionContext).launch {
            queueMutationContext.runFromWorker { if (owner.job.isActive) action() }
        }
    }

    fun updateSettings(backendUrl: String, guestOrigin: String): Unit = queueMutationContext.run {
        if (!endingRoom && (state is HostingState.Setup || state is HostingState.Error)) {
            setupState = HostingState.Setup(backendUrl, guestOrigin)
            publish(setupState)
        }
    }

    fun createRoom(): Boolean = queueMutationContext.run {
        if (endingRoom) return@run false
        val candidate = when (val current = state) {
            is HostingState.Setup -> current
            is HostingState.Error -> HostingState.Setup(current.backendUrl, current.guestOrigin)
            else -> return@run false
        }
        val settings = EndpointSettings.validate(candidate.backendUrl, candidate.guestOrigin)
        if (settings == null) {
            setupState = HostingState.Setup("", "")
            publish(HostingState.Error(UserMessage.INVALID_ENDPOINT, "", ""))
            return@run false
        }
        setupState = HostingState.Setup(settings.backendUrl, settings.guestOrigin)
        if (settings.usesHttp && (httpAcknowledgementQuarantined || !settingsPersistence.isHttpWarningAcknowledged())) {
            publish(HostingState.HttpWarning(settings.backendUrl, settings.guestOrigin))
        } else {
            beginCreate(settings)
        }
        true
    }

    fun confirmHttpWarning(): Boolean = queueMutationContext.run {
        val warning = state as? HostingState.HttpWarning ?: return@run false
        val settings = EndpointSettings(warning.backendUrl, warning.guestOrigin)
        httpAcknowledgementQuarantined = true
        try {
            settingsPersistence.acknowledgeHttpWarning()
        } catch (_: IllegalStateException) {
            publish(HostingState.Error(UserMessage.PERSISTENCE_ERROR, warning.backendUrl, warning.guestOrigin))
            return@run false
        }
        httpAcknowledgementQuarantined = false
        setupState = HostingState.Setup(settings.backendUrl, settings.guestOrigin)
        beginCreate(settings)
        true
    }

    fun cancelHttpWarning(): Unit = queueMutationContext.run {
        val warning = state as? HostingState.HttpWarning ?: return@run
        setupState = HostingState.Setup(warning.backendUrl, warning.guestOrigin)
        publish(setupState)
    }

    private fun beginCreate(settings: EndpointSettings) {
        val owner = HostSession(ownerScope.coroutineContext[Job])
        session = owner
        val job = CoroutineScope(ownerScope.coroutineContext + owner.job).launch(roomCollectionContext, start = CoroutineStart.LAZY) {
            try {
                val created = RoomApiClient(httpClient, settings.backendUrl, roomApiLogger).createRoom()
                val invite = GuestInvite.create(created, settings.guestOrigin)
                val stillPending = queueMutationContext.runFromWorker {
                    owner.job.isActive && state is HostingState.Pending
                }
                if (!stillPending) return@launch
                // Persistence can block on disk. Keep it off the mutation lane so endRoom
                // can detach/cancel this worker; revalidate admission after save returns.
                settingsPersistence.save(settings)
                coroutineContext.ensureActive()
                queueMutationContext.runFromWorker {
                    if (!owner.job.isActive || state !is HostingState.Pending) return@runFromWorker
                    owner.credentials = created
                    owner.backendUrl = settings.backendUrl
                    owner.worker = null
                    publish(HostingState.Invitation(invite))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RoomApiException) {
                createError(owner, settings, error.userMessage)
            } catch (_: IllegalArgumentException) {
                createError(owner, settings, UserMessage.INVALID_ENDPOINT)
            } catch (_: IllegalStateException) {
                createError(owner, settings, UserMessage.PERSISTENCE_ERROR)
            }
        }
        owner.worker = job
        publish(HostingState.Pending(settings.backendUrl, settings.guestOrigin))
        // A cancelled lazy child cannot execute, including after a reentrant endRoom.
        job.start()
    }

    private suspend fun createError(owner: HostSession, settings: EndpointSettings, message: UserMessage) = queueMutationContext.runFromWorker {
        if (owner.job.isActive && state is HostingState.Pending) {
            session = null
            owner.worker = null
            owner.job.cancel()
            publish(HostingState.Error(message, settings.backendUrl, settings.guestOrigin))
        }
    }

    fun enterRoom(): Unit = queueMutationContext.run {
        val invitation = state as? HostingState.Invitation ?: return@run
        val invite = invitation.invite
        val owner = session ?: return@run
        val backend = owner.backendUrl ?: return@run
        publish(HostingState.LiveRoom(
            invite, RoomSyncState.Active(invite.code, null, Freshness.LOADING, LiveConnection.CONNECTING),
        ))
        if (!owner.job.isActive) return@run
        val sessionCredentials = owner.credentials
        // Transport uses the worker context; all publications re-enter the mutation context.
        // The detached owner is joined only by the off-dispatcher finalizer.
        val sessionScope = CoroutineScope(ownerScope.coroutineContext + owner.job + roomCollectionContext)
        val queue = if (sessionCredentials != null) queueCoordinatorFactory?.invoke(backend, sessionCredentials, { update ->
            deliverCoordinatorUpdate(owner) delivery@{
                val current = state as? HostingState.LiveRoom ?: return@delivery
                if (owner.queue?.state === update && current.commandPending != update.pending) {
                    publish(current.copy(commandPending = update.pending))
                }
            }
        }, sessionScope) else null
        if (!owner.job.isActive) { queue?.close(); return@run }
        owner.queue = queue
        val playback = if (sessionCredentials != null) playbackCoordinatorFactory?.invoke(
            backend, sessionCredentials,
            { playback ->
                deliverCoordinatorUpdate(owner) delivery@{
                    val current = state as? HostingState.LiveRoom ?: return@delivery
                    if (current.playback != playback) publish(current.copy(playback = playback))
                }
            },
            { trackId -> queueMutationContext.run {
                owner.job.isActive && owner.queue?.onPlaybackEnded(trackId) == true
            } }, sessionScope,
        ) else null
        if (!owner.job.isActive) { playback?.close(); return@run }
        owner.playback = playback
        startRoomCollection(owner, backend, invite)
    }

    private fun startRoomCollection(owner: HostSession, backend: String, invite: GuestInvite) {
        if (!canStartCollection(owner, invite)) return
        val repository = roomRepositoryFactory?.invoke(backend) ?: return
        // A factory can synchronously stop/start the host and install a recovery worker.
        if (!canStartCollection(owner, invite)) return
        val scope = CoroutineScope(ownerScope.coroutineContext + owner.job)
        val job = scope.launch(roomCollectionContext, start = CoroutineStart.LAZY) {
            repository.observe(invite.code).collect { sync ->
                val collectingJob = coroutineContext[Job]
                queueMutationContext.runFromWorker {
                    if (owner.job.isActive && owner.worker === collectingJob) {
                        publishRoomSynchronization(owner, collectingJob, sync)
                    }
                }
            }
        }
        owner.recovering = false
        owner.worker = job
        if (foreground) job.start() else job.cancel()
    }

    private fun canStartCollection(owner: HostSession, invite: GuestInvite): Boolean {
        val current = state as? HostingState.LiveRoom ?: return false
        return session === owner && owner.job.isActive && foreground &&
            owner.worker == null && owner.stopping == null && current.invite === invite
    }

    private fun publishRoomSynchronization(owner: HostSession, collectingJob: Job?, sync: RoomSyncState) {
        val current = state as? HostingState.LiveRoom ?: return
        if (current.invite.code != sync.roomCode) return
        publish(current.copy(synchronization = retainLastKnownRoom(current.synchronization, sync)))
        if (!stillCollecting(owner, collectingJob, current.invite)) return
        owner.playback?.onSynchronization(sync)
        if (!stillCollecting(owner, collectingJob, current.invite)) return
        val active = sync as? RoomSyncState.Active
        if (!current.foregroundRecoveryPending && active?.freshness == Freshness.FRESH && active.room != null) {
            owner.queue?.onAuthoritativeRoom(active.room)
        } else if (current.foregroundRecoveryPending && foreground && active?.freshness == Freshness.FRESH &&
            active.room != null && !owner.recovering
        ) {
            startForegroundRecovery(owner)
        }
    }

    private fun stillCollecting(owner: HostSession, job: Job?, invite: GuestInvite): Boolean =
        session === owner && owner.job.isActive && foreground && owner.worker === job &&
            (state as? HostingState.LiveRoom)?.invite === invite

    private fun retainLastKnownRoom(previous: RoomSyncState, update: RoomSyncState): RoomSyncState {
        if (update !is RoomSyncState.Active || update.freshness != Freshness.STALE || update.room != null) return update
        val lastKnown = (previous as? RoomSyncState.Active)?.room ?: return update
        return update.copy(room = lastKnown)
    }

    fun setCommandPending(pending: Boolean): Unit = queueMutationContext.run {
        val current = state as? HostingState.LiveRoom ?: return@run
        if (current.commandPending != pending) publish(current.copy(commandPending = pending))
    }

    private fun stopWorker(owner: HostSession) {
        val worker = owner.worker ?: return
        owner.worker = null
        owner.recovering = false
        owner.stopping = worker
        worker.cancel()
        CoroutineScope(ownerScope.coroutineContext + owner.job + Dispatchers.IO).launch {
            worker.join()
            queueMutationContext.runFromWorker {
                if (owner.stopping === worker) {
                    owner.stopping = null
                    if (foreground) startForegroundRecovery(owner)
                }
            }
        }
    }

    fun onHostStopped(): Unit = queueMutationContext.run {
        val current = state as? HostingState.LiveRoom ?: return@run
        val owner = session ?: return@run
        if (!foreground) return@run
        foreground = false
        stopWorker(owner)
        owner.queue?.onForegroundLost()
        if (state is HostingState.LiveRoom) owner.playback?.onForegroundLost()
        val latest = state as? HostingState.LiveRoom ?: return@run
        if (latest.invite !== current.invite) return@run
        publish(latest.copy(foregroundRecoveryPending = true, commandPending = false))
    }

    fun onHostStarted(): Unit = queueMutationContext.run {
        if (foreground) return@run
        foreground = true
        session?.let(::startForegroundRecovery)
    }

    private fun startForegroundRecovery(owner: HostSession) {
        val current = state as? HostingState.LiveRoom ?: return
        if (!owner.job.isActive || !foreground || owner.stopping != null || !current.foregroundRecoveryPending) return
        val backend = owner.backendUrl ?: return
        val fetch = foregroundReconcilerFactory?.invoke(backend) ?: return
        val latest = state as? HostingState.LiveRoom ?: return
        if (session !== owner || !owner.job.isActive || !foreground || owner.stopping != null ||
            latest.invite !== current.invite || !latest.foregroundRecoveryPending) return
        if (owner.worker != null) {
            if (!owner.recovering) stopWorker(owner)
            return
        }
        val code = current.invite.code
        val scope = CoroutineScope(ownerScope.coroutineContext + owner.job)
        val job = scope.launch(foregroundRecoveryContext, start = CoroutineStart.LAZY) {
            val result = try {
                fetch(code)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RoomFetchResult.Failure
            }
            coroutineContext.ensureActive()
            val recoveringJob = coroutineContext[Job]
            queueMutationContext.runFromWorker {
                completeForegroundRecovery(owner, recoveringJob, code, result)
            }
        }
        owner.recovering = true
        owner.worker = job
        if (foreground) job.start() else job.cancel()
    }

    private fun completeForegroundRecovery(owner: HostSession, job: Job?, code: String, result: RoomFetchResult) {
        val current = state as? HostingState.LiveRoom ?: return
        if (!foreground || !owner.job.isActive || owner.worker !== job || current.invite.code != code) return
        owner.worker = null
        owner.recovering = false
        when (result) {
            RoomFetchResult.Missing -> publish(current.copy(synchronization = RoomSyncState.Missing(code)))
            is RoomFetchResult.Success -> if (result.room.code == code) {
                val connection = (current.synchronization as? RoomSyncState.Active)?.connection ?: LiveConnection.CONNECTING
                publish(current.copy(
                    synchronization = RoomSyncState.Active(code, result.room, Freshness.FRESH, connection),
                    foregroundRecoveryPending = false,
                ))
                // A collector or coordinator can synchronously stop/restart this session.
                if (!stillRecovered(owner, current.invite, result.room)) return
                owner.queue?.onForegroundReconciled(result.room)
                if (!stillRecovered(owner, current.invite, result.room)) return
                owner.playback?.onForegroundReconciled(result.room)
                if (!stillRecovered(owner, current.invite, result.room)) return
            }
            RoomFetchResult.Failure -> Unit
        }
        if (result != RoomFetchResult.Missing) {
            owner.backendUrl?.let { startRoomCollection(owner, it, current.invite) }
        }
    }

    private fun stillRecovered(owner: HostSession, invite: GuestInvite, room: RoomState): Boolean {
        val latest = state as? HostingState.LiveRoom ?: return false
        val sync = latest.synchronization as? RoomSyncState.Active ?: return false
        return session === owner && owner.job.isActive && foreground && owner.worker == null &&
            owner.stopping == null && latest.invite === invite && !latest.foregroundRecoveryPending &&
            sync.room === room && sync.freshness == Freshness.FRESH
    }

    override fun onStartOrNext(): Unit = queueMutationContext.run {
        if (endingRoom) return@run
        if ((state as? HostingState.LiveRoom)?.isPrimaryActionEnabled != true) return@run
        val coordinator = session?.queue
        if (coordinator != null) coordinator.requestExplicitAdvance() else primaryActionHandler()
    }
    fun onPlaybackEnded(trackId: String): Boolean = queueMutationContext.run {
        session?.queue?.onPlaybackEnded(trackId) == true
    }
    override fun onPlayPause(): Unit = queueMutationContext.run { session?.playback?.togglePlayPause(); Unit }
    override fun onSeekBy(offsetMs: Long): Unit = queueMutationContext.run { session?.playback?.seekBy(offsetMs); Unit }
    override fun onPlay() = resumePlayback()
    override fun onPause() = pausePlayback()
    fun pausePlayback(): Unit = queueMutationContext.run { session?.playback?.pause(); Unit }
    fun resumePlayback(): Unit = queueMutationContext.run { session?.playback?.resume(); Unit }
    override fun onRetryCurrent() = retryCurrent()
    fun retryCurrent(): Unit = queueMutationContext.run { session?.playback?.retryCurrent(); Unit }
    override fun onInvite(): Unit = queueMutationContext.run {
        val current = state as? HostingState.LiveRoom ?: return@run
        if (!current.invitationVisible) publish(current.copy(invitationVisible = true))
    }
    override fun onBack(): LiveRoomBackResult = queueMutationContext.run {
        if (endingRoom) return@run LiveRoomBackResult.EXIT_ACTIVITY
        val current = state as? HostingState.LiveRoom ?: return@run LiveRoomBackResult.IGNORED
        if (current.invitationVisible) {
            publish(current.copy(invitationVisible = false))
            LiveRoomBackResult.HANDLED
        } else {
            endRoomOnMutationContext()
            LiveRoomBackResult.EXIT_ACTIVITY
        }
    }

    fun endRoom(): Unit = queueMutationContext.run { endRoomOnMutationContext() }

    private fun endRoomOnMutationContext() {
        if (endingRoom) return
        endingRoom = true
        val detached = session
        session = null
        foreground = true
        publish(HostingState.Ending)
        detached?.job?.cancel()
        runCatching { detached?.queue?.close() }
        runCatching { detached?.playback?.close() }
        // Never wait on a child in its reducer or publication callback. This sibling finalizer
        // waits for the entire detached tree and only then reopens admission on the reducer.
        ownerScope.launch(Dispatchers.IO) {
            detached?.job?.join()
            queueMutationContext.runFromWorker {
                if (endingRoom && state === HostingState.Ending) {
                    endingRoom = false
                    publish(setupState)
                }
            }
        }
    }
}
