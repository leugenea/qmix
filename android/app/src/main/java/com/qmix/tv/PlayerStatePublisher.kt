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
import kotlinx.coroutines.withTimeout

/** Structured, selection-bound publication of the host's actual local playback state. */
class PlayerStatePublisher(
    private val roomCode: String,
    private val hostToken: String,
    private val reportPlayer: suspend (String, String, PlayerReport) -> PlayerReportResult,
    parentScope: CoroutineScope,
    private val mutationContext: QueueMutationContext,
    private val finalReportScope: CoroutineScope,
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
        const val FINAL_PAUSE_TIMEOUT_MILLIS = 2_000L
    }

    private enum class ReportingMode { ACTIVE, RECONCILING, STOPPED }

    private class Selection(val trackId: String)

    private val sessionJob = SupervisorJob(requireNotNull(parentScope.coroutineContext[Job]))
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob + mutationContext.coroutineContext)
    private val listener = listener
    private var selection: Selection? = null
    private var latest: PlayerReport? = null
    private var acceptedPause: PlayerReport? = null
    private var pending: PlayerReport? = null
    private var reportJob: Job? = null
    private var foregroundPauseJob: Job? = null
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
        acceptedPause = null
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
        val activeSelection = selection ?: return@run
        if (!canUpdate(report, activeSelection)) return@run

        latest = report
        clearAcknowledgedPauseIfChanged(report)
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

    private fun clearAcknowledgedPauseIfChanged(report: PlayerReport) {
        if (report.state != PlayerReportState.PAUSED || acceptedPause != report) acceptedPause = null
    }

    private fun canUpdate(report: PlayerReport, activeSelection: Selection): Boolean =
        sessionJob.isActive && foreground && mode == ReportingMode.ACTIVE &&
            report.trackId == activeSelection.trackId

    fun suspendPlayingProgress(): Unit = mutationContext.run {
        if (!sessionJob.isActive) return@run
        playingProgressEnabled = false
        periodicJob?.cancel()
        periodicJob = null
        if (latest?.state == PlayerReportState.PLAYING) latest = null
        if (pending?.state == PlayerReportState.PLAYING) pending = null
    }

    internal fun foregroundWorkers(): List<Job> = sessionJob.children.toList()

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

    /** One bounded final report, owned by the process rather than the cancelled room session.
     * Wait for the cancelled in-flight report: the backend has no per-track sequence numbers.
     * A timeout while an older request ignores cancellation drops the pause. Cancellation
     * cannot prove ordering for a request the backend already received; this is best effort.
     * Even after the pause deadline, keep the recovery barrier until predecessors settle;
     * local teardown never joins it. No callbacks or retries are emitted.
     */
    fun reportFinalPause(report: PlayerReport, alreadyPaused: Boolean = false): Unit = mutationContext.run {
        if (report.state != PlayerReportState.PAUSED || report.trackId != selection?.trackId ||
            !foreground || (alreadyPaused && acceptedPause == report)
        ) return@run
        val previous = reportJob
        val priorPause = foregroundPauseJob
        setForeground(false)
        val job = finalReportScope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(FINAL_PAUSE_TIMEOUT_MILLIS) {
                    previous?.join()
                    priorPause?.join()
                    reportPlayer(roomCode, hostToken, report)
                }
            } catch (_: Exception) {
                // Network failures, cancellation, and the deadline cannot block local teardown.
            } finally {
                // A timed-out attempt must not free a newer report to overtake a prior
                // PAUSED (or a recovered report) still ignoring cancellation.
                previous?.join()
                priorPause?.join()
            }
        }
        foregroundPauseJob = job
        job.start()
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
        // Capture at admission: a later foreground loss may chain behind this Job.
        // Reading the mutable field inside launch could make the two Jobs join each other.
        val precedingPause = foregroundPauseJob
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val activeJob = requireNotNull(coroutineContext[Job])
            var completedResult: PlayerReportResult? = null
            try {
                val result = try {
                    precedingPause?.join()
                    reportPlayer(roomCode, hostToken, report)
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (_: Exception) {
                    PlayerReportResult.FAILED
                }
                coroutineContext.ensureActive()
                recordAcceptedPause(report, result)
                completedResult = result
            } finally {
                finish(expectedSelection, activeJob, completedResult)
            }
        }
        reportJob = job
        // Cancellation before posted body-entry skips its finally. Retire the exact
        // slot only at whole-Job completion, including any owned child cleanup.
        job.invokeOnCompletion { mutationContext.run { releaseCompletedReport(job) } }
        job.start()
    }

    private fun recordAcceptedPause(report: PlayerReport, result: PlayerReportResult) {
        if (result == PlayerReportResult.ACCEPTED && report.state == PlayerReportState.PAUSED &&
            latest == report && foreground
        ) acceptedPause = report
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

        val synchronizationNotification = applyReportResult(result)
        notifyReportResult(expectedSelection, completedJob, result, synchronizationNotification)
        releaseCompletedReport(completedJob)
    }

    private fun applyReportResult(result: PlayerReportResult): Boolean? {
        var synchronizationNotification: Boolean? = null
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
            }
            PlayerReportResult.FAILED -> if (synchronized) {
                synchronized = false
                synchronizationNotification = false
            }
        }
        return synchronizationNotification
    }

    private fun notifyReportResult(
        expectedSelection: Selection,
        completedJob: Job,
        result: PlayerReportResult,
        synchronizationNotification: Boolean?,
    ) {
        synchronizationNotification?.let { value -> safelyNotify { listener.onSynchronizationChanged(value) } }
        when (result) {
            PlayerReportResult.CONFLICT -> {
                if (secondaryNotificationIsCurrent(expectedSelection, completedJob, ReportingMode.RECONCILING)) {
                    safelyNotify { listener.onConflict() }
                }
            }
            PlayerReportResult.FORBIDDEN,
            PlayerReportResult.MISSING,
            -> {
                if (secondaryNotificationIsCurrent(expectedSelection, completedJob, ReportingMode.STOPPED)) {
                    safelyNotify { listener.onRoomUnavailable() }
                }
            }
            else -> Unit
        }
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
        if (reportJob !== completedJob || !completedJob.isCompleted) return
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
