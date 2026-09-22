package com.qmix.tv

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Structured, selection-bound publication of the host's actual local playback state. */
class PlayerStatePublisher(
    private val roomCode: String,
    private val hostToken: String,
    private val reportPlayer: suspend (String, String, PlayerReport) -> PlayerReportResult,
    parentScope: CoroutineScope,
    private val mutationContext: QueueMutationContext,
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

    private class Selection(val trackId: String)

    private val sessionJob = SupervisorJob(requireNotNull(parentScope.coroutineContext[Job]))
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob + mutationContext.dispatcher)
    private val listener = listener
    private var selection: Selection? = null
    private var latest: PlayerReport? = null
    private var pending: PlayerReport? = null
    private var reportJob: Job? = null
    private var periodicJob: Job? = null
    private var playingProgressEnabled = false
    private var synchronized = true
    private var mode = ReportingMode.ACTIVE
    private var foreground = true

    fun reconciled(selectedTrackId: String?): Unit = mutationContext.run {
        if (!sessionJob.isActive) return@run
        if (selectedTrackId != selection?.trackId) {
            selectTrackNow(selectedTrackId)
        } else if (mode != ReportingMode.STOPPED) {
            mode = ReportingMode.ACTIVE
        }
    }

    fun selectTrack(selectedTrackId: String?): Unit = mutationContext.run {
        if (sessionJob.isActive) selectTrackNow(selectedTrackId)
    }

    private fun selectTrackNow(selectedTrackId: String?) {
        if (selectedTrackId == selection?.trackId) return
        val activeReport = reportJob
        selection = selectedTrackId?.let(::Selection)
        mode = ReportingMode.ACTIVE
        latest = null
        pending = null
        playingProgressEnabled = false
        periodicJob?.cancel()
        periodicJob = null
        activeReport?.cancel()
    }

    fun update(
        report: PlayerReport,
        immediate: Boolean,
        periodicProgress: Boolean = report.state == PlayerReportState.PLAYING,
    ): Unit = mutationContext.run {
        val activeSelection = selection
        if (!sessionJob.isActive || !foreground || mode != ReportingMode.ACTIVE ||
            report.trackId != activeSelection?.trackId
        ) {
            return@run
        }

        latest = report
        playingProgressEnabled = periodicProgress && report.state == PlayerReportState.PLAYING
        if (playingProgressEnabled) {
            ensurePeriodic(activeSelection)
        } else {
            periodicJob?.cancel()
            periodicJob = null
            if (pending?.state == PlayerReportState.PLAYING) pending = null
        }

        if (reportJob != null) {
            if (immediate || pending?.state == PlayerReportState.PLAYING) pending = report
        } else if (immediate) {
            send(report, activeSelection)
        }
    }

    fun suspendPlayingProgress(): Unit = mutationContext.run {
        if (!sessionJob.isActive) return@run
        playingProgressEnabled = false
        periodicJob?.cancel()
        periodicJob = null
        if (latest?.state == PlayerReportState.PLAYING) latest = null
        if (pending?.state == PlayerReportState.PLAYING) pending = null
    }

    fun setForeground(active: Boolean): Unit = mutationContext.run {
        if (!sessionJob.isActive || foreground == active) return@run
        foreground = active
        latest = null
        pending = null
        playingProgressEnabled = false
        val activeReport = reportJob
        periodicJob?.cancel()
        periodicJob = null
        activeReport?.cancel()
    }

    private fun ensurePeriodic(expectedSelection: Selection) {
        if (periodicJob?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (currentCoroutineContext().isActive) {
                delay(PROGRESS_INTERVAL_MILLIS)
                if (!sessionJob.isActive || !foreground || mode != ReportingMode.ACTIVE ||
                    selection !== expectedSelection || !playingProgressEnabled
                ) {
                    return@launch
                }
                val report = latest?.takeIf { it.state == PlayerReportState.PLAYING } ?: continue
                if (reportJob != null) {
                    if (pending == null || pending?.state == PlayerReportState.PLAYING) pending = report
                } else {
                    send(report, expectedSelection)
                }
            }
        }
        periodicJob = job
        job.start()
    }

    private fun send(report: PlayerReport, expectedSelection: Selection) {
        check(reportJob == null)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val activeJob = requireNotNull(coroutineContext[Job])
            var completedResult: PlayerReportResult? = null
            try {
                val result = try {
                    reportPlayer(roomCode, hostToken, report)
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (_: Exception) {
                    PlayerReportResult.FAILED
                }
                coroutineContext.ensureActive()
                completedResult = result
            } finally {
                finish(expectedSelection, activeJob, completedResult)
            }
        }
        reportJob = job
        job.start()
    }

    private fun finish(
        expectedSelection: Selection,
        completedJob: Job,
        result: PlayerReportResult?,
    ) {
        if (reportJob !== completedJob) return
        if (!sessionJob.isActive || !foreground || selection !== expectedSelection || result == null) {
            releaseCompletedReport(completedJob)
            return
        }

        var synchronizationNotification: Boolean? = null
        var notifyConflict = false
        var notifyUnavailable = false
        when (result) {
            PlayerReportResult.ACCEPTED -> if (!synchronized) {
                synchronized = true
                synchronizationNotification = true
            }
            PlayerReportResult.CONFLICT -> {
                if (synchronized) {
                    synchronized = false
                    synchronizationNotification = false
                }
                mode = ReportingMode.RECONCILING
                latest = null
                pending = null
                playingProgressEnabled = false
                periodicJob?.cancel()
                periodicJob = null
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
                playingProgressEnabled = false
                periodicJob?.cancel()
                periodicJob = null
                notifyUnavailable = true
            }
            PlayerReportResult.FAILED -> if (synchronized) {
                synchronized = false
                synchronizationNotification = false
            }
        }

        synchronizationNotification?.let { value -> safelyNotify { listener.onSynchronizationChanged(value) } }
        if (notifyConflict &&
            secondaryNotificationIsCurrent(expectedSelection, completedJob, ReportingMode.RECONCILING)
        ) {
            safelyNotify { listener.onConflict() }
        }
        if (notifyUnavailable &&
            secondaryNotificationIsCurrent(expectedSelection, completedJob, ReportingMode.STOPPED)
        ) {
            safelyNotify { listener.onRoomUnavailable() }
        }

        releaseCompletedReport(completedJob)
    }

    private fun secondaryNotificationIsCurrent(
        expectedSelection: Selection,
        completedJob: Job,
        expectedMode: ReportingMode,
    ): Boolean =
        reportJob === completedJob &&
            sessionJob.isActive &&
            foreground &&
            selection === expectedSelection &&
            mode == expectedMode

    private fun releaseCompletedReport(completedJob: Job) {
        if (reportJob !== completedJob) return
        val activeSelection = selection
        val next = pending
        reportJob = null
        pending = null
        if (!sessionJob.isActive || !foreground || mode != ReportingMode.ACTIVE ||
            activeSelection == null || next == null || next.trackId != activeSelection.trackId
        ) {
            return
        }
        send(next, activeSelection)
    }

    private inline fun safelyNotify(notification: () -> Unit) {
        try {
            notification()
        } catch (_: Throwable) {
            // Listener failures must not corrupt serialized publisher state or progress.
        }
    }

    override fun close(): Unit = mutationContext.run {
        if (!sessionJob.isActive) return@run
        latest = null
        pending = null
        playingProgressEnabled = false
        reportJob = null
        periodicJob = null
        sessionJob.cancel()
    }
}
