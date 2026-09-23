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

    private var credentials: RoomCredentials? = null
    private var activeBackendUrl: String? = null
    private var createJob: Job? = null
    private var sessionJob: Job? = null
    private var roomCollectionJob: Job? = null
    private var queueCoordinator: QueueAdvancementCoordinator? = null
    private var playbackCoordinator: AuthoritativePlaybackCoordinator? = null
    private var foreground = true
    private var foregroundRecoveryJob: Job? = null
    private var httpAcknowledgementQuarantined = false
    private var endingRoom = false
    private fun publish(newState: HostingState) { mutableStates.value = newState }
    private fun deliverCoordinatorUpdate(owner: Job, action: () -> Unit) {
        if (queueMutationContext.isOnContext()) {
            if (sessionJob === owner) action()
        } else CoroutineScope(ownerScope.coroutineContext + owner + roomCollectionContext).launch {
            queueMutationContext.runFromWorker { if (sessionJob === owner) action() }
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
        val job = ownerScope.launch(roomCollectionContext, start = CoroutineStart.LAZY) {
            val executingJob = coroutineContext[Job]
            try {
                val created = RoomApiClient(httpClient, settings.backendUrl, roomApiLogger).createRoom()
                val invite = GuestInvite.create(created, settings.guestOrigin)
                val stillPending = queueMutationContext.runFromWorker {
                    createJob === executingJob && state is HostingState.Pending
                }
                if (!stillPending) return@launch
                // Persistence can block on disk. Keep it off the mutation lane so endRoom
                // can detach/cancel this worker; revalidate admission after save returns.
                settingsPersistence.save(settings)
                coroutineContext.ensureActive()
                queueMutationContext.runFromWorker {
                    if (createJob !== executingJob || state !is HostingState.Pending) return@runFromWorker
                    credentials = created
                    activeBackendUrl = settings.backendUrl
                    publish(HostingState.Invitation(invite))
                    if (createJob === executingJob) createJob = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RoomApiException) {
                createError(executingJob, settings, error.userMessage)
            } catch (_: IllegalArgumentException) {
                createError(executingJob, settings, UserMessage.INVALID_ENDPOINT)
            } catch (_: IllegalStateException) {
                createError(executingJob, settings, UserMessage.PERSISTENCE_ERROR)
            }
        }
        createJob = job
        publish(HostingState.Pending(settings.backendUrl, settings.guestOrigin))
        // A reentrant collector can end the session during Pending publication.
        if (createJob === job) job.start() else job.cancel()
    }

    private suspend fun createError(job: Job?, settings: EndpointSettings, message: UserMessage) = queueMutationContext.runFromWorker {
        if (createJob === job && job?.isActive == true && state is HostingState.Pending) {
            publish(HostingState.Error(message, settings.backendUrl, settings.guestOrigin))
            if (createJob === job) createJob = null
        }
    }

    fun enterRoom(): Unit = queueMutationContext.run {
        val invitation = state as? HostingState.Invitation ?: return@run
        val invite = invitation.invite
        val backend = activeBackendUrl
        val owner = SupervisorJob(ownerScope.coroutineContext[Job])
        sessionJob = owner
        publish(HostingState.LiveRoom(
            invite, RoomSyncState.Active(invite.code, null, Freshness.LOADING, LiveConnection.CONNECTING),
        ))
        if (sessionJob !== owner) return@run
        if (backend == null) return@run
        val sessionCredentials = credentials
        // Transport uses the worker context; all publications re-enter the mutation context.
        // The detached owner is joined only by the off-dispatcher finalizer.
        val sessionScope = CoroutineScope(ownerScope.coroutineContext + owner + roomCollectionContext)
        queueCoordinator = if (sessionCredentials != null) queueCoordinatorFactory?.invoke(backend, sessionCredentials, { update ->
            deliverCoordinatorUpdate(owner) delivery@{
                val current = state as? HostingState.LiveRoom ?: return@delivery
                if (sessionJob === owner && queueCoordinator?.state === update && current.commandPending != update.pending) {
                    publish(current.copy(commandPending = update.pending))
                }
            }
        }, sessionScope) else null
        if (sessionJob !== owner) { queueCoordinator?.close(); queueCoordinator = null; return@run }
        playbackCoordinator = if (sessionCredentials != null) playbackCoordinatorFactory?.invoke(
            backend, sessionCredentials,
            { playback ->
                deliverCoordinatorUpdate(owner) delivery@{
                    val current = state as? HostingState.LiveRoom ?: return@delivery
                    if (sessionJob === owner && current.playback != playback) publish(current.copy(playback = playback))
                }
            },
            { trackId -> queueMutationContext.run {
                if (sessionJob === owner) queueCoordinator?.onPlaybackEnded(trackId) == true else false
            } }, sessionScope,
        ) else null
        if (sessionJob !== owner) { playbackCoordinator?.close(); playbackCoordinator = null; return@run }
        startRoomCollection(owner, backend, invite)
    }

    private fun startRoomCollection(owner: Job, backend: String, invite: GuestInvite) {
        if (sessionJob !== owner || !foreground) return
        val repository = roomRepositoryFactory?.invoke(backend) ?: return
        if (sessionJob !== owner || !foreground) return
        val scope = CoroutineScope(ownerScope.coroutineContext + owner)
        val job = scope.launch(roomCollectionContext, start = CoroutineStart.LAZY) {
            repository.observe(invite.code).collect { sync ->
                val collectingJob = coroutineContext[Job]
                queueMutationContext.runFromWorker {
                    if (roomCollectionJob === collectingJob) publishRoomSynchronization(owner, sync)
                }
            }
        }
        roomCollectionJob = job
        if (sessionJob === owner && foreground && roomCollectionJob === job) job.start() else job.cancel()
    }

    private fun publishRoomSynchronization(owner: Job, sync: RoomSyncState) {
        val current = state as? HostingState.LiveRoom ?: return
        if (sessionJob !== owner || current.invite.code != sync.roomCode) return
        publish(current.copy(synchronization = retainLastKnownRoom(current.synchronization, sync)))
        if (sessionJob !== owner) return
        playbackCoordinator?.onSynchronization(sync)
        if (sessionJob !== owner) return
        val active = sync as? RoomSyncState.Active
        if (!current.foregroundRecoveryPending && active?.freshness == Freshness.FRESH && active.room != null) {
            queueCoordinator?.onAuthoritativeRoom(active.room)
        } else if (current.foregroundRecoveryPending && foreground && active?.freshness == Freshness.FRESH &&
            active.room != null && foregroundRecoveryJob == null
        ) {
            startForegroundRecovery(owner)
        }
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

    private var stoppingCollection: Job? = null
    private var stoppingRecovery: Job? = null

    private fun resumeAfterForegroundCleanup(owner: Job?) {
        if (foreground && owner != null && sessionJob === owner &&
            stoppingCollection == null && stoppingRecovery == null) startForegroundRecovery(owner)
    }

    fun onHostStopped(): Unit = queueMutationContext.run {
        val current = state as? HostingState.LiveRoom ?: return@run
        val owner = sessionJob
        if (!foreground) return@run
        foreground = false
        val recovery = foregroundRecoveryJob
        foregroundRecoveryJob = null
        val collection = roomCollectionJob
        roomCollectionJob = null
        if (collection != null) stoppingCollection = collection
        if (recovery != null) stoppingRecovery = recovery
        recovery?.cancel()
        collection?.cancel()
        queueCoordinator?.onForegroundLost()
        if (sessionJob === owner) playbackCoordinator?.onForegroundLost()
        if (sessionJob !== owner) return@run
        val latest = state as? HostingState.LiveRoom ?: return@run
        if (latest.invite !== current.invite) return@run
        publish(latest.copy(foregroundRecoveryPending = true, commandPending = false))
        if (collection != null) ownerScope.launch(Dispatchers.IO) {
            collection.join()
            queueMutationContext.runFromWorker {
                if (stoppingCollection === collection) {
                    stoppingCollection = null
                    resumeAfterForegroundCleanup(owner)
                }
            }
        }
        if (recovery != null) ownerScope.launch(Dispatchers.IO) {
            recovery.join()
            queueMutationContext.runFromWorker {
                if (stoppingRecovery === recovery) {
                    stoppingRecovery = null
                    resumeAfterForegroundCleanup(owner)
                }
            }
        }
    }

    fun onHostStarted(): Unit = queueMutationContext.run {
        if (foreground) return@run
        foreground = true
        if (stoppingCollection == null && stoppingRecovery == null) sessionJob?.let(::startForegroundRecovery)
    }

    private fun startForegroundRecovery(owner: Job) {
        val current = state as? HostingState.LiveRoom ?: return
        if (sessionJob !== owner || !foreground || stoppingCollection != null || stoppingRecovery != null ||
            !current.foregroundRecoveryPending || foregroundRecoveryJob != null) return
        val backend = activeBackendUrl ?: return
        val fetch = foregroundReconcilerFactory?.invoke(backend) ?: return
        val collection = roomCollectionJob
        if (collection != null) {
            roomCollectionJob = null
            stoppingCollection = collection
            collection.cancel()
            ownerScope.launch(Dispatchers.IO) {
                collection.join()
                queueMutationContext.runFromWorker {
                    if (stoppingCollection === collection) {
                        stoppingCollection = null
                        resumeAfterForegroundCleanup(owner)
                    }
                }
            }
            return
        }
        val code = current.invite.code
        val scope = CoroutineScope(ownerScope.coroutineContext + owner)
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
        foregroundRecoveryJob = job
        if (sessionJob === owner && foreground && foregroundRecoveryJob === job) job.start() else job.cancel()
    }

    private fun completeForegroundRecovery(owner: Job, job: Job?, code: String, result: RoomFetchResult) {
        val current = state as? HostingState.LiveRoom ?: return
        if (!foreground || sessionJob !== owner || foregroundRecoveryJob !== job || current.invite.code != code) return
        foregroundRecoveryJob = null
        when (result) {
            RoomFetchResult.Missing -> publish(current.copy(synchronization = RoomSyncState.Missing(code)))
            is RoomFetchResult.Success -> if (result.room.code == code) {
                val connection = (current.synchronization as? RoomSyncState.Active)?.connection ?: LiveConnection.CONNECTING
                publish(current.copy(
                    synchronization = RoomSyncState.Active(code, result.room, Freshness.FRESH, connection),
                    foregroundRecoveryPending = false,
                ))
                // StateFlow collectors and coordinator callbacks can synchronously stop/end this room.
                fun stillRecovered(): Boolean {
                    val latest = state as? HostingState.LiveRoom ?: return false
                    val sync = latest.synchronization as? RoomSyncState.Active ?: return false
                    return sessionJob === owner && foreground && latest.invite === current.invite &&
                        !latest.foregroundRecoveryPending && sync.room === result.room &&
                        sync.freshness == Freshness.FRESH
                }
                if (!stillRecovered()) return
                queueCoordinator?.onForegroundReconciled(result.room)
                if (!stillRecovered()) return
                playbackCoordinator?.onForegroundReconciled(result.room)
            }
            RoomFetchResult.Failure -> Unit
        }
        if (sessionJob === owner && foreground && result != RoomFetchResult.Missing) {
            activeBackendUrl?.let { startRoomCollection(owner, it, current.invite) }
        }
    }

    override fun onStartOrNext(): Unit = queueMutationContext.run {
        if (endingRoom) return@run
        if ((state as? HostingState.LiveRoom)?.isPrimaryActionEnabled != true) return@run
        val coordinator = queueCoordinator
        if (coordinator != null) coordinator.requestExplicitAdvance() else primaryActionHandler()
    }
    fun onPlaybackEnded(trackId: String): Boolean = queueMutationContext.run {
        queueCoordinator?.onPlaybackEnded(trackId) == true
    }
    override fun onPlayPause(): Unit = queueMutationContext.run { playbackCoordinator?.togglePlayPause(); Unit }
    override fun onSeekBy(offsetMs: Long): Unit = queueMutationContext.run { playbackCoordinator?.seekBy(offsetMs); Unit }
    override fun onPlay() = resumePlayback()
    override fun onPause() = pausePlayback()
    fun pausePlayback(): Unit = queueMutationContext.run { playbackCoordinator?.pause(); Unit }
    fun resumePlayback(): Unit = queueMutationContext.run { playbackCoordinator?.resume(); Unit }
    override fun onRetryCurrent() = retryCurrent()
    fun retryCurrent(): Unit = queueMutationContext.run { playbackCoordinator?.retryCurrent(); Unit }
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
        val create = createJob
        val owner = sessionJob
        val collection = roomCollectionJob
        val recovery = foregroundRecoveryJob
        val stopping = stoppingCollection
        val stoppingFetch = stoppingRecovery
        val queue = queueCoordinator
        val playback = playbackCoordinator
        createJob = null
        sessionJob = null
        roomCollectionJob = null
        foregroundRecoveryJob = null
        stoppingCollection = null
        stoppingRecovery = null
        queueCoordinator = null
        playbackCoordinator = null
        credentials = null
        activeBackendUrl = null
        foreground = true
        publish(HostingState.Ending)
        create?.cancel()
        owner?.cancel()
        collection?.cancel()
        recovery?.cancel()
        stopping?.cancel()
        stoppingFetch?.cancel()
        runCatching { queue?.close() }
        runCatching { playback?.close() }
        // Never wait on a child in its reducer or publication callback. This sibling finalizer
        // waits for the entire detached tree and only then reopens admission on the reducer.
        ownerScope.launch(Dispatchers.IO) {
            listOfNotNull(create, owner, collection, recovery, stopping, stoppingFetch).forEach { it.join() }
            queueMutationContext.runFromWorker {
                if (endingRoom && state === HostingState.Ending) {
                    endingRoom = false
                    publish(setupState)
                }
            }
        }
    }
}
