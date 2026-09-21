package com.qmix.tv

import java.util.concurrent.Executor

/** Binds only fresh authoritative current-track selections to local playback. */
class AuthoritativePlaybackCoordinator(
    private val roomCode: String,
    private val streamUrl: String,
    private val playbackEngine: PlaybackEngine,
    private val reconciler: RoomStateFetcher,
    private val dispatcher: Executor,
    private val advanceAfterEnded: (String) -> Boolean,
    private val observer: (LocalPlaybackState) -> Unit = {},
    private val statePublisher: PlayerStatePublisher? = null,
) : AutoCloseable {
    private var currentTrackId: String? = null
    private var latestRoom: RoomState? = null
    private var endedConsumed = false
    private var explicitlyPaused = false
    private var retryGeneration = 0L
    private var retryRequest: Cancelable? = null
    private var reportReconciliationGeneration = 0L
    private var reportReconciliationRequest: Cancelable? = null
    private var foregroundReady = true
    @Volatile private var requestedLifecycleToken = 0L
    private var directLifecycleToken = 0L
    private var closed = false
    private val playbackListener: (PlaybackState) -> Unit = { snapshot ->
        dispatcher.execute { onPlaybackState(snapshot) }
    }

    @Volatile
    var state: LocalPlaybackState = LocalPlaybackState()
        private set

    init {
        playbackEngine.addListener(playbackListener)
        statePublisher?.setListener(object : PlayerStatePublisher.Listener {
            override fun onSynchronizationChanged(synchronized: Boolean) {
                dispatcher.execute {
                    if (!closed && state.reportSynchronized != synchronized) {
                        publish(state.copy(reportSynchronized = synchronized))
                    }
                }
            }

            override fun onConflict() {
                dispatcher.execute { reconcilePlayerReportConflict() }
            }

            override fun onRoomUnavailable() {
                // The local player remains usable; the observable sync flag is already false.
            }
        })
    }

    fun onSynchronization(synchronization: RoomSyncState) {
        dispatcher.execute {
            if (closed || !foregroundReady) return@execute
            val active = synchronization as? RoomSyncState.Active ?: return@execute
            val room = active.room ?: return@execute
            if (active.roomCode != roomCode || room.code != roomCode ||
                active.freshness != Freshness.FRESH || active.connection != LiveConnection.CONNECTED
            ) {
                return@execute
            }
            statePublisher?.reconciled(room.current?.trackId)
            applyAuthoritativeRoom(room)
        }
    }

    fun onForegroundLost() {
        val token = synchronized(this) { ++directLifecycleToken }
        onForegroundLost(token)
    }

    fun onForegroundLost(token: Long) {
        synchronized(this) {
            if (token > requestedLifecycleToken) requestedLifecycleToken = token
        }
        dispatcher.execute {
            if (closed || token != requestedLifecycleToken || !foregroundReady) return@execute
            foregroundReady = false
            retryGeneration++
            retryRequest?.cancel()
            retryRequest = null
            reportReconciliationGeneration++
            reportReconciliationRequest?.cancel()
            reportReconciliationRequest = null
            statePublisher?.setForeground(false)
            playbackEngine.pause()
            if (currentTrackId != null && state.status in setOf(
                    LocalPlaybackStatus.BUFFERING,
                    LocalPlaybackStatus.PLAYING,
                    LocalPlaybackStatus.PAUSED,
                )
            ) {
                explicitlyPaused = true
                publish(state.copy(status = LocalPlaybackStatus.PAUSED, isPlaying = false))
            }
        }
    }

    fun onForegroundReconciled(room: RoomState) {
        val token = synchronized(this) { directLifecycleToken }
        onForegroundReconciled(token, room)
    }

    fun onForegroundReconciled(token: Long, room: RoomState) {
        synchronized(this) {
            if (token < requestedLifecycleToken) return
            requestedLifecycleToken = token
        }
        dispatcher.execute {
            if (closed || token != requestedLifecycleToken || foregroundReady || room.code != roomCode) return@execute
            latestRoom = room
            val selectedId = room.current?.trackId
            statePublisher?.setForeground(true)
            statePublisher?.reconciled(selectedId)
            if (selectedId == null) {
                currentTrackId = null
                endedConsumed = false
                explicitlyPaused = false
                foregroundReady = true
                publish(LocalPlaybackState(reportSynchronized = state.reportSynchronized))
                return@execute
            }
            if (selectedId != currentTrackId) {
                retryGeneration++
                currentTrackId = selectedId
                endedConsumed = false
                playbackEngine.prepare(PlaybackMedia(selectedId, streamUrl))
                playbackEngine.pause()
                publish(
                    LocalPlaybackState(
                        trackId = selectedId,
                        status = LocalPlaybackStatus.PAUSED,
                        reportSynchronized = state.reportSynchronized,
                    ),
                )
            } else if (state.status in setOf(LocalPlaybackStatus.COMPLETED, LocalPlaybackStatus.ERROR)) {
                foregroundReady = true
                return@execute
            } else if (playbackEngine.state.status in setOf(PlaybackStatus.ENDED, PlaybackStatus.ERROR)) {
                foregroundReady = true
                onPlaybackState(playbackEngine.state)
                return@execute
            } else {
                publish(state.copy(status = LocalPlaybackStatus.PAUSED, isPlaying = false))
            }
            explicitlyPaused = true
            foregroundReady = true
            report(PlayerReportState.PAUSED, state.positionMs, immediate = true)
        }
    }

    private fun applyAuthoritativeRoom(room: RoomState) {
        latestRoom = room
        val selectedId = room.current?.trackId
        if (selectedId == null) {
            if (currentTrackId != null) {
                invalidateReportReconciliation()
                currentTrackId = null
                retryGeneration++
                retryRequest?.cancel()
                retryRequest = null
                statePublisher?.selectTrack(null)
                if (state.status != LocalPlaybackStatus.COMPLETED) {
                    playbackEngine.pause()
                    publish(LocalPlaybackState(reportSynchronized = state.reportSynchronized))
                }
            }
            return
        }
        if (selectedId == currentTrackId) return
        invalidateReportReconciliation()
        retryGeneration++
        retryRequest?.cancel()
        retryRequest = null
        currentTrackId = selectedId
        statePublisher?.selectTrack(selectedId)
        endedConsumed = false
        explicitlyPaused = false
        prepareAndPlay(selectedId)
    }

    private fun prepareAndPlay(trackId: String) {
        publish(
            LocalPlaybackState(
                trackId = trackId,
                status = LocalPlaybackStatus.BUFFERING,
                reportSynchronized = state.reportSynchronized,
            ),
        )
        playbackEngine.prepare(PlaybackMedia(trackId, streamUrl))
        playbackEngine.play()
    }

    fun pause() {
        dispatcher.execute {
            if (closed || !foregroundReady || currentTrackId == null || endedConsumed ||
                state.status !in setOf(LocalPlaybackStatus.BUFFERING, LocalPlaybackStatus.PLAYING)
            ) {
                return@execute
            }
            explicitlyPaused = true
            playbackEngine.pause()
            publish(state.copy(status = LocalPlaybackStatus.PAUSED, isPlaying = false))
            report(PlayerReportState.PAUSED, state.positionMs, immediate = true)
        }
    }

    fun resume() {
        dispatcher.execute {
            if (closed || !foregroundReady || currentTrackId == null || endedConsumed || !explicitlyPaused ||
                state.status != LocalPlaybackStatus.PAUSED
            ) {
                return@execute
            }
            explicitlyPaused = false
            publish(state.copy(status = LocalPlaybackStatus.BUFFERING, isPlaying = false))
            report(PlayerReportState.PLAYING, state.positionMs, immediate = true)
            playbackEngine.play()
        }
    }

    fun togglePlayPause() {
        dispatcher.execute {
            if (closed || !foregroundReady || currentTrackId == null || endedConsumed) return@execute
            when (state.status) {
                LocalPlaybackStatus.BUFFERING,
                LocalPlaybackStatus.PLAYING,
                -> {
                    explicitlyPaused = true
                    playbackEngine.pause()
                    publish(state.copy(status = LocalPlaybackStatus.PAUSED, isPlaying = false))
                    report(PlayerReportState.PAUSED, state.positionMs, immediate = true)
                }
                LocalPlaybackStatus.PAUSED -> {
                    explicitlyPaused = false
                    publish(state.copy(status = LocalPlaybackStatus.BUFFERING, isPlaying = false))
                    report(PlayerReportState.PLAYING, state.positionMs, immediate = true)
                    playbackEngine.play()
                }
                LocalPlaybackStatus.IDLE,
                LocalPlaybackStatus.COMPLETED,
                LocalPlaybackStatus.ERROR,
                -> Unit
            }
        }
    }

    fun seekBy(offsetMs: Long) {
        dispatcher.execute {
            val snapshot = state
            val duration = snapshot.durationMs
            if (closed || !foregroundReady || currentTrackId == null || endedConsumed ||
                !snapshot.isSeekable || duration == null ||
                snapshot.status !in setOf(
                    LocalPlaybackStatus.BUFFERING,
                    LocalPlaybackStatus.PLAYING,
                    LocalPlaybackStatus.PAUSED,
                )
            ) {
                return@execute
            }
            val position = snapshot.positionMs.coerceIn(0, duration)
            val target = if (offsetMs >= 0) {
                if (offsetMs >= duration - position) duration else position + offsetMs
            } else {
                val magnitude = if (offsetMs == Long.MIN_VALUE) Long.MAX_VALUE else -offsetMs
                if (magnitude >= position) 0 else position - magnitude
            }
            val reportState = if (explicitlyPaused || snapshot.status == LocalPlaybackStatus.PAUSED) {
                PlayerReportState.PAUSED
            } else {
                PlayerReportState.PLAYING
            }
            report(
                reportState,
                target,
                immediate = true,
                periodicProgress = reportState == PlayerReportState.PLAYING &&
                    snapshot.status == LocalPlaybackStatus.PLAYING,
            )
            playbackEngine.seekTo(target)
        }
    }

    fun retryCurrent() {
        dispatcher.execute {
            val expectedTrackId = currentTrackId
            if (closed || !foregroundReady || state.status != LocalPlaybackStatus.ERROR || expectedTrackId == null || retryRequest != null) {
                return@execute
            }
            val token = ++retryGeneration
            val request = reconciler.fetch(roomCode) { result ->
                dispatcher.execute { completeRetry(token, expectedTrackId, result) }
            }
            if (closed || token != retryGeneration) {
                request.cancel()
            } else {
                retryRequest = request
            }
        }
    }

    private fun completeRetry(token: Long, expectedTrackId: String, result: RoomFetchResult) {
        if (closed || token != retryGeneration) return
        retryGeneration++
        retryRequest = null
        val room = (result as? RoomFetchResult.Success)?.room ?: return
        if (room.code != roomCode) return
        latestRoom = room
        val authoritativeTrackId = room.current?.trackId
        if (authoritativeTrackId == expectedTrackId && currentTrackId == expectedTrackId) {
            explicitlyPaused = false
            endedConsumed = false
            prepareAndPlay(expectedTrackId)
        } else {
            applyAuthoritativeRoom(room)
        }
    }

    private fun onPlaybackState(snapshot: PlaybackState) {
        if (closed || !foregroundReady || snapshot.mediaId != currentTrackId || endedConsumed) return
        val previousStatus = state.status
        if (snapshot.status == PlaybackStatus.ENDED) {
            endedConsumed = true
            report(PlayerReportState.ENDED, snapshot.positionMs, immediate = true)
            if (latestRoom?.queue?.isNotEmpty() == true) {
                advanceAfterEnded(checkNotNull(currentTrackId))
            } else {
                publish(snapshot.toLocal(LocalPlaybackStatus.COMPLETED))
            }
            return
        }
        val status = when {
            snapshot.status == PlaybackStatus.ERROR -> LocalPlaybackStatus.ERROR
            explicitlyPaused -> LocalPlaybackStatus.PAUSED
            snapshot.isPlaying -> LocalPlaybackStatus.PLAYING
            else -> LocalPlaybackStatus.BUFFERING
        }
        publish(snapshot.toLocal(status))
        when (status) {
            LocalPlaybackStatus.ERROR -> report(PlayerReportState.ERROR, snapshot.positionMs, immediate = true)
            LocalPlaybackStatus.PLAYING -> report(
                PlayerReportState.PLAYING,
                snapshot.positionMs,
                immediate = previousStatus != LocalPlaybackStatus.PLAYING,
                periodicProgress = true,
            )
            LocalPlaybackStatus.PAUSED -> report(
                PlayerReportState.PAUSED,
                snapshot.positionMs,
                immediate = previousStatus != LocalPlaybackStatus.PAUSED,
            )
            LocalPlaybackStatus.BUFFERING -> statePublisher?.suspendPlayingProgress()
            else -> Unit
        }
    }

    private fun report(
        reportState: PlayerReportState,
        positionMs: Long,
        immediate: Boolean,
        periodicProgress: Boolean = false,
    ) {
        val selected = currentTrackId ?: return
        val seconds = (positionMs.coerceAtLeast(0) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        statePublisher?.update(PlayerReport(selected, reportState, seconds), immediate, periodicProgress)
    }

    private fun invalidateReportReconciliation() {
        reportReconciliationGeneration++
        reportReconciliationRequest?.cancel()
        reportReconciliationRequest = null
    }

    private fun reconcilePlayerReportConflict() {
        val expectedTrackId = currentTrackId ?: return
        if (closed || !foregroundReady || reportReconciliationRequest != null) return
        val token = ++reportReconciliationGeneration
        val request = reconciler.fetch(roomCode) { result ->
            dispatcher.execute {
                if (closed || token != reportReconciliationGeneration) return@execute
                reportReconciliationGeneration++
                reportReconciliationRequest = null
                val room = (result as? RoomFetchResult.Success)?.room ?: return@execute
                if (room.code != roomCode || currentTrackId != expectedTrackId) return@execute
                statePublisher?.reconciled(room.current?.trackId)
                applyAuthoritativeRoom(room)
            }
        }
        if (closed || token != reportReconciliationGeneration) {
            request.cancel()
        } else {
            reportReconciliationRequest = request
        }
    }

    private fun PlaybackState.toLocal(status: LocalPlaybackStatus) = LocalPlaybackState(
        trackId = mediaId,
        status = status,
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        isSeekable = isSeekable,
        error = error,
        reportSynchronized = state.reportSynchronized,
    )

    private fun publish(next: LocalPlaybackState) {
        state = next
        observer(next)
    }

    override fun close() {
        dispatcher.execute {
            if (closed) return@execute
            closed = true
            retryGeneration++
            retryRequest?.cancel()
            retryRequest = null
            reportReconciliationGeneration++
            reportReconciliationRequest?.cancel()
            reportReconciliationRequest = null
            statePublisher?.close()
            playbackEngine.removeListener(playbackListener)
            playbackEngine.pause()
        }
    }
}

enum class LocalPlaybackStatus {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    COMPLETED,
    ERROR,
}

data class LocalPlaybackState(
    val trackId: String? = null,
    val status: LocalPlaybackStatus = LocalPlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val isSeekable: Boolean = false,
    val error: PlaybackError? = null,
    val reportSynchronized: Boolean = true,
)
