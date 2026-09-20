package com.qmix.tv

import androidx.annotation.StringRes

internal fun handleLiveRoomBack(handler: LiveRoomHandler, onExitLiveRoom: () -> Unit) {
    if (handler.onBack() == LiveRoomBackResult.EXIT_ACTIVITY) {
        onExitLiveRoom()
    }
}

internal fun formatDuration(durationSeconds: Int, unknownDuration: String): String {
    if (durationSeconds <= 0) return unknownDuration
    val hours = durationSeconds / 3600
    val minutes = (durationSeconds % 3600) / 60
    val seconds = durationSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

@StringRes
internal fun localPlaybackStatusResource(status: LocalPlaybackStatus): Int = when (status) {
    LocalPlaybackStatus.IDLE -> R.string.playback_status_idle
    LocalPlaybackStatus.BUFFERING -> R.string.playback_status_buffering
    LocalPlaybackStatus.PLAYING -> R.string.playback_status_playing
    LocalPlaybackStatus.PAUSED -> R.string.playback_status_paused
    LocalPlaybackStatus.COMPLETED -> R.string.playback_status_completed
    LocalPlaybackStatus.ERROR -> R.string.playback_status_error
}

internal data class FormattedText(@param:StringRes val resource: Int, val argument: Int? = null)

internal fun localPlaybackErrorText(error: PlaybackError): FormattedText = when (error.kind) {
    PlaybackErrorKind.HTTP -> error.httpResponseCode?.let {
        FormattedText(R.string.playback_stream_request_failed_http, it)
    } ?: FormattedText(R.string.playback_stream_request_failed)
    PlaybackErrorKind.RANGE -> error.httpResponseCode?.let {
        FormattedText(R.string.playback_stream_seek_failed_http, it)
    } ?: FormattedText(R.string.playback_stream_seek_failed)
    PlaybackErrorKind.DECODE -> FormattedText(R.string.playback_decode_failed)
    PlaybackErrorKind.NETWORK -> FormattedText(R.string.playback_network_interrupted)
    PlaybackErrorKind.UNKNOWN -> FormattedText(R.string.playback_unknown_failed)
}

@StringRes
internal fun synchronizationMessageResource(synchronization: RoomSyncState): Int? = when (synchronization) {
    is RoomSyncState.Missing -> R.string.sync_room_missing
    is RoomSyncState.Active -> when {
        synchronization.connection == LiveConnection.CONNECTING -> R.string.sync_connecting
        synchronization.connection == LiveConnection.RECONNECTING && synchronization.room != null ->
            R.string.sync_reconnecting_with_room
        synchronization.connection == LiveConnection.RECONNECTING -> R.string.sync_reconnecting
        synchronization.freshness == Freshness.STALE && synchronization.room != null ->
            R.string.sync_stale_with_room
        synchronization.freshness == Freshness.STALE -> R.string.sync_refresh_failed
        synchronization.freshness == Freshness.LOADING -> R.string.sync_waiting
        else -> null
    }
}

enum class UserMessage {
    INVALID_ENDPOINT,
    PERSISTENCE_ERROR,
    REQUEST_FAILED,
    INVALID_RESPONSE,
    SERVER_TIMEOUT,
    SERVER_UNREACHABLE,
    HOST_ACCESS_DENIED,
    ROOM_NOT_FOUND,
    SERVER_UNAVAILABLE,
    REQUEST_REJECTED,
}

@StringRes
internal fun UserMessage.resourceId(): Int = when (this) {
    UserMessage.INVALID_ENDPOINT -> R.string.error_invalid_endpoint
    UserMessage.PERSISTENCE_ERROR -> R.string.error_persistence
    UserMessage.REQUEST_FAILED -> R.string.error_request_failed
    UserMessage.INVALID_RESPONSE -> R.string.error_invalid_response
    UserMessage.SERVER_TIMEOUT -> R.string.error_server_timeout
    UserMessage.SERVER_UNREACHABLE -> R.string.error_server_unreachable
    UserMessage.HOST_ACCESS_DENIED -> R.string.error_host_access_denied
    UserMessage.ROOM_NOT_FOUND -> R.string.error_room_not_found
    UserMessage.SERVER_UNAVAILABLE -> R.string.error_server_unavailable
    UserMessage.REQUEST_REJECTED -> R.string.error_request_rejected
}
