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
    fun onBack(): LiveRoomBackResult
}

/** qmix#217: the room tree remains owned through creation, invitation, and live playback. */
private class HostSession(parent: Job?) {
    val job = SupervisorJob(parent)
    var worker: Job? = null
    var stopping: Job? = null
    var recovering = false
    var observedTrack = false
    // An automatic replacement must be confirmed by an authoritative read before another replacement.
    var unvalidatedAutomaticReplacement = false
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
    roomCloseScope: CoroutineScope? = null,
    private val roomCloseCommandFactory: (String) -> RoomCloseCommand = { backend ->
        RoomCloseCommand(RoomApiClient(httpClient, backend, roomApiLogger)::deleteRoom)
    },
) : LiveRoomHandler {
    private val ownerScope = roomCollectionScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeScope = roomCloseScope ?: CoroutineScope(ownerScope.coroutineContext + Dispatchers.IO)
    private val initialSettings = settingsPersistence.load()
    private var setupState = HostingState.Setup(initialSettings.backendUrl, initialSettings.guestOrigin)
    private val mutableStates = MutableStateFlow<HostingState>(setupState)
    val states: StateFlow<HostingState> = mutableStates.asStateFlow()
    val state: HostingState get() = states.value
    val roomSyncState: RoomSyncState? get() = (state as? HostingState.LiveRoom)?.synchronization

    private var session: HostSession? = null
    private var foreground = true
    private var deferredReplacement: Pair<GuestInvite, Boolean>? = null
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

    private fun beginCreate(
        settings: EndpointSettings, replacementInvite: GuestInvite? = null, automaticReplacement: Boolean = false,
    ) {
        val owner = HostSession(ownerScope.coroutineContext[Job])
        owner.unvalidatedAutomaticReplacement = automaticReplacement
        session = owner
        val job = CoroutineScope(ownerScope.coroutineContext + owner.job).launch(roomCollectionContext, start = CoroutineStart.LAZY) {
            createRoomWorker(owner, settings, replacementInvite)
        }
        owner.worker = job
        publish(HostingState.Pending(settings.backendUrl, settings.guestOrigin))
        // A cancelled lazy child cannot execute, including after a reentrant endRoom.
        job.start()
    }

    private suspend fun createRoomWorker(owner: HostSession, settings: EndpointSettings, replacementInvite: GuestInvite?) {
        try {
            val created = RoomApiClient(httpClient, settings.backendUrl, roomApiLogger).createRoom()
            val invite = GuestInvite.create(created, settings.guestOrigin)
            completeCreatedRoom(owner, settings, replacementInvite, created, invite)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RoomApiException) {
            createError(owner, settings, error.userMessage, replacementInvite)
        } catch (_: IllegalArgumentException) {
            createError(owner, settings, UserMessage.INVALID_ENDPOINT, replacementInvite)
        } catch (_: IllegalStateException) {
            createError(owner, settings, UserMessage.PERSISTENCE_ERROR, replacementInvite)
        }
    }

    private suspend fun completeCreatedRoom(
        owner: HostSession, settings: EndpointSettings, replacementInvite: GuestInvite?,
        created: RoomCredentials, invite: GuestInvite,
    ) {
        val stillPending = queueMutationContext.runFromWorker {
            session === owner && owner.job.isActive && state is HostingState.Pending
        }
        if (!stillPending) return
        // Persistence can block on disk. Keep it off the mutation lane so endRoom
        // can detach/cancel this worker; revalidate admission after save returns.
        settingsPersistence.save(settings)
        coroutineContext.ensureActive()
        queueMutationContext.runFromWorker {
            if (session !== owner || !owner.job.isActive || state !is HostingState.Pending) return@runFromWorker
            owner.credentials = created
            owner.backendUrl = settings.backendUrl
            owner.worker = null
            publish(HostingState.Invitation(
                invite, roomReplacementNotice = replacementInvite != null,
                foregroundRecoveryPending = !foreground,
            ))
            startRoomCollection(owner, settings.backendUrl, invite)
        }
    }

    private suspend fun createError(
        owner: HostSession,
        settings: EndpointSettings,
        message: UserMessage,
        replacementInvite: GuestInvite?,
    ) = queueMutationContext.runFromWorker {
        if (session === owner && owner.job.isActive && state is HostingState.Pending) {
            session = null
            owner.worker = null
            owner.job.cancel()
            if (replacementInvite == null) {
                publish(HostingState.Error(message, settings.backendUrl, settings.guestOrigin))
            } else {
                publish(HostingState.LiveRoom(
                    replacementInvite, RoomSyncState.Missing(replacementInvite.code), replacementError = message,
                ))
            }
        }
    }

    fun enterRoom(): Unit = queueMutationContext.run {
        val invitation = state as? HostingState.Invitation ?: return@run
        val invite = invitation.invite
        val owner = session ?: return@run
        val backend = owner.backendUrl ?: return@run
        publish(HostingState.LiveRoom(
            invite, RoomSyncState.Active(invite.code, null, Freshness.LOADING, LiveConnection.CONNECTING),
            foregroundRecoveryPending = invitation.foregroundRecoveryPending,
        ))
        if (owner.worker != null) stopWorker(owner)
        if (!owner.job.isActive) return@run
        installRoomCoordinators(owner, backend)
        if (owner.job.isActive && owner.stopping == null && !invitation.foregroundRecoveryPending) {
            startRoomCollection(owner, backend, invite)
        }
    }

    private fun installRoomCoordinators(owner: HostSession, backend: String) {
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
        if (!owner.job.isActive) { queue?.close(); return }
        owner.queue = queue
        installPlaybackCoordinator(owner, backend, sessionCredentials, sessionScope)
    }

    private fun installPlaybackCoordinator(
        owner: HostSession, backend: String, credentials: RoomCredentials?, sessionScope: CoroutineScope,
    ) {
        val playback = if (credentials != null) playbackCoordinatorFactory?.invoke(
            backend, credentials,
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
        if (!owner.job.isActive) { playback?.close(); return }
        owner.playback = playback
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
        val current = state
        val sameInvite = when (current) {
            is HostingState.Invitation -> current.invite === invite && !current.foregroundRecoveryPending
            is HostingState.LiveRoom -> current.invite === invite &&
                current.synchronization !is RoomSyncState.Missing
            else -> false
        }
        return session === owner && owner.job.isActive && foreground &&
            owner.worker == null && owner.stopping == null && sameInvite
    }

    private fun publishRoomSynchronization(owner: HostSession, collectingJob: Job?, sync: RoomSyncState) {
        val invitation = state as? HostingState.Invitation
        if (invitation != null) {
            publishInvitationSynchronization(owner, collectingJob, sync, invitation)
            return
        }
        val current = state as? HostingState.LiveRoom ?: return
        if (current.synchronization is RoomSyncState.Missing) return
        if (!stillCollecting(owner, collectingJob, current.invite) || current.invite.code != sync.roomCode ||
            !hasMatchingRoomCode(current.invite.code, sync)) return
        if (sync is RoomSyncState.Missing) {
            handleMissingRoom(owner, current)
            return
        }
        val room = (sync as? RoomSyncState.Active)?.room
        if (room != null) {
            validateReplacement(owner, sync)
            recordObservedTrack(owner, room)
        }
        publish(current.copy(synchronization = retainLastKnownRoom(current.synchronization, sync)))
        followRoomSynchronization(owner, collectingJob, current, sync)
    }

    private fun publishInvitationSynchronization(
        owner: HostSession, job: Job?, sync: RoomSyncState, invitation: HostingState.Invitation,
    ) {
        if (!stillCollecting(owner, job, invitation.invite) ||
            invitation.invite.code != sync.roomCode || !hasMatchingRoomCode(invitation.invite.code, sync) ||
            invitation.foregroundRecoveryPending) return
        if (sync is RoomSyncState.Missing) handleMissingInvitation(owner, invitation)
        else (sync as? RoomSyncState.Active)?.room?.let {
            validateReplacement(owner, sync)
            recordObservedTrack(owner, it)
        }
    }

    private fun hasMatchingRoomCode(code: String, sync: RoomSyncState): Boolean =
        (sync as? RoomSyncState.Active)?.room?.code?.let { it == code } ?: true

    private fun followRoomSynchronization(
        owner: HostSession, job: Job?, previous: HostingState.LiveRoom, sync: RoomSyncState,
    ) {
        if (!stillCollecting(owner, job, previous.invite)) return
        owner.playback?.onSynchronization(sync)
        if (!stillCollecting(owner, job, previous.invite)) return
        val active = sync as? RoomSyncState.Active ?: return
        followFreshRoom(owner, previous, active)
    }

    private fun followFreshRoom(owner: HostSession, previous: HostingState.LiveRoom, active: RoomSyncState.Active) {
        val room = active.room ?: return
        if (active.freshness != Freshness.FRESH) return
        if (!previous.foregroundRecoveryPending) owner.queue?.onAuthoritativeRoom(room)
        else if (foreground && !owner.recovering) startForegroundRecovery(owner)
    }

    private fun validateReplacement(owner: HostSession, sync: RoomSyncState.Active) {
        if (sync.freshness == Freshness.FRESH && sync.room != null) {
            owner.unvalidatedAutomaticReplacement = false
        }
    }

    private fun recordObservedTrack(owner: HostSession, room: RoomState) {
        if (room.current != null || room.queue.isNotEmpty()) owner.observedTrack = true
    }

    private fun handleMissingInvitation(owner: HostSession, current: HostingState.Invitation) {
        if (session !== owner || state !== current) return
        if (!owner.observedTrack && !owner.unvalidatedAutomaticReplacement) {
            endRoomOnMutationContext(current.invite, automaticReplacement = true)
            return
        }
        publish(HostingState.LiveRoom(current.invite, RoomSyncState.Missing(current.invite.code)))
        if (session === owner && owner.job.isActive) owner.worker?.cancel()
    }

    private fun handleMissingRoom(owner: HostSession, current: HostingState.LiveRoom) {
        if (current.synchronization is RoomSyncState.Missing || session !== owner) return
        if (!owner.observedTrack && !owner.unvalidatedAutomaticReplacement) {
            endRoomOnMutationContext(current.invite, automaticReplacement = true)
            return
        }
        publish(current.copy(
            synchronization = RoomSyncState.Missing(current.invite.code),
            commandPending = false,
            foregroundRecoveryPending = false,
            invitationVisible = false,
        ))
        suspendMissingRoomCoordinators(owner)
    }

    private fun suspendMissingRoomCoordinators(owner: HostSession) {
        if (session !== owner || !owner.job.isActive) return
        owner.queue?.onForegroundLost()
        if (session !== owner || !owner.job.isActive) return
        owner.playback?.onForegroundLost()
        if (session === owner && owner.job.isActive) owner.worker?.cancel()
    }

    private fun stillCollecting(owner: HostSession, job: Job?, invite: GuestInvite): Boolean {
        val currentInvite = when (val current = state) {
            is HostingState.Invitation -> current.invite
            is HostingState.LiveRoom -> current.invite
            else -> null
        }
        return session === owner && owner.job.isActive && foreground && owner.worker === job &&
            currentInvite === invite
    }

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
                    if (foreground && session === owner) resumeAfterWorkerStopped(owner)
                }
            }
        }
    }

    private fun resumeAfterWorkerStopped(owner: HostSession) {
        val current = state
        val invite = when (current) {
            is HostingState.Invitation -> current.invite
            is HostingState.LiveRoom -> current.invite
            else -> return
        }
        if (pendingRecoveryInvite() === invite) startForegroundRecovery(owner)
        else owner.backendUrl?.let { startRoomCollection(owner, it, invite) }
    }

    fun onHostStopped(): Unit = queueMutationContext.run {
        if (!foreground) return@run
        foreground = false
        val owner = session ?: return@run
        when (val current = state) {
            is HostingState.Invitation -> stopInvitation(owner, current)
            is HostingState.LiveRoom -> if (current.synchronization !is RoomSyncState.Missing) {
                stopLiveRoom(owner, current)
            }
            else -> Unit
        }
    }

    private fun suspendMonitoring(owner: HostSession) {
        foreground = false
        stopWorker(owner)
    }

    private fun stopInvitation(owner: HostSession, current: HostingState.Invitation) {
        suspendMonitoring(owner)
        val latest = state as? HostingState.Invitation ?: return
        if (latest.invite === current.invite) publish(latest.copy(foregroundRecoveryPending = true))
    }

    private fun stopLiveRoom(owner: HostSession, current: HostingState.LiveRoom) {
        suspendMonitoring(owner)
        owner.queue?.onForegroundLost()
        if (state is HostingState.LiveRoom) owner.playback?.onForegroundLost()
        val latest = state as? HostingState.LiveRoom ?: return
        if (latest.invite !== current.invite) return
        publish(latest.copy(foregroundRecoveryPending = true, commandPending = false))
    }

    fun onHostStarted(): Unit = queueMutationContext.run {
        if (foreground) return@run
        foreground = true
        val replacement = deferredReplacement
        if (replacement != null && !endingRoom && session == null) {
            deferredReplacement = null
            beginCreate(EndpointSettings(setupState.backendUrl, setupState.guestOrigin),
                replacement.first, replacement.second)
        } else {
            session?.let(::startForegroundRecovery)
        }
    }

    private fun pendingRecoveryInvite(): GuestInvite? = when (val current = state) {
        is HostingState.Invitation -> current.invite.takeIf { current.foregroundRecoveryPending }
        is HostingState.LiveRoom -> current.invite.takeIf { current.foregroundRecoveryPending }
        else -> null
    }

    private fun canRecover(owner: HostSession): Boolean =
        session === owner && owner.job.isActive && foreground && owner.stopping == null

    private fun startForegroundRecovery(owner: HostSession) {
        if (!canRecover(owner)) return
        val invite = pendingRecoveryInvite() ?: return
        val backend = owner.backendUrl ?: return
        val fetch = foregroundReconcilerFactory?.invoke(backend) ?: return
        if (!canRecover(owner) || pendingRecoveryInvite() !== invite) return
        if (owner.worker != null) {
            if (!owner.recovering) stopWorker(owner)
            return
        }
        val code = invite.code
        val scope = CoroutineScope(ownerScope.coroutineContext + owner.job)
        val job = scope.launch(foregroundRecoveryContext, start = CoroutineStart.LAZY) {
            runForegroundRecovery(owner, fetch, code)
        }
        owner.recovering = true
        owner.worker = job
        if (foreground) job.start() else job.cancel()
    }

    private suspend fun runForegroundRecovery(
        owner: HostSession, fetch: suspend (String) -> RoomFetchResult, code: String,
    ) {
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

    private fun completeForegroundRecovery(owner: HostSession, job: Job?, code: String, result: RoomFetchResult) {
        val invitation = state as? HostingState.Invitation
        if (invitation != null) {
            completeInvitationRecovery(owner, job, code, result, invitation)
            return
        }
        val current = state as? HostingState.LiveRoom ?: return
        if (!isCurrentRecovery(owner, job, current.invite, code)) return
        owner.worker = null
        owner.recovering = false
        when (result) {
            RoomFetchResult.Missing -> handleMissingRoom(owner, current)
            is RoomFetchResult.Success -> if (!applyRecoveredRoom(owner, current, code, result.room)) return
            RoomFetchResult.Failure -> Unit
        }
        if (result != RoomFetchResult.Missing) {
            owner.backendUrl?.let { startRoomCollection(owner, it, current.invite) }
        }
    }

    private fun completeInvitationRecovery(
        owner: HostSession, job: Job?, code: String, result: RoomFetchResult,
        invitation: HostingState.Invitation,
    ) {
        if (!isCurrentRecovery(owner, job, invitation.invite, code)) return
        owner.worker = null
        owner.recovering = false
        when (result) {
            RoomFetchResult.Missing -> { handleMissingInvitation(owner, invitation); return }
            is RoomFetchResult.Success -> {
                if (result.room.code == code) {
                    owner.unvalidatedAutomaticReplacement = false
                    recordObservedTrack(owner, result.room)
                }
            }
            RoomFetchResult.Failure -> Unit
        }
        if (session === owner && state === invitation) {
            publish(invitation.copy(foregroundRecoveryPending = false))
            owner.backendUrl?.let { startRoomCollection(owner, it, invitation.invite) }
        }
    }

    private fun isCurrentRecovery(owner: HostSession, job: Job?, invite: GuestInvite, code: String): Boolean =
        session === owner && foreground && owner.job.isActive && owner.worker === job &&
            pendingRecoveryInvite() === invite && invite.code == code

    private fun applyRecoveredRoom(owner: HostSession, previous: HostingState.LiveRoom, code: String, room: RoomState): Boolean {
        if (room.code != code) return true
        owner.unvalidatedAutomaticReplacement = false
        recordObservedTrack(owner, room)
        val connection = (previous.synchronization as? RoomSyncState.Active)?.connection ?: LiveConnection.CONNECTING
        publish(previous.copy(
            synchronization = RoomSyncState.Active(code, room, Freshness.FRESH, connection),
            foregroundRecoveryPending = false,
        ))
        // A collector or coordinator can synchronously stop/restart this session.
        if (!stillRecovered(owner, previous.invite, room)) return false
        owner.queue?.onForegroundReconciled(room)
        if (!stillRecovered(owner, previous.invite, room)) return false
        owner.playback?.onForegroundReconciled(room)
        return stillRecovered(owner, previous.invite, room)
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
        if ((state as? HostingState.LiveRoom)?.synchronization is RoomSyncState.Missing) return@run false
        session?.queue?.onPlaybackEnded(trackId) == true
    }
    private fun playableSession(): HostSession? =
        if ((state as? HostingState.LiveRoom)?.synchronization is RoomSyncState.Missing) null else session

    override fun onPlayPause(): Unit = queueMutationContext.run { playableSession()?.playback?.togglePlayPause(); Unit }
    override fun onSeekBy(offsetMs: Long): Unit = queueMutationContext.run { playableSession()?.playback?.seekBy(offsetMs); Unit }
    override fun onPlay() = resumePlayback()
    override fun onPause() = pausePlayback()
    fun pausePlayback(): Unit = queueMutationContext.run { playableSession()?.playback?.pause(); Unit }
    fun resumePlayback(): Unit = queueMutationContext.run { playableSession()?.playback?.resume(); Unit }
    override fun onRetryCurrent() = retryCurrent()
    fun retryCurrent(): Unit = queueMutationContext.run { playableSession()?.playback?.retryCurrent(); Unit }
    override fun onInvite(): Unit = queueMutationContext.run {
        val current = state as? HostingState.LiveRoom ?: return@run
        if (current.synchronization is RoomSyncState.Missing) return@run
        if (!current.invitationVisible) publish(current.copy(invitationVisible = true))
    }
    override fun onNewRoom(): Unit = queueMutationContext.run {
        if (endingRoom) return@run
        val current = state as? HostingState.LiveRoom ?: return@run
        if (current.synchronization !is RoomSyncState.Missing) return@run
        endRoomOnMutationContext(current.invite, suppressBackendClose = true)
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
    /** Activity destruction is not an explicit host end (for example, task removal). */
    fun abandonRoom(): Unit = queueMutationContext.run {
        endRoomOnMutationContext(suppressBackendClose = true)
    }

    private fun endRoomOnMutationContext(
        replacementInvite: GuestInvite? = null, automaticReplacement: Boolean = false,
        suppressBackendClose: Boolean = false,
    ) {
        if (endingRoom) return
        val missing = suppressBackendClose || (state as? HostingState.LiveRoom)?.synchronization is RoomSyncState.Missing
        endingRoom = true
        deferredReplacement = null
        val detached = session
        session = null
        publish(HostingState.Ending)
        detached?.job?.cancel()
        runCatching { detached?.queue?.close() }
        runCatching { detached?.playback?.close() }
        // #259: only an explicit end of an existing Invitation/LiveRoom closes the backend.
        // Joining the process-owned final PAUSED Job precedes DELETE; the Setup finalizer
        // below never waits for either network operation.
        if (!automaticReplacement && !missing) scheduleBackendClose(detached)
        // Never wait on a child in its reducer or publication callback. This sibling finalizer
        // waits for the entire detached tree before admitting a replacement creation.
        ownerScope.launch(Dispatchers.IO) {
            detached?.job?.join()
            queueMutationContext.runFromWorker {
                if (endingRoom && state === HostingState.Ending) {
                    endingRoom = false
                    if (replacementInvite == null) publish(setupState)
                    else if (foreground) beginCreate(
                        EndpointSettings(setupState.backendUrl, setupState.guestOrigin),
                        replacementInvite, automaticReplacement,
                    ) else deferredReplacement = replacementInvite to automaticReplacement
                }
            }
        }
    }

    private fun scheduleBackendClose(detached: HostSession?) {
        val credentials = detached?.credentials ?: return
        val backend = detached.backendUrl ?: return
        closeBackendRoom(backend, credentials, detached.job, detached.playback)
    }

    private fun closeBackendRoom(
        backend: String, credentials: RoomCredentials, sessionJob: Job,
        playback: AuthoritativePlaybackCoordinator?,
    ) {
        val finalPause = playback?.finalPauseCompletion()
        closeScope.launch(Dispatchers.IO) {
            try {
                // Cancelled session reports and any process-owned final PAUSED must settle
                // before DELETE. If either ignores cancellation, leave the room to TTL.
                withTimeout(2_500L) {
                    sessionJob.join()
                    finalPause?.join()
                }
                if (playback?.canCloseRoomAfterFinalPause() == false) {
                    roomApiLogger.warn(QMixLogOperation.DELETE_ROOM, QMixLogCause.NETWORK)
                    return@launch
                }
                withTimeout(3_000L) {
                    roomCloseCommandFactory(backend).close(credentials.code, credentials.hostToken)
                }
            } catch (failure: Exception) {
                roomApiLogger.warn(QMixLogOperation.DELETE_ROOM,
                    if (failure is RoomApiException) failure.logCause else QMixLogCause.NETWORK)
            }
        }
    }
}
