package com.qmix.tv

internal fun handleLiveRoomBack(handler: LiveRoomHandler, onExitLiveRoom: () -> Unit) {
    if (handler.onBack() == LiveRoomBackResult.EXIT_ACTIVITY) {
        onExitLiveRoom()
    }
}

internal fun formatDuration(durationSeconds: Int): String {
    if (durationSeconds <= 0) return "Duration unknown"
    val hours = durationSeconds / 3600
    val minutes = (durationSeconds % 3600) / 60
    val seconds = durationSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

internal fun localPlaybackStatusLabel(status: LocalPlaybackStatus): String = when (status) {
    LocalPlaybackStatus.IDLE -> "Idle"
    LocalPlaybackStatus.BUFFERING -> "Buffering"
    LocalPlaybackStatus.PLAYING -> "Playing"
    LocalPlaybackStatus.PAUSED -> "Paused"
    LocalPlaybackStatus.COMPLETED -> "Completed"
    LocalPlaybackStatus.ERROR -> "Error"
}

internal fun localPlaybackErrorMessage(error: PlaybackError): String = when (error.kind) {
    PlaybackErrorKind.HTTP -> error.httpResponseCode?.let { "Stream request failed (HTTP $it)." }
        ?: "Stream request failed."
    PlaybackErrorKind.RANGE -> error.httpResponseCode?.let { "Stream seek failed (HTTP $it)." }
        ?: "Stream seek failed."
    PlaybackErrorKind.DECODE -> "Audio format could not be played."
    PlaybackErrorKind.NETWORK -> "Network connection interrupted."
    PlaybackErrorKind.UNKNOWN -> "Playback failed unexpectedly."
}

internal fun synchronizationMessage(synchronization: RoomSyncState): String? = when (synchronization) {
    is RoomSyncState.Missing -> "Room not found."
    is RoomSyncState.Active -> when {
        synchronization.connection == LiveConnection.CONNECTING -> "Connecting to room…"
        synchronization.connection == LiveConnection.RECONNECTING && synchronization.room != null ->
            "Reconnecting… Showing last known room."
        synchronization.connection == LiveConnection.RECONNECTING -> "Reconnecting…"
        synchronization.freshness == Freshness.STALE && synchronization.room != null ->
            "Updates are stale. Showing last known room."
        synchronization.freshness == Freshness.STALE -> "Could not refresh the room."
        synchronization.freshness == Freshness.LOADING -> "Waiting for room data…"
        else -> null
    }
}
