package com.qmix.tv

data class PlaybackMedia(
    val trackId: String,
    val streamUrl: String,
)

enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR,
    RELEASED,
}

data class PlaybackState(
    val mediaId: String? = null,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val isSeekable: Boolean = false,
    val error: PlaybackError? = null,
)

data class PlaybackError(
    val kind: PlaybackErrorKind,
    val message: String,
    val httpResponseCode: Int? = null,
    val cause: Throwable? = null,
)

enum class PlaybackErrorKind {
    HTTP,
    RANGE,
    DECODE,
    NETWORK,
    UNKNOWN,
}

/**
 * Playback state is an immutable snapshot safe to read from any thread. Mutating
 * operations and listener registration must run on the engine application thread.
 */
interface PlaybackEngine {
    val state: PlaybackState

    fun prepare(media: PlaybackMedia)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun release()

    fun addListener(listener: (PlaybackState) -> Unit)

    fun removeListener(listener: (PlaybackState) -> Unit)
}
