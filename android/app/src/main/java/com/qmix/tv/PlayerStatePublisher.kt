package com.qmix.tv

import java.util.concurrent.Executor

/** Serial, generation-bound publication of the host's actual local playback state. */
class PlayerStatePublisher(
    private val roomCode: String,
    private val hostToken: String,
    private val client: PlayerReportClient,
    private val scheduler: RoomSyncScheduler,
    dispatcher: Executor,
    listener: Listener = object : Listener {
        override fun onSynchronizationChanged(synchronized: Boolean) = Unit
        override fun onConflict() = Unit
        override fun onRoomUnavailable() = Unit
    },
) : AutoCloseable {
    interface Listener {
        fun onSynchronizationChanged(synchronized: Boolean)
        fun onConflict()
        fun onRoomUnavailable()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MILLIS = 7_500L
    }

    private enum class ReportingMode { ACTIVE, RECONCILING, STOPPED }

    private val dispatcher = SerialExecutor(dispatcher)
    private var listener = listener
    private var trackId: String? = null
    private var generation = 0L
    private var latest: PlayerReport? = null
    private var playingProgressEnabled = false
    private var pending: PlayerReport? = null
    private var inFlight: Cancelable? = null
    private var periodic: Cancelable? = null
    private var synchronized = true
    private var mode = ReportingMode.ACTIVE
    private var foreground = true
    private var closed = false

    fun setListener(next: Listener) {
        dispatcher.execute { if (!closed) listener = next }
    }

    fun reconciled(selectedTrackId: String?) {
        dispatcher.execute {
            if (closed) return@execute
            if (selectedTrackId != trackId) {
                selectTrackNow(selectedTrackId)
            } else {
                if (mode == ReportingMode.STOPPED) return@execute
                mode = ReportingMode.ACTIVE
                if (foreground && selectedTrackId != null && periodic == null) {
                    scheduleProgress(generation)
                }
            }
        }
    }

    fun selectTrack(selectedTrackId: String?) {
        dispatcher.execute {
            if (!closed) selectTrackNow(selectedTrackId)
        }
    }

    private fun selectTrackNow(selectedTrackId: String?) {
        if (selectedTrackId == trackId) return
        generation++
        trackId = selectedTrackId
        mode = ReportingMode.ACTIVE
        latest = null
        playingProgressEnabled = false
        pending = null
        inFlight?.cancel()
        inFlight = null
        periodic?.cancel()
        periodic = null
        if (foreground && selectedTrackId != null) scheduleProgress(generation)
    }

    fun update(
        report: PlayerReport,
        immediate: Boolean,
        periodicProgress: Boolean = report.state == PlayerReportState.PLAYING,
    ) {
        dispatcher.execute {
            if (closed || !foreground || mode != ReportingMode.ACTIVE || report.trackId != trackId) return@execute
            latest = report
            playingProgressEnabled = periodicProgress && report.state == PlayerReportState.PLAYING
            if (inFlight != null) {
                if (immediate || pending != null) pending = report
            } else if (immediate) {
                send(report, generation)
            }
        }
    }

    fun suspendPlayingProgress() {
        dispatcher.execute {
            if (closed) return@execute
            playingProgressEnabled = false
            if (latest?.state == PlayerReportState.PLAYING) latest = null
            if (pending?.state == PlayerReportState.PLAYING) pending = null
        }
    }

    fun setForeground(active: Boolean) {
        dispatcher.execute {
            if (closed || foreground == active) return@execute
            foreground = active
            generation++
            latest = null
            playingProgressEnabled = false
            pending = null
            inFlight?.cancel()
            inFlight = null
            periodic?.cancel()
            periodic = null
            if (active && trackId != null) scheduleProgress(generation)
        }
    }

    private fun scheduleProgress(expectedGeneration: Long) {
        periodic = scheduler.schedule(PROGRESS_INTERVAL_MILLIS) {
            dispatcher.execute {
                if (closed || !foreground || mode != ReportingMode.ACTIVE || expectedGeneration != generation) return@execute
                periodic = null
                latest?.takeIf { playingProgressEnabled && it.state == PlayerReportState.PLAYING }?.let { report ->
                    if (inFlight == null) send(report, expectedGeneration) else pending = report
                }
                scheduleProgress(expectedGeneration)
            }
        }
    }

    private fun send(report: PlayerReport, expectedGeneration: Long) {
        val handle = client.reportPlayer(roomCode, hostToken, report) { result ->
            dispatcher.execute { complete(expectedGeneration, result) }
        }
        if (closed || expectedGeneration != generation || !foreground) {
            handle.cancel()
        } else {
            inFlight = handle
        }
    }

    private fun complete(expectedGeneration: Long, result: PlayerReportResult) {
        if (closed || expectedGeneration != generation) return
        inFlight = null
        var synchronizationNotification: Boolean? = null
        var notifyConflict = false
        var notifyUnavailable = false
        when (result) {
            PlayerReportResult.ACCEPTED -> {
                if (!synchronized) {
                    synchronized = true
                    synchronizationNotification = true
                }
            }
            PlayerReportResult.CONFLICT -> {
                if (synchronized) {
                    synchronized = false
                    synchronizationNotification = false
                }
                mode = ReportingMode.RECONCILING
                latest = null
                playingProgressEnabled = false
                pending = null
                periodic?.cancel()
                periodic = null
                notifyConflict = true
            }
            PlayerReportResult.FORBIDDEN,
            PlayerReportResult.MISSING,
            -> {
                if (synchronized) {
                    synchronized = false
                    synchronizationNotification = false
                }
                mode = ReportingMode.STOPPED
                pending = null
                periodic?.cancel()
                periodic = null
                notifyUnavailable = true
            }
            PlayerReportResult.FAILED -> {
                if (synchronized) {
                    synchronized = false
                    synchronizationNotification = false
                }
            }
        }
        val next = pending
        pending = null
        if (next != null && result != PlayerReportResult.CONFLICT &&
            result != PlayerReportResult.FORBIDDEN && result != PlayerReportResult.MISSING
        ) {
            send(next, expectedGeneration)
        }
        synchronizationNotification?.let { value -> safelyNotify { listener.onSynchronizationChanged(value) } }
        if (notifyConflict) safelyNotify { listener.onConflict() }
        if (notifyUnavailable) safelyNotify { listener.onRoomUnavailable() }
    }

    private inline fun safelyNotify(notification: () -> Unit) {
        try {
            notification()
        } catch (_: Throwable) {
            // Listener failures must not corrupt serialized publisher state or progress.
        }
    }

    override fun close() {
        dispatcher.execute {
            if (closed) return@execute
            closed = true
            generation++
            pending = null
            latest = null
            playingProgressEnabled = false
            inFlight?.cancel()
            inFlight = null
            periodic?.cancel()
            periodic = null
        }
    }
}
