package com.qmix.tv

internal sealed interface BackendFailure {
    val message: String
    val cause: Throwable?

    data class Http(val responseCode: Int, override val message: String, override val cause: Throwable? = null) : BackendFailure
    data class Range(
        override val message: String,
        val responseCode: Int? = null,
        override val cause: Throwable? = null,
    ) : BackendFailure
    data class Decode(override val message: String, override val cause: Throwable? = null) : BackendFailure
    data class Network(override val message: String, override val cause: Throwable? = null) : BackendFailure
    data class Unknown(override val message: String, override val cause: Throwable? = null) : BackendFailure
}

/**
 * Adapter operations and callbacks run on the Media3 application thread. Snapshots
 * are immutable values so the engine never needs to read a Player from another thread.
 */
internal interface PlayerBackend {
    data class Snapshot(
        val positionMs: Long,
        val durationMs: Long?,
        val isSeekable: Boolean,
        val isPlaying: Boolean,
    )

    sealed interface Event {
        data class Buffering(val snapshot: Snapshot) : Event
        data class Ready(val snapshot: Snapshot) : Event
        data class Ended(val snapshot: Snapshot) : Event
        data class StateChanged(val snapshot: Snapshot) : Event
        data class Failed(val failure: BackendFailure) : Event
    }

    fun configureAudioFocus(enabled: Boolean)
    fun setMedia(media: PlaybackMedia)
    fun setListener(listener: (Event) -> Unit)
    fun prepare()
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
}

/**
 * Mutating operations must be called on the backend's application thread. [state]
 * is a safely-published immutable cache and may be read from any thread.
 */
internal class Media3PlaybackEngine(
    private val player: PlayerBackend,
    private val requireApplicationThread: () -> Unit = {},
) : PlaybackEngine {
    private val listeners = linkedSetOf<(PlaybackState) -> Unit>()
    private var generation = 0L
    private var released = false
    private var currentMedia: PlaybackMedia? = null
    @Volatile private var storedState = PlaybackState()

    init {
        player.configureAudioFocus(true)
    }

    override val state: PlaybackState
        get() = storedState

    override fun prepare(media: PlaybackMedia) {
        requireApplicationThread()
        check(!released) { "PlaybackEngine is released" }
        if (currentMedia == media && storedState.status != PlaybackStatus.ERROR) return
        currentMedia = media
        generation += 1
        val callbackGeneration = generation
        player.setListener { event -> onPlayerEvent(callbackGeneration, event) }
        publish(PlaybackState(mediaId = media.trackId, status = PlaybackStatus.BUFFERING))
        player.setMedia(media)
        player.prepare()
    }

    override fun play() {
        requireApplicationThread()
        if (!released && currentMedia != null) player.play()
    }

    override fun pause() {
        requireApplicationThread()
        if (!released) player.pause()
    }

    override fun seekTo(positionMs: Long) {
        requireApplicationThread()
        val snapshot = storedState
        val duration = snapshot.durationMs
        if (!released && currentMedia != null && snapshot.isSeekable && duration != null) {
            player.seekTo(positionMs.coerceIn(0, duration))
        }
    }

    override fun release() {
        requireApplicationThread()
        if (released) return
        released = true
        generation += 1
        player.release()
        publish(storedState.copy(status = PlaybackStatus.RELEASED, isPlaying = false))
        listeners.clear()
    }

    override fun addListener(listener: (PlaybackState) -> Unit) {
        requireApplicationThread()
        if (!released) listeners += listener
    }

    override fun removeListener(listener: (PlaybackState) -> Unit) {
        requireApplicationThread()
        listeners -= listener
    }

    internal fun onHostStop() = pause()
    internal fun onHostDestroy() = release()

    private fun onPlayerEvent(callbackGeneration: Long, event: PlayerBackend.Event) {
        if (released || callbackGeneration != generation) return
        when (event) {
            is PlayerBackend.Event.Buffering -> publishSnapshot(storedState.copy(status = PlaybackStatus.BUFFERING), event.snapshot)
            is PlayerBackend.Event.Ready -> publishSnapshot(storedState.copy(status = PlaybackStatus.READY, error = null), event.snapshot)
            is PlayerBackend.Event.StateChanged -> publishSnapshot(storedState, event.snapshot)
            is PlayerBackend.Event.Ended -> publishSnapshot(storedState.copy(status = PlaybackStatus.ENDED), event.snapshot.copy(isPlaying = false))
            is PlayerBackend.Event.Failed -> publish(
                storedState.copy(
                    status = PlaybackStatus.ERROR,
                    isPlaying = false,
                    error = event.failure.toPlaybackError(),
                ),
            )
        }
    }

    private fun publishSnapshot(base: PlaybackState, snapshot: PlayerBackend.Snapshot) {
        val duration = snapshot.durationMs?.takeIf { it >= 0 }
        publish(
            base.copy(
                isPlaying = if (base.status == PlaybackStatus.ENDED || base.status == PlaybackStatus.ERROR) false else snapshot.isPlaying,
                positionMs = snapshot.positionMs.coerceAtLeast(0),
                durationMs = duration,
                isSeekable = snapshot.isSeekable && duration != null,
            ),
        )
    }

    private fun publish(next: PlaybackState) {
        storedState = next
        listeners.toList().forEach { it(next) }
    }

    private fun BackendFailure.toPlaybackError() = PlaybackError(
        kind = when (this) {
            is BackendFailure.Http -> PlaybackErrorKind.HTTP
            is BackendFailure.Range -> PlaybackErrorKind.RANGE
            is BackendFailure.Decode -> PlaybackErrorKind.DECODE
            is BackendFailure.Network -> PlaybackErrorKind.NETWORK
            is BackendFailure.Unknown -> PlaybackErrorKind.UNKNOWN
        },
        message = message,
        httpResponseCode = when (this) {
            is BackendFailure.Http -> responseCode
            is BackendFailure.Range -> responseCode
            else -> null
        },
        cause = cause,
    )
}
