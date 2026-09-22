package com.qmix.tv

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStatePublisherTest {
    @Test
    fun cadence_coalesces_latest_progress_with_no_more_than_one_report_in_flight() = runTest {
        val reporter = SuspendedReporter()
        val publisher = publisher(reporter)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 0), immediate = true)
        runCurrent()
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 6), immediate = false)
        advanceTimeBy(7_500)
        runCurrent()
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 8), immediate = false)

        assertEquals(listOf(0), reporter.positions())
        assertEquals(1, reporter.maxInFlight)

        reporter.complete(0, PlayerReportResult.ACCEPTED)
        runCurrent()

        assertEquals(listOf(0, 8), reporter.positions())
        assertEquals(1, reporter.maxInFlight)
        reporter.complete(1, PlayerReportResult.ACCEPTED)
        runCurrent()

        advanceTimeBy(7_499)
        runCurrent()
        assertEquals(2, reporter.calls.size)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(0, 8, 8), reporter.positions())
        assertEquals(1, reporter.maxInFlight)
        publisher.close()
    }

    @Test
    fun periodic_progress_cannot_replace_a_queued_pause_while_a_report_is_in_flight() = runTest {
        val reporter = SuspendedReporter()
        val publisher = publisher(reporter)
        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 0), immediate = true)
        runCurrent()
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)
        // A newer playing sample must not supersede the already queued pause.
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 3), immediate = false)
        advanceTimeBy(7_500)
        runCurrent()
        reporter.complete(0, PlayerReportResult.ACCEPTED)
        runCurrent()

        assertEquals(listOf(PlayerReportState.PLAYING, PlayerReportState.PAUSED), reporter.states())
        assertEquals(listOf(0, 2), reporter.positions())
        reporter.complete(1, PlayerReportResult.ACCEPTED)
        runCurrent()
        assertEquals(1, reporter.maxInFlight)
        publisher.close()
    }

    @Test
    fun track_replacement_waits_for_canceled_report_completion_before_starting_the_new_report() = runTest {
        val reporter = SuspendedReporter()
        val publisher = publisher(reporter)

        publisher.selectTrack("old")
        publisher.update(PlayerReport("old", PlayerReportState.PLAYING, 4), immediate = true)
        runCurrent()
        reporter.calls.single().ignoreCancellation = true

        publisher.selectTrack("new")
        publisher.update(PlayerReport("new", PlayerReportState.PLAYING, 0), immediate = true)
        runCurrent()

        assertTrue(reporter.calls.single().canceled)
        assertEquals(listOf("old"), reporter.calls.map { it.report.trackId })
        assertEquals(1, reporter.maxInFlight)

        reporter.complete(0, PlayerReportResult.ACCEPTED)
        runCurrent()

        assertEquals(listOf("old", "new"), reporter.calls.map { it.report.trackId })
        assertEquals(1, reporter.maxInFlight)
        publisher.close()
    }

    @Test
    fun track_replacement_cancels_suspended_report_and_late_conflict_cannot_affect_new_selection() = runTest {
        val reporter = SuspendedReporter()
        val notifications = RecordingListener()
        val publisher = publisher(reporter, notifications)

        publisher.selectTrack("old")
        publisher.update(PlayerReport("old", PlayerReportState.PLAYING, 4), immediate = true)
        runCurrent()
        reporter.calls.single().ignoreCancellation = true

        publisher.selectTrack("new")
        publisher.update(PlayerReport("new", PlayerReportState.PLAYING, 0), immediate = true)
        runCurrent()

        assertTrue(reporter.calls.single().canceled)
        assertEquals(listOf("old"), reporter.calls.map { it.report.trackId })

        reporter.complete(0, PlayerReportResult.CONFLICT)
        runCurrent()
        assertEquals(listOf("old", "new"), reporter.calls.map { it.report.trackId })
        assertTrue(notifications.events.isEmpty())

        reporter.complete(1, PlayerReportResult.ACCEPTED)
        runCurrent()
        publisher.update(PlayerReport("new", PlayerReportState.PAUSED, 2), immediate = true)
        runCurrent()
        assertEquals(listOf("old", "new", "new"), reporter.calls.map { it.report.trackId })
        publisher.close()
    }

    @Test
    fun foreground_loss_cancels_reporting_and_return_never_replays_stale_progress() = runTest {
        val reporter = SuspendedReporter()
        val publisher = publisher(reporter)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 4), immediate = true)
        runCurrent()
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 5), immediate = false)

        publisher.setForeground(false)
        runCurrent()
        assertTrue(reporter.calls.single().canceled)

        publisher.setForeground(true)
        publisher.reconciled("one")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(4), reporter.positions())

        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 6), immediate = true)
        runCurrent()
        assertEquals(listOf(4, 6), reporter.positions())
        publisher.close()
    }

    @Test
    fun immediate_transition_supersedes_queued_progress_behind_a_progress_report() = runTest {
        val reporter = SuspendedReporter()
        val publisher = publisher(reporter)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 0), immediate = true)
        runCurrent()
        reporter.complete(0, PlayerReportResult.ACCEPTED)
        runCurrent()

        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 7), immediate = false)
        advanceTimeBy(7_500)
        runCurrent()
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 8), immediate = false)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 9), immediate = true)

        reporter.complete(1, PlayerReportResult.ACCEPTED)
        runCurrent()

        assertEquals(listOf(0, 7, 9), reporter.positions())
        assertEquals(PlayerReportState.PAUSED, reporter.calls.last().report.state)
        assertEquals(1, reporter.maxInFlight)
        publisher.close()
    }

    @Test
    fun conflict_clears_rejected_and_pending_reports_and_same_track_reconciliation_needs_fresh_update() = runTest {
        val reporter = SuspendedReporter()
        val notifications = RecordingListener()
        val publisher = publisher(reporter, notifications)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 3), immediate = true)
        runCurrent()
        publisher.update(PlayerReport("one", PlayerReportState.ENDED, 4), immediate = true)

        reporter.complete(0, PlayerReportResult.CONFLICT)
        runCurrent()
        assertEquals(listOf("synchronized=false", "conflict"), notifications.events)
        assertEquals(listOf(3), reporter.positions())

        publisher.reconciled("one")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(3), reporter.positions())

        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 12), immediate = false)
        advanceTimeBy(7_500)
        runCurrent()
        assertEquals(listOf(3, 12), reporter.positions())
        publisher.close()
    }

    @Test
    fun forbidden_and_missing_stop_only_the_current_selection() = runTest {
        listOf(PlayerReportResult.FORBIDDEN, PlayerReportResult.MISSING).forEach { result ->
            val reporter = SuspendedReporter()
            val notifications = RecordingListener()
            val publisher = publisher(reporter, notifications)

            publisher.selectTrack("one")
            publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
            runCurrent()
            reporter.complete(0, result)
            runCurrent()

            publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 2), immediate = true)
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(listOf(1), reporter.positions())
            assertEquals(listOf("synchronized=false", "unavailable"), notifications.events)

            publisher.selectTrack("two")
            publisher.update(PlayerReport("two", PlayerReportState.PAUSED, 0), immediate = true)
            runCurrent()
            assertEquals(listOf("one", "two"), reporter.calls.map { it.report.trackId })
            publisher.close()
        }
    }

    @Test
    fun conflict_synchronization_callback_close_suppresses_stale_secondary_notification() = runTest {
        val reporter = SuspendedReporter()
        lateinit var publisher: PlayerStatePublisher
        val notifications = ReentrantListener {
            publisher.close()
            publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 3), immediate = true)
        }
        publisher = publisher(reporter, notifications, dispatcher = ImmediateDispatcher)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        reporter.complete(0, PlayerReportResult.CONFLICT)
        publisher.selectTrack("two")
        publisher.update(PlayerReport("two", PlayerReportState.PAUSED, 4), immediate = true)

        assertEquals(listOf("synchronized=false"), notifications.events)
        assertEquals(listOf(1), reporter.positions())
        assertEquals(0, reporter.inFlight)
        assertEquals(1, reporter.maxInFlight)
    }

    @Test
    fun conflict_synchronization_callback_foreground_loss_suppresses_stale_secondary_notification() = runTest {
        val reporter = SuspendedReporter()
        lateinit var publisher: PlayerStatePublisher
        val notifications = ReentrantListener {
            publisher.setForeground(false)
            publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 3), immediate = true)
        }
        publisher = publisher(reporter, notifications, dispatcher = ImmediateDispatcher)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        reporter.complete(0, PlayerReportResult.CONFLICT)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 4), immediate = true)

        assertEquals(listOf("synchronized=false"), notifications.events)
        assertEquals(listOf(1), reporter.positions())
        assertEquals(0, reporter.inFlight)
        assertEquals(1, reporter.maxInFlight)

        publisher.setForeground(true)
        publisher.reconciled("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 5), immediate = true)
        assertEquals(listOf(1, 5), reporter.positions())
        assertEquals(1, reporter.maxInFlight)
        reporter.complete(1, PlayerReportResult.ACCEPTED)

        assertEquals(listOf("synchronized=false", "synchronized=true"), notifications.events)
        publisher.close()
    }

    @Test
    fun conflict_synchronization_callback_selection_replacement_suppresses_stale_secondary_notification() = runTest {
        val reporter = SuspendedReporter()
        lateinit var publisher: PlayerStatePublisher
        val notifications = ReentrantListener {
            publisher.selectTrack("two")
            publisher.update(PlayerReport("two", PlayerReportState.PAUSED, 3), immediate = true)
        }
        publisher = publisher(reporter, notifications, dispatcher = ImmediateDispatcher)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        reporter.complete(0, PlayerReportResult.CONFLICT)

        assertEquals(listOf("synchronized=false"), notifications.events)
        assertEquals(listOf("one", "two"), reporter.calls.map { it.report.trackId })
        assertEquals(listOf(1, 3), reporter.positions())
        assertEquals(1, reporter.inFlight)
        assertEquals(1, reporter.maxInFlight)

        reporter.complete(1, PlayerReportResult.ACCEPTED)

        assertEquals(listOf("synchronized=false", "synchronized=true"), notifications.events)
        assertEquals(0, reporter.inFlight)
        publisher.close()
    }

    @Test
    fun conflict_synchronization_callback_same_selection_reconciliation_suppresses_stale_secondary_notification() = runTest {
        val reporter = SuspendedReporter()
        lateinit var publisher: PlayerStatePublisher
        val notifications = ReentrantListener {
            publisher.reconciled("one")
            publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 3), immediate = true)
        }
        publisher = publisher(reporter, notifications, dispatcher = ImmediateDispatcher)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        reporter.complete(0, PlayerReportResult.CONFLICT)

        assertEquals(listOf("synchronized=false"), notifications.events)
        assertEquals(listOf(1, 3), reporter.positions())
        assertEquals(1, reporter.inFlight)
        assertEquals(1, reporter.maxInFlight)

        reporter.complete(1, PlayerReportResult.ACCEPTED)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 4), immediate = true)

        assertEquals(listOf("synchronized=false", "synchronized=true"), notifications.events)
        assertEquals(listOf(1, 3, 4), reporter.positions())
        assertEquals(1, reporter.maxInFlight)
        reporter.complete(2, PlayerReportResult.ACCEPTED)
        publisher.close()
    }

    @Test
    fun forbidden_synchronization_callback_close_suppresses_stale_room_unavailable_notification() = runTest {
        val reporter = SuspendedReporter()
        lateinit var publisher: PlayerStatePublisher
        val notifications = ReentrantListener {
            publisher.close()
            publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 3), immediate = true)
        }
        publisher = publisher(reporter, notifications, dispatcher = ImmediateDispatcher)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        reporter.complete(0, PlayerReportResult.FORBIDDEN)
        publisher.selectTrack("two")
        publisher.update(PlayerReport("two", PlayerReportState.PAUSED, 4), immediate = true)

        assertEquals(listOf("synchronized=false"), notifications.events)
        assertEquals(listOf(1), reporter.positions())
        assertEquals(0, reporter.inFlight)
        assertEquals(1, reporter.maxInFlight)
    }

    @Test
    fun missing_synchronization_callback_selection_replacement_suppresses_stale_room_unavailable_notification() = runTest {
        val reporter = SuspendedReporter()
        lateinit var publisher: PlayerStatePublisher
        val notifications = ReentrantListener {
            publisher.selectTrack("two")
            publisher.update(PlayerReport("two", PlayerReportState.PAUSED, 3), immediate = true)
        }
        publisher = publisher(reporter, notifications, dispatcher = ImmediateDispatcher)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        reporter.complete(0, PlayerReportResult.MISSING)

        assertEquals(listOf("synchronized=false"), notifications.events)
        assertEquals(listOf("one", "two"), reporter.calls.map { it.report.trackId })
        assertEquals(listOf(1, 3), reporter.positions())
        assertEquals(1, reporter.inFlight)
        assertEquals(1, reporter.maxInFlight)

        reporter.complete(1, PlayerReportResult.ACCEPTED)

        assertEquals(listOf("synchronized=false", "synchronized=true"), notifications.events)
        assertEquals(0, reporter.inFlight)
        publisher.close()
    }

    @Test
    fun transient_failure_marks_unsynchronized_and_later_fresh_report_recovers() = runTest {
        val reporter = SuspendedReporter()
        val notifications = RecordingListener()
        val publisher = publisher(reporter, notifications)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 1), immediate = true)
        runCurrent()
        reporter.complete(0, PlayerReportResult.FAILED)
        runCurrent()

        assertEquals(listOf("synchronized=false"), notifications.events)
        assertEquals(listOf(1), reporter.positions())

        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 9), immediate = false)
        advanceTimeBy(7_500)
        runCurrent()
        reporter.complete(1, PlayerReportResult.ACCEPTED)
        runCurrent()

        assertEquals(listOf(1, 9), reporter.positions())
        assertEquals(listOf("synchronized=false", "synchronized=true"), notifications.events)
        publisher.close()
    }

    @Test
    fun pending_synchronous_recovery_cannot_overtake_completed_failure_notification() = runTest {
        lateinit var completeFirst: (PlayerReportResult) -> Unit
        val notifications = RecordingListener()
        var calls = 0
        val publisher = PlayerStatePublisher(
            roomCode = "ABCD",
            hostToken = "host-secret",
            reportPlayer = { _, _, _ ->
                if (calls++ == 0) {
                    suspendCoroutineUninterceptedOrReturn { continuation ->
                        completeFirst = continuation::resume
                        COROUTINE_SUSPENDED
                    }
                } else {
                    PlayerReportResult.ACCEPTED
                }
            },
            parentScope = backgroundScope,
            mutationContext = QueueMutationContext(ImmediateDispatcher) { true },
            listener = notifications,
        )

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        completeFirst(PlayerReportResult.FAILED)

        assertEquals(2, calls)
        assertEquals(
            listOf("synchronized=false", "synchronized=true"),
            notifications.events,
        )
        publisher.close()
    }

    @Test
    fun failure_listener_immediate_update_supersedes_pending_without_double_admission() = runTest {
        val reporter = SuspendedReporter()
        val notifications = mutableListOf<Boolean>()
        lateinit var publisher: PlayerStatePublisher
        publisher = publisher(
            reporter = reporter,
            dispatcher = ImmediateDispatcher,
            listener = object : PlayerStatePublisher.Listener {
                override fun onSynchronizationChanged(synchronized: Boolean) {
                    notifications += synchronized
                    if (!synchronized) {
                        publisher.update(
                            PlayerReport("one", PlayerReportState.PAUSED, 3),
                            immediate = true,
                        )
                    }
                }

                override fun onConflict() = Unit
                override fun onRoomUnavailable() = Unit
            },
        )

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        reporter.complete(0, PlayerReportResult.FAILED)

        assertEquals(listOf(1, 3), reporter.positions())
        assertEquals(1, reporter.maxInFlight)
        assertEquals(1, reporter.inFlight)
        assertEquals(listOf(false), notifications)

        reporter.complete(1, PlayerReportResult.ACCEPTED)

        assertEquals(listOf(false, true), notifications)
        assertEquals(0, reporter.inFlight)
        publisher.close()
    }

    @Test
    fun failure_listener_selection_replacement_discards_old_pending_and_starts_new_selection() = runTest {
        val reporter = SuspendedReporter()
        val notifications = mutableListOf<Boolean>()
        lateinit var publisher: PlayerStatePublisher
        publisher = publisher(
            reporter = reporter,
            dispatcher = ImmediateDispatcher,
            listener = object : PlayerStatePublisher.Listener {
                override fun onSynchronizationChanged(synchronized: Boolean) {
                    notifications += synchronized
                    if (!synchronized) {
                        publisher.selectTrack("two")
                        publisher.update(
                            PlayerReport("two", PlayerReportState.PAUSED, 3),
                            immediate = true,
                        )
                    }
                }

                override fun onConflict() = Unit
                override fun onRoomUnavailable() = Unit
            },
        )

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        reporter.complete(0, PlayerReportResult.FAILED)

        assertEquals(listOf("one", "two"), reporter.calls.map { it.report.trackId })
        assertEquals(listOf(1, 3), reporter.positions())
        assertEquals(1, reporter.maxInFlight)

        reporter.complete(1, PlayerReportResult.ACCEPTED)

        assertEquals(listOf(false, true), notifications)
        publisher.close()
    }

    @Test
    fun failure_listener_foreground_loss_discards_pending_and_reentrant_update() = runTest {
        val reporter = SuspendedReporter()
        val notifications = mutableListOf<Boolean>()
        lateinit var publisher: PlayerStatePublisher
        publisher = publisher(
            reporter = reporter,
            dispatcher = ImmediateDispatcher,
            listener = object : PlayerStatePublisher.Listener {
                override fun onSynchronizationChanged(synchronized: Boolean) {
                    notifications += synchronized
                    if (!synchronized) {
                        publisher.setForeground(false)
                        publisher.update(
                            PlayerReport("one", PlayerReportState.PAUSED, 3),
                            immediate = true,
                        )
                    }
                }

                override fun onConflict() = Unit
                override fun onRoomUnavailable() = Unit
            },
        )

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        reporter.complete(0, PlayerReportResult.FAILED)

        assertEquals(listOf(1), reporter.positions())
        assertEquals(0, reporter.inFlight)
        assertEquals(listOf(false), notifications)

        publisher.setForeground(true)
        publisher.reconciled("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 4), immediate = true)
        assertEquals(listOf(1, 4), reporter.positions())
        reporter.complete(1, PlayerReportResult.ACCEPTED)

        assertEquals(listOf(false, true), notifications)
        publisher.close()
    }

    @Test
    fun failure_listener_close_discards_pending_and_reentrant_update() = runTest {
        val reporter = SuspendedReporter()
        val notifications = mutableListOf<Boolean>()
        lateinit var publisher: PlayerStatePublisher
        publisher = publisher(
            reporter = reporter,
            dispatcher = ImmediateDispatcher,
            listener = object : PlayerStatePublisher.Listener {
                override fun onSynchronizationChanged(synchronized: Boolean) {
                    notifications += synchronized
                    if (!synchronized) {
                        publisher.close()
                        publisher.update(
                            PlayerReport("one", PlayerReportState.PAUSED, 3),
                            immediate = true,
                        )
                    }
                }

                override fun onConflict() = Unit
                override fun onRoomUnavailable() = Unit
            },
        )

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        reporter.complete(0, PlayerReportResult.FAILED)

        assertEquals(listOf(1), reporter.positions())
        assertEquals(0, reporter.inFlight)
        assertEquals(listOf(false), notifications)
    }

    @Test
    fun listener_failures_cannot_wedge_reconciliation_pending_drain_or_future_reporting() = runTest {
        val reporter = SuspendedReporter()
        val notifications = RecordingListener(throwAfterNotification = true)
        val publisher = publisher(reporter, notifications)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 1), immediate = true)
        runCurrent()
        reporter.complete(0, PlayerReportResult.CONFLICT)
        runCurrent()

        publisher.reconciled("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 2), immediate = true)
        runCurrent()
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 3), immediate = true)
        reporter.complete(1, PlayerReportResult.FAILED)
        runCurrent()
        reporter.complete(2, PlayerReportResult.ACCEPTED)
        runCurrent()

        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 4), immediate = true)
        runCurrent()

        assertEquals(listOf(1, 2, 3, 4), reporter.positions())
        assertEquals(
            listOf("synchronized=false", "conflict", "synchronized=true"),
            notifications.events,
        )
        publisher.close()
    }

    @Test
    fun non_playing_reports_are_immediate_only_and_suspending_progress_drops_queued_playing() = runTest {
        val reporter = SuspendedReporter()
        val publisher = publisher(reporter)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 4), immediate = true)
        runCurrent()
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 5), immediate = true)
        publisher.suspendPlayingProgress()
        reporter.complete(0, PlayerReportResult.ACCEPTED)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(listOf(4), reporter.positions())
        publisher.update(PlayerReport("one", PlayerReportState.ERROR, 6), immediate = true)
        runCurrent()
        reporter.complete(1, PlayerReportResult.ACCEPTED)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(PlayerReportState.PAUSED, PlayerReportState.ERROR), reporter.states())
        publisher.close()
    }

    @Test
    fun close_cancels_active_and_periodic_jobs_and_late_completion_is_ignored() = runTest {
        val reporter = SuspendedReporter()
        val notifications = RecordingListener()
        val publisher = publisher(reporter, notifications)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 1), immediate = true)
        runCurrent()
        reporter.calls.single().ignoreCancellation = true

        publisher.close()
        runCurrent()
        assertTrue(reporter.calls.single().canceled)
        reporter.complete(0, PlayerReportResult.CONFLICT)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 2), immediate = true)
        runCurrent()

        assertEquals(1, reporter.calls.size)
        assertTrue(notifications.events.isEmpty())
    }

    @Test
    fun parent_session_cancellation_stops_reporting_and_ignores_late_completion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val parentJob = SupervisorJob(backgroundScope.coroutineContext[Job])
        val parentScope = CoroutineScope(backgroundScope.coroutineContext + parentJob)
        val reporter = SuspendedReporter()
        val notifications = RecordingListener()
        val publisher = publisher(reporter, notifications, parentScope, dispatcher)

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 1), immediate = true)
        runCurrent()
        reporter.calls.single().ignoreCancellation = true

        parentJob.cancel()
        runCurrent()
        assertTrue(reporter.calls.single().canceled)
        reporter.complete(0, PlayerReportResult.CONFLICT)
        runCurrent()
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(1, reporter.calls.size)
        assertTrue(notifications.events.isEmpty())
    }

    @Test
    fun thrown_report_exception_is_a_transient_failure_and_does_not_kill_the_session() = runTest {
        var attempts = 0
        val notifications = RecordingListener()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val publisher = PlayerStatePublisher(
            roomCode = "ABCD",
            hostToken = "host-secret",
            reportPlayer = { _, _, _ ->
                attempts++
                if (attempts == 1) throw IllegalStateException("network seam")
                PlayerReportResult.ACCEPTED
            },
            parentScope = backgroundScope,
            mutationContext = QueueMutationContext(dispatcher) { true },
            listener = notifications,
        )

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 1), immediate = true)
        runCurrent()
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)
        runCurrent()

        assertEquals(2, attempts)
        assertEquals(listOf("synchronized=false", "synchronized=true"), notifications.events)
        publisher.close()
    }

    @Test
    fun unobserved_publisher_still_quarantines_conflicts_and_recovers_on_a_fresh_selection() = runTest {
        val reporter = SuspendedReporter()
        val publisher = PlayerStatePublisher(
            roomCode = "ABCD", hostToken = "host-secret", reportPlayer = reporter::report,
            parentScope = backgroundScope,
            mutationContext = QueueMutationContext(StandardTestDispatcher(testScheduler)) { true },
        )
        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 1), immediate = true)
        runCurrent()
        reporter.complete(0, PlayerReportResult.CONFLICT)
        runCurrent()
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 2), immediate = true)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(listOf(1), reporter.positions())

        publisher.reconciled("two")
        publisher.update(PlayerReport("two", PlayerReportState.PAUSED, 3), immediate = true)
        runCurrent()
        assertEquals(listOf("one", "two"), reporter.calls.map { it.report.trackId })
        reporter.complete(1, PlayerReportResult.MISSING)
        runCurrent()
        publisher.update(PlayerReport("two", PlayerReportState.PAUSED, 4), immediate = true)
        runCurrent()
        assertEquals(listOf(1, 3), reporter.positions())
        publisher.close()
    }

    private object ImmediateDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = false

        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }

    private fun TestScope.publisher(
        reporter: SuspendedReporter,
        listener: PlayerStatePublisher.Listener = RecordingListener(),
        parentScope: CoroutineScope = backgroundScope,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = PlayerStatePublisher(
        roomCode = "ABCD",
        hostToken = "host-secret",
        reportPlayer = reporter::report,
        parentScope = parentScope,
        mutationContext = QueueMutationContext(dispatcher) { true },
        listener = listener,
    )

    private class ReentrantListener(
        private val onUnsynchronized: () -> Unit,
    ) : PlayerStatePublisher.Listener {
        val events = mutableListOf<String>()

        override fun onSynchronizationChanged(synchronized: Boolean) {
            events += "synchronized=$synchronized"
            if (!synchronized) onUnsynchronized()
        }

        override fun onConflict() {
            events += "conflict"
        }

        override fun onRoomUnavailable() {
            events += "unavailable"
        }
    }

    private class RecordingListener(
        private val throwAfterNotification: Boolean = false,
    ) : PlayerStatePublisher.Listener {
        val events = mutableListOf<String>()

        override fun onSynchronizationChanged(synchronized: Boolean) {
            events += "synchronized=$synchronized"
            maybeThrow()
        }

        override fun onConflict() {
            events += "conflict"
            maybeThrow()
        }

        override fun onRoomUnavailable() {
            events += "unavailable"
            maybeThrow()
        }

        private fun maybeThrow() {
            if (throwAfterNotification) throw IllegalStateException("listener failure")
        }
    }

    private class SuspendedReporter {
        data class Call(
            val report: PlayerReport,
            val result: CompletableDeferred<PlayerReportResult> = CompletableDeferred(),
            var ignoreCancellation: Boolean = false,
            var canceled: Boolean = false,
        )

        val calls = mutableListOf<Call>()
        var inFlight = 0
        var maxInFlight = 0

        suspend fun report(roomCode: String, hostToken: String, report: PlayerReport): PlayerReportResult {
            assertEquals("ABCD", roomCode)
            assertEquals("host-secret", hostToken)
            val call = Call(report)
            calls += call
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            return try {
                try {
                    call.result.await()
                } catch (canceled: CancellationException) {
                    call.canceled = true
                    if (call.ignoreCancellation) {
                        withContext(NonCancellable) { call.result.await() }
                    } else {
                        throw canceled
                    }
                }
            } finally {
                inFlight--
            }
        }

        fun complete(index: Int, result: PlayerReportResult) {
            assertFalse(calls[index].result.isCompleted)
            calls[index].result.complete(result)
        }

        fun positions(): List<Int> = calls.map { it.report.positionSeconds }
        fun states(): List<PlayerReportState> = calls.map { it.report.state }
    }
}
