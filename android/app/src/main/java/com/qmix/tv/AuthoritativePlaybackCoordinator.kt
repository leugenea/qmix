package com.qmix.tv

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Binds only fresh authoritative current-track selections to local playback. */
class AuthoritativePlaybackCoordinator(
    private val roomCode: String,
    private val streamUrl: String,
    private val playbackEngine: PlaybackEngine,
    private val reconciler: suspend (String) -> RoomFetchResult,
    parentScope: CoroutineScope,
    private val mutationContext: QueueMutationContext,
    private val advanceAfterEnded: (String) -> Boolean,
    private val observer: (LocalPlaybackState) -> Unit = {},
    statePublisherFactory: ((PlayerStatePublisher.Listener) -> PlayerStatePublisher)? = null,
) : AutoCloseable {
    private var currentTrackId: String? = null
    private var latestRoom: RoomState? = null
    private var endedConsumed = false
    private var explicitlyPaused = false
    private val sessionJob = SupervisorJob(requireNotNull(parentScope.coroutineContext[Job]))
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob + mutationContext.dispatcher)
    private var retryJob: Job? = null
    private var reportReconciliationJob: Job? = null
    private var foregroundReady = true
    private var closed = false
    private val playbackListener: (PlaybackState) -> Unit = { snapshot ->
        mutationContext.run { onPlaybackState(snapshot) }
    }
    private val statePublisher = statePublisherFactory?.invoke(object : PlayerStatePublisher.Listener {
        override fun onSynchronizationChanged(synchronized: Boolean) {
            mutationContext.run {
                if (sessionJob.isActive && state.reportSynchronized != synchronized) {
                    publish(state.copy(reportSynchronized = synchronized))
                }
            }
        }

        override fun onConflict() = mutationContext.run { reconcilePlayerReportConflict() }
        override fun onRoomUnavailable() = Unit
    })

    @Volatile
    var state: LocalPlaybackState = LocalPlaybackState()
        private set

    init {
        playbackEngine.addListener(playbackListener)

    }

    fun onSynchronization(synchronization: RoomSyncState) {
        mutationContext.run {
            if (closed || !foregroundReady) return@run
            val active = synchronization as? RoomSyncState.Active ?: return@run
            val room = active.room ?: return@run
            if (active.roomCode != roomCode || room.code != roomCode ||
                active.freshness != Freshness.FRESH || active.connection != LiveConnection.CONNECTED
            ) {
                return@run
            }
            statePublisher?.reconciled(room.current?.trackId)
            applyAuthoritativeRoom(room)
        }
    }

    fun onForegroundLost(): Unit = mutationContext.run {
        if (!sessionJob.isActive || !foregroundReady) return@run
        foregroundReady = false
        retryJob?.cancel()
        retryJob = null
        invalidateReportReconciliation()
        statePublisher?.setForeground(false)
        playbackEngine.pause()
        if (currentTrackId != null && state.status in setOf(
                LocalPlaybackStatus.BUFFERING, LocalPlaybackStatus.PLAYING, LocalPlaybackStatus.PAUSED,
            )
        ) {
            explicitlyPaused = true
            publish(state.copy(status = LocalPlaybackStatus.PAUSED, isPlaying = false))
        }
    }

    /** The host delivers only its own successful, still-owned foreground GET. */
    fun onForegroundReconciled(room: RoomState): Unit = mutationContext.run {
        if (!sessionJob.isActive || foregroundReady || room.code != roomCode) return@run
        latestRoom = room
        val selectedId = room.current?.trackId
        statePublisher?.setForeground(true)
        statePublisher?.reconciled(selectedId)
        if (selectedId == null) {
            invalidateReportReconciliation()
            retryJob?.cancel()
            retryJob = null
            currentTrackId = null
            statePublisher?.selectTrack(null)
            endedConsumed = false
            explicitlyPaused = false
            foregroundReady = true
            publish(LocalPlaybackState(reportSynchronized = state.reportSynchronized))
            return@run
        }
        if (selectedId != currentTrackId) {
            invalidateReportReconciliation()
            retryJob?.cancel()
            retryJob = null
            currentTrackId = selectedId
            endedConsumed = false
            playbackEngine.prepare(PlaybackMedia(selectedId, streamUrl))
            playbackEngine.pause()
            explicitlyPaused = true
            foregroundReady = true
            publish(LocalPlaybackState(
                trackId = selectedId, status = LocalPlaybackStatus.PAUSED,
                reportSynchronized = state.reportSynchronized,
            ))
        } else if (state.status in setOf(LocalPlaybackStatus.COMPLETED, LocalPlaybackStatus.ERROR)) {
            foregroundReady = true
            return@run
        } else if (playbackEngine.state.status in setOf(PlaybackStatus.ENDED, PlaybackStatus.ERROR)) {
            foregroundReady = true
            onPlaybackState(playbackEngine.state)
            return@run
        } else {
            explicitlyPaused = true
            foregroundReady = true
            publish(state.copy(status = LocalPlaybackStatus.PAUSED, isPlaying = false))
        }
        if (sessionJob.isActive && foregroundReady && currentTrackId == selectedId) {
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
                retryJob?.cancel()
                retryJob = null
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
        retryJob?.cancel()
        retryJob = null
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
        if (!sessionJob.isActive || !foregroundReady || currentTrackId != trackId || explicitlyPaused) return
        playbackEngine.prepare(PlaybackMedia(trackId, streamUrl))
        if (!sessionJob.isActive || !foregroundReady || currentTrackId != trackId || explicitlyPaused) return
        playbackEngine.play()
    }

    fun pause() {
        mutationContext.run {
            if (closed || !foregroundReady || currentTrackId == null || endedConsumed ||
                state.status !in setOf(LocalPlaybackStatus.BUFFERING, LocalPlaybackStatus.PLAYING)
            ) {
                return@run
            }
            explicitlyPaused = true
            playbackEngine.pause()
            publish(state.copy(status = LocalPlaybackStatus.PAUSED, isPlaying = false))
            report(PlayerReportState.PAUSED, state.positionMs, immediate = true)
        }
    }

    fun resume() {
        mutationContext.run {
            if (closed || !foregroundReady || currentTrackId == null || endedConsumed || !explicitlyPaused ||
                state.status != LocalPlaybackStatus.PAUSED
            ) {
                return@run
            }
            explicitlyPaused = false
            publish(state.copy(status = LocalPlaybackStatus.BUFFERING, isPlaying = false))
            report(PlayerReportState.PLAYING, state.positionMs, immediate = true)
            playbackEngine.play()
        }
    }

    fun togglePlayPause() {
        mutationContext.run {
            if (closed || !foregroundReady || currentTrackId == null || endedConsumed) return@run
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
        mutationContext.run {
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
                return@run
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

    fun retryCurrent(): Unit = mutationContext.run {
        val expectedTrackId = currentTrackId
        if (!sessionJob.isActive || !foregroundReady || state.status != LocalPlaybackStatus.ERROR ||
            expectedTrackId == null || retryJob?.isActive == true
        ) return@run
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = fetchAuthoritativeRoom()
            coroutineContext.ensureActive()
            if (!foregroundReady || currentTrackId != expectedTrackId) return@launch
            retryJob = null
            val room = (result as? RoomFetchResult.Success)?.room ?: return@launch
            if (room.code != roomCode) return@launch
            latestRoom = room
            val authoritativeTrackId = room.current?.trackId
            if (authoritativeTrackId == expectedTrackId) {
                explicitlyPaused = false
                endedConsumed = false
                prepareAndPlay(expectedTrackId)
            } else {
                applyAuthoritativeRoom(room)
            }
        }
        retryJob = job
        job.start()
    }

    private suspend fun fetchAuthoritativeRoom(): RoomFetchResult = try {
        reconciler(roomCode)
    } catch (canceled: CancellationException) {
        throw canceled
    } catch (_: Exception) {
        RoomFetchResult.Failure
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
        reportReconciliationJob?.cancel()
        reportReconciliationJob = null
    }

    private fun reconcilePlayerReportConflict() {
        val expectedTrackId = currentTrackId ?: return
        if (!sessionJob.isActive || !foregroundReady || reportReconciliationJob?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = fetchAuthoritativeRoom()
            coroutineContext.ensureActive()
            if (!foregroundReady || currentTrackId != expectedTrackId) return@launch
            reportReconciliationJob = null
            val room = (result as? RoomFetchResult.Success)?.room ?: return@launch
            if (room.code != roomCode) return@launch
            statePublisher?.reconciled(room.current?.trackId)
            applyAuthoritativeRoom(room)
        }
        reportReconciliationJob = job
        job.start()
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

    override fun close(): Unit = mutationContext.run {
        if (closed) return@run
        closed = true
        sessionJob.cancel()
        retryJob = null
        reportReconciliationJob = null
        statePublisher?.close()
        playbackEngine.removeListener(playbackListener)
        playbackEngine.pause()
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
