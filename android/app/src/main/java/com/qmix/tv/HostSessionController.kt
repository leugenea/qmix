package com.qmix.tv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

sealed interface HostingState {
    data class Setup(val backendUrl: String, val guestOrigin: String) : HostingState
    data class Pending(val backendUrl: String, val guestOrigin: String) : HostingState
    data class Invitation(val invite: GuestInvite) : HostingState
    data class RoomPlaceholder(val code: String) : HostingState
    data class Error(val message: String, val backendUrl: String, val guestOrigin: String) : HostingState
}

class HostSessionController(
    private val httpClient: OkHttpClient,
    initialBackendUrl: String = "https://qmix.example",
    initialGuestOrigin: String = initialBackendUrl,
    private val executor: Executor = Executor { command ->
        Thread(command, "qmix-room-request").apply { isDaemon = true }.start()
    },
) {
    private val observers = CopyOnWriteArrayList<(HostingState) -> Unit>()
    private var credentials: RoomCredentials? = null

    @Volatile
    var state: HostingState = HostingState.Setup(initialBackendUrl, initialGuestOrigin)
        private set

    fun observe(observer: (HostingState) -> Unit): AutoCloseable {
        observers += observer
        observer(state)
        return AutoCloseable { observers -= observer }
    }

    @Synchronized
    fun updateSettings(backendUrl: String, guestOrigin: String) {
        if (state is HostingState.Setup || state is HostingState.Error) {
            publish(HostingState.Setup(backendUrl, guestOrigin))
        }
    }

    @Synchronized
    fun createRoom(): Boolean {
        val settings = when (val current = state) {
            is HostingState.Setup -> current
            is HostingState.Error -> HostingState.Setup(current.backendUrl, current.guestOrigin)
            else -> return false
        }
        if (settings.backendUrl.toHttpUrlOrNull() == null || settings.guestOrigin.toHttpUrlOrNull() == null) {
            publish(HostingState.Error("Enter valid absolute http(s) URLs.", settings.backendUrl, settings.guestOrigin))
            return false
        }
        publish(HostingState.Pending(settings.backendUrl, settings.guestOrigin))
        executor.execute {
            try {
                val created = RoomApiClient(httpClient, settings.backendUrl).createRoom()
                credentials = created
                publish(HostingState.Invitation(GuestInvite.create(created, settings.guestOrigin)))
            } catch (error: RoomApiException) {
                publish(
                    HostingState.Error(
                        error.message ?: "The request failed.",
                        settings.backendUrl,
                        settings.guestOrigin,
                    ),
                )
            } catch (_: IllegalArgumentException) {
                publish(HostingState.Error("Enter valid absolute http(s) URLs.", settings.backendUrl, settings.guestOrigin))
            }
        }
        return true
    }

    fun enterRoom() {
        val invitation = state as? HostingState.Invitation ?: return
        publish(HostingState.RoomPlaceholder(invitation.invite.code))
    }

    private fun publish(newState: HostingState) {
        state = newState
        observers.forEach { it(newState) }
    }
}
