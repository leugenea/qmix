package com.qmix.tv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.ArrayDeque
import java.util.concurrent.Executor

sealed interface HostingState {
    data class Setup(val backendUrl: String, val guestOrigin: String) : HostingState
    data class Pending(val backendUrl: String, val guestOrigin: String) : HostingState
    data class Invitation(val invite: GuestInvite) : HostingState
    data class LiveRoom(
        val invite: GuestInvite,
        val synchronization: RoomSyncState,
        val commandPending: Boolean = false,
        val invitationVisible: Boolean = false,
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
                    active.room?.queue?.isNotEmpty() == true &&
                    active.freshness == Freshness.FRESH &&
                    active.connection == LiveConnection.CONNECTED
            }
    }
    data class Error(val message: String, val backendUrl: String, val guestOrigin: String) : HostingState
}

enum class LiveRoomPrimaryAction { START, NEXT }

enum class LiveRoomBackResult { HANDLED, EXIT_ACTIVITY, IGNORED }

interface LiveRoomHandler {
    fun onStartOrNext()
    fun onInvite()
    fun onBack(): LiveRoomBackResult
}

class HostSessionController(
    private val httpClient: OkHttpClient,
    initialBackendUrl: String = "https://qmix.example",
    initialGuestOrigin: String = initialBackendUrl,
    private val executor: Executor = Executor { command ->
        Thread(command, "qmix-room-request").apply { isDaemon = true }.start()
    },
    private val roomRepositoryFactory: ((String) -> RoomRepository)? = null,
    private val primaryActionHandler: () -> Unit = {},
    private val observerFailureHandler: (Throwable) -> Unit = {},
    private val logger: QMixComponentLogger = QMixComponentLogger.noOp(QMixLogComponent.APP_HOST_SESSION),
    private val roomApiLogger: QMixComponentLogger = QMixComponentLogger.noOp(QMixLogComponent.ROOM_API_CREATION),
) : LiveRoomHandler {
    private data class Notification(
        val state: HostingState,
        val recipients: List<(HostingState) -> Unit>,
    )

    private val observers = linkedSetOf<(HostingState) -> Unit>()
    private val notifications = ArrayDeque<Notification>()
    private var deliveringNotifications = false
    private var setupState = HostingState.Setup(initialBackendUrl, initialGuestOrigin)
    private var credentials: RoomCredentials? = null
    private var activeBackendUrl: String? = null
    private var roomSubscription: AutoCloseable? = null
    private var createGeneration = 0L
    private var syncGeneration = 0L

    @Volatile
    var state: HostingState = HostingState.Setup(initialBackendUrl, initialGuestOrigin)
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
        lateinit var settings: HostingState.Setup
        var generation = 0L
        var accepted = false
        synchronized(this) {
            settings = when (val current = state) {
                is HostingState.Setup -> current
                is HostingState.Error -> HostingState.Setup(current.backendUrl, current.guestOrigin)
                else -> return false
            }
            if (settings.backendUrl.toHttpUrlOrNull() == null || settings.guestOrigin.toHttpUrlOrNull() == null) {
                publishLocked(
                    HostingState.Error(
                        "Enter valid absolute http(s) URLs.",
                        settings.backendUrl,
                        settings.guestOrigin,
                    ),
                )
            } else {
                setupState = settings
                generation = ++createGeneration
                publishLocked(HostingState.Pending(settings.backendUrl, settings.guestOrigin))
                accepted = true
            }
        }
        drainNotifications()
        if (!accepted) return false

        executor.execute {
            try {
                val created = RoomApiClient(httpClient, settings.backendUrl, roomApiLogger).createRoom()
                val changed = synchronized(this) {
                    if (generation != createGeneration) {
                        false
                    } else {
                        credentials = created
                        activeBackendUrl = settings.backendUrl
                        publishLocked(HostingState.Invitation(GuestInvite.create(created, settings.guestOrigin)))
                        true
                    }
                }
                if (changed) drainNotifications()
            } catch (error: RoomApiException) {
                publishCreateError(generation, settings, error.message ?: "The request failed.")
            } catch (_: IllegalArgumentException) {
                publishCreateError(generation, settings, "Enter valid absolute http(s) URLs.")
            }
        }
        return true
    }

    private fun publishCreateError(generation: Long, settings: HostingState.Setup, message: String) {
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
        var factory: ((String) -> RoomRepository)? = null
        var backendUrl: String? = null
        var previousSubscription: AutoCloseable? = null
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
            factory = roomRepositoryFactory
            backendUrl = activeBackendUrl
            if (factory != null && backendUrl != null) {
                previousSubscription = roomSubscription
                roomSubscription = null
                generation = ++syncGeneration
            }
        }
        drainNotifications()
        previousSubscription?.close()

        val repositoryFactory = factory ?: return
        val activeUrl = backendUrl ?: return
        val subscription = repositoryFactory(activeUrl).observe(invite.code) { syncState ->
            val changed = synchronized(this@HostSessionController) {
                val current = state as? HostingState.LiveRoom
                if (generation != syncGeneration || current?.invite?.code != syncState.roomCode) {
                    false
                } else {
                    publishLocked(current.copy(synchronization = retainLastKnownRoom(current.synchronization, syncState)))
                    true
                }
            }
            if (changed) drainNotifications()
        }
        val closeImmediately = synchronized(this) {
            val current = state as? HostingState.LiveRoom
            if (generation == syncGeneration && current?.invite?.code == invite.code) {
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

    override fun onStartOrNext() {
        val enabled = synchronized(this) {
            (state as? HostingState.LiveRoom)?.isPrimaryActionEnabled == true
        }
        if (enabled) primaryActionHandler()
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
        val subscription = synchronized(this) {
            createGeneration++
            syncGeneration++
            val owned = roomSubscription
            roomSubscription = null
            credentials = null
            activeBackendUrl = null
            publishLocked(setupState)
            owned
        }
        try {
            subscription?.close()
        } finally {
            drainNotifications()
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
