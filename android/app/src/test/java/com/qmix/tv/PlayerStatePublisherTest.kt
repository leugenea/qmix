package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue
import java.util.concurrent.Executor

class PlayerStatePublisherTest {
    @Test
    fun progress_uses_virtual_time_and_coalesces_behind_one_in_flight_report() {
        val client = RecordingPlayerReportClient()
        val scheduler = VirtualScheduler()
        val synchronization = mutableListOf<Boolean>()
        val publisher = PlayerStatePublisher(
            roomCode = "ABCD",
            hostToken = "host-secret",
            client = client,
            scheduler = scheduler,
            dispatcher = Executor { it.run() },
            listener = listener(synchronization),
        )

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 0), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 6), immediate = false)
        scheduler.advanceBy(7_500)
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 8), immediate = false)

        assertEquals(listOf(0), client.calls.map { it.report.positionSeconds })
        assertEquals(1, client.maxInFlight)
        client.complete(PlayerReportResult.ACCEPTED)
        assertEquals(listOf(0, 8), client.calls.map { it.report.positionSeconds })
        assertEquals(1, client.maxInFlight)
        client.complete(PlayerReportResult.ACCEPTED)

        scheduler.advanceBy(7_499)
        assertEquals(2, client.calls.size)
        scheduler.advanceBy(1)
        assertEquals(listOf(0, 8, 8), client.calls.map { it.report.positionSeconds })
        assertTrue(synchronization.isEmpty())
    }

    @Test
    fun track_change_cancels_and_discards_old_reports_even_when_the_old_callback_arrives_late() {
        val client = RecordingPlayerReportClient()
        val scheduler = VirtualScheduler()
        val conflicts = mutableListOf<Unit>()
        val publisher = PlayerStatePublisher(
            "ABCD",
            "host-secret",
            client,
            scheduler,
            Executor { it.run() },
            object : PlayerStatePublisher.Listener {
                override fun onSynchronizationChanged(synchronized: Boolean) = Unit
                override fun onConflict() { conflicts += Unit }
                override fun onRoomUnavailable() = Unit
            },
        )
        publisher.selectTrack("old")
        publisher.update(PlayerReport("old", PlayerReportState.PAUSED, 4), immediate = true)
        publisher.update(PlayerReport("old", PlayerReportState.ENDED, 5), immediate = true)

        publisher.selectTrack("new")
        publisher.update(PlayerReport("new", PlayerReportState.PLAYING, 0), immediate = true)
        client.complete(PlayerReportResult.CONFLICT, 0)

        assertTrue(client.calls[0].canceled)
        assertEquals(listOf("old", "new"), client.calls.map { it.report.trackId })
        assertTrue(conflicts.isEmpty())
        assertEquals(1, client.inFlight)
    }

    @Test
    fun failure_marks_unsynchronized_and_a_later_progress_report_recovers_without_retrying_the_old_operation() {
        val client = RecordingPlayerReportClient()
        val scheduler = VirtualScheduler()
        val synchronization = mutableListOf<Boolean>()
        val publisher = PlayerStatePublisher(
            "ABCD",
            "host-secret",
            client,
            scheduler,
            Executor { it.run() },
            listener(synchronization),
        )
        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 1), immediate = true)

        client.complete(PlayerReportResult.FAILED)
        assertEquals(listOf(false), synchronization)
        assertEquals(1, client.calls.size)

        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 9), immediate = false)
        scheduler.advanceBy(7_500)
        assertEquals(listOf(1, 9), client.calls.map { it.report.positionSeconds })
        client.complete(PlayerReportResult.ACCEPTED)
        assertEquals(listOf(false, true), synchronization)
    }

    @Test
    fun conflict_reconciles_once_without_retry_while_forbidden_and_missing_stop_reporting() {
        listOf(
            PlayerReportResult.CONFLICT to Pair(1, 0),
            PlayerReportResult.FORBIDDEN to Pair(0, 1),
            PlayerReportResult.MISSING to Pair(0, 1),
        ).forEach { (result, expected) ->
            val client = RecordingPlayerReportClient()
            val scheduler = VirtualScheduler()
            var conflicts = 0
            var unavailable = 0
            val publisher = PlayerStatePublisher(
                "ABCD",
                "host-secret",
                client,
                scheduler,
                Executor { it.run() },
                object : PlayerStatePublisher.Listener {
                    override fun onSynchronizationChanged(synchronized: Boolean) = Unit
                    override fun onConflict() { conflicts++ }
                    override fun onRoomUnavailable() { unavailable++ }
                },
            )
            publisher.selectTrack("one")
            publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 3), immediate = true)
            publisher.update(PlayerReport("one", PlayerReportState.ENDED, 4), immediate = true)

            client.complete(result)
            publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 9), immediate = true)
            scheduler.advanceBy(30_000)

            assertEquals(expected.first, conflicts)
            assertEquals(expected.second, unavailable)
            assertEquals(1, client.calls.size)
        }
    }

    @Test
    fun successful_same_track_conflict_reconciliation_waits_for_a_fresh_local_update() {
        val client = RecordingPlayerReportClient()
        val scheduler = VirtualScheduler()
        val publisher = PlayerStatePublisher(
            "ABCD",
            "host-secret",
            client,
            scheduler,
            Executor { it.run() },
            listener(mutableListOf()),
        )
        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 3), immediate = true)

        client.complete(PlayerReportResult.CONFLICT)
        publisher.reconciled("one")
        scheduler.advanceBy(7_501)

        assertEquals(listOf(3), client.calls.map { it.report.positionSeconds })

        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 12), immediate = false)
        scheduler.advanceBy(7_500)

        assertEquals(listOf(3, 12), client.calls.map { it.report.positionSeconds })
    }

    @Test
    fun later_same_track_snapshot_after_failed_reconciliation_does_not_replay_rejected_report() {
        val client = RecordingPlayerReportClient()
        val scheduler = VirtualScheduler()
        val publisher = PlayerStatePublisher(
            "ABCD",
            "host-secret",
            client,
            scheduler,
            Executor { it.run() },
            listener(mutableListOf()),
        )
        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 4), immediate = true)

        client.complete(PlayerReportResult.CONFLICT)
        scheduler.advanceBy(30_000) // Conflict reconciliation fails without calling reconciled.
        publisher.reconciled("one") // A later fresh same-track SSE snapshot arrives.
        scheduler.advanceBy(7_501)

        assertEquals(listOf(4), client.calls.map { it.report.positionSeconds })
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 15), immediate = true)
        assertEquals(listOf(4, 15), client.calls.map { it.report.positionSeconds })
    }

    @Test
    fun throwing_synchronization_and_conflict_listeners_do_not_block_reconciliation_or_recovery() {
        val client = RecordingPlayerReportClient()
        val scheduler = VirtualScheduler()
        val notifications = mutableListOf<String>()
        val publisher = PlayerStatePublisher(
            "ABCD",
            "host-secret",
            client,
            scheduler,
            Executor { it.run() },
            object : PlayerStatePublisher.Listener {
                override fun onSynchronizationChanged(synchronized: Boolean) {
                    notifications += "synchronized=$synchronized"
                    throw IllegalStateException("listener failure")
                }

                override fun onConflict() {
                    notifications += "conflict"
                    throw IllegalStateException("listener failure")
                }

                override fun onRoomUnavailable() = Unit
            },
        )
        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 1), immediate = true)

        client.complete(PlayerReportResult.CONFLICT)
        publisher.reconciled("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 2), immediate = true)
        client.complete(PlayerReportResult.ACCEPTED)
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 3), immediate = true)

        assertEquals(
            listOf("synchronized=false", "conflict", "synchronized=true"),
            notifications,
        )
        assertEquals(listOf(1, 2, 3), client.calls.map { it.report.positionSeconds })
    }

    @Test
    fun throwing_failure_listener_does_not_block_pending_drain_or_dispatcher_progress() {
        val client = RecordingPlayerReportClient()
        val notifications = mutableListOf<Boolean>()
        val publisher = PlayerStatePublisher(
            "ABCD",
            "host-secret",
            client,
            VirtualScheduler(),
            Executor { it.run() },
            object : PlayerStatePublisher.Listener {
                override fun onSynchronizationChanged(synchronized: Boolean) {
                    notifications += synchronized
                    throw IllegalStateException("listener failure")
                }

                override fun onConflict() = Unit
                override fun onRoomUnavailable() = Unit
            },
        )
        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        client.complete(PlayerReportResult.FAILED)
        assertEquals(listOf(1, 2), client.calls.map { it.report.positionSeconds })
        client.complete(PlayerReportResult.ACCEPTED)
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 3), immediate = true)

        assertEquals(listOf(false, true), notifications)
        assertEquals(listOf(1, 2, 3), client.calls.map { it.report.positionSeconds })
    }

    @Test
    fun throwing_unavailable_listeners_do_not_block_stop_or_new_track_recovery() {
        val client = RecordingPlayerReportClient()
        val notifications = mutableListOf<String>()
        val publisher = PlayerStatePublisher(
            "ABCD",
            "host-secret",
            client,
            VirtualScheduler(),
            Executor { it.run() },
            object : PlayerStatePublisher.Listener {
                override fun onSynchronizationChanged(synchronized: Boolean) {
                    notifications += "synchronized=$synchronized"
                    throw IllegalStateException("listener failure")
                }

                override fun onConflict() = Unit
                override fun onRoomUnavailable() {
                    notifications += "unavailable"
                    throw IllegalStateException("listener failure")
                }
            },
        )
        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 1), immediate = true)

        client.complete(PlayerReportResult.FORBIDDEN)
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 2), immediate = true)
        publisher.selectTrack("two")
        publisher.update(PlayerReport("two", PlayerReportState.PLAYING, 0), immediate = true)
        client.complete(PlayerReportResult.ACCEPTED)

        assertEquals(
            listOf("synchronized=false", "unavailable", "synchronized=true"),
            notifications,
        )
        assertEquals(listOf("one", "two"), client.calls.map { it.report.trackId })
    }

    @Test
    fun synchronous_callback_before_handle_return_does_not_leave_a_stale_in_flight_request() {
        val client = SynchronousPlayerReportClient(PlayerReportResult.ACCEPTED)
        val publisher = PlayerStatePublisher(
            "ABCD",
            "host-secret",
            client,
            VirtualScheduler(),
            Executor { it.run() },
        )
        publisher.selectTrack("one")

        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 1), immediate = true)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)

        assertEquals(listOf(1, 2), client.reports.map { it.positionSeconds })
        assertEquals(0, client.cancelCount)
    }

    @Test
    fun queued_dispatcher_preserves_selection_update_callback_and_follow_up_order() {
        val client = RecordingPlayerReportClient()
        val dispatcher = QueuedExecutor()
        val publisher = PlayerStatePublisher(
            "ABCD",
            "host-secret",
            client,
            VirtualScheduler(),
            dispatcher,
        )

        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 1), immediate = true)
        assertTrue(client.calls.isEmpty())
        dispatcher.runAll()
        assertEquals(listOf(1), client.calls.map { it.report.positionSeconds })

        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 2), immediate = true)
        client.complete(PlayerReportResult.ACCEPTED)
        assertEquals(listOf(1), client.calls.map { it.report.positionSeconds })
        dispatcher.runAll()

        assertEquals(listOf(1, 2), client.calls.map { it.report.positionSeconds })
        assertEquals(1, client.inFlight)
    }

    @Test
    fun close_cancels_the_active_handle_and_ignores_its_late_callback() {
        val client = RecordingPlayerReportClient()
        val notifications = mutableListOf<Boolean>()
        val scheduler = VirtualScheduler()
        val publisher = PlayerStatePublisher(
            "ABCD",
            "host-secret",
            client,
            scheduler,
            Executor { it.run() },
            listener(notifications),
        )
        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 1), immediate = true)

        publisher.close()
        assertTrue(client.calls.single().canceled)
        client.complete(PlayerReportResult.CONFLICT)
        scheduler.advanceBy(30_000)
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 2), immediate = true)

        assertEquals(1, client.calls.size)
        assertTrue(notifications.isEmpty())
        assertEquals(0, client.inFlight)
    }

    @Test
    fun periodic_reporting_repeats_only_playing_progress() {
        listOf(PlayerReportState.PAUSED, PlayerReportState.ERROR, PlayerReportState.ENDED).forEach { state ->
            val client = RecordingPlayerReportClient()
            val scheduler = VirtualScheduler()
            val publisher = PlayerStatePublisher(
                "ABCD",
                "host-secret",
                client,
                scheduler,
                Executor { it.run() },
            )
            publisher.selectTrack("one")
            publisher.update(PlayerReport("one", state, 6), immediate = true)
            client.complete(PlayerReportResult.ACCEPTED)

            scheduler.advanceBy(30_000)

            assertEquals("$state must remain immediate-only", listOf(state), client.calls.map { it.report.state })
        }
    }

    @Test
    fun foreground_recovery_does_not_republish_the_stale_pre_stop_playing_position() {
        val client = RecordingPlayerReportClient()
        val scheduler = VirtualScheduler()
        val publisher = PlayerStatePublisher(
            "ABCD",
            "host-secret",
            client,
            scheduler,
            Executor { it.run() },
            listener(mutableListOf()),
        )
        publisher.selectTrack("one")
        publisher.update(PlayerReport("one", PlayerReportState.PLAYING, 6), immediate = true)
        client.complete(PlayerReportResult.ACCEPTED)

        publisher.setForeground(false)
        publisher.setForeground(true)
        publisher.reconciled("one")
        scheduler.advanceBy(30_000)

        assertEquals(1, client.calls.size)
        publisher.update(PlayerReport("one", PlayerReportState.PAUSED, 6), immediate = true)
        assertEquals(PlayerReportState.PAUSED, client.calls.last().report.state)
    }

    private fun listener(synchronization: MutableList<Boolean>) = object : PlayerStatePublisher.Listener {
        override fun onSynchronizationChanged(synchronized: Boolean) {
            synchronization += synchronized
        }

        override fun onConflict() = Unit
        override fun onRoomUnavailable() = Unit
    }

    private class SynchronousPlayerReportClient(
        private val result: PlayerReportResult,
    ) : PlayerReportClient {
        val reports = mutableListOf<PlayerReport>()
        var cancelCount = 0

        override fun reportPlayer(
            roomCode: String,
            hostToken: String,
            report: PlayerReport,
            callback: (PlayerReportResult) -> Unit,
        ): Cancelable {
            reports += report
            callback(result)
            return Cancelable { cancelCount++ }
        }
    }

    private class QueuedExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            commands.addLast(command)
        }

        fun runAll() {
            while (commands.isNotEmpty()) commands.removeFirst().run()
        }
    }

    private class RecordingPlayerReportClient : PlayerReportClient {
        data class Call(
            val report: PlayerReport,
            val callback: (PlayerReportResult) -> Unit,
            var canceled: Boolean = false,
            var completed: Boolean = false,
        )

        val calls = mutableListOf<Call>()
        var inFlight = 0
        var maxInFlight = 0

        override fun reportPlayer(
            roomCode: String,
            hostToken: String,
            report: PlayerReport,
            callback: (PlayerReportResult) -> Unit,
        ): Cancelable {
            assertEquals("ABCD", roomCode)
            assertEquals("host-secret", hostToken)
            val call = Call(report, callback)
            calls += call
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            return Cancelable {
                if (!call.completed && !call.canceled) {
                    call.canceled = true
                    inFlight--
                }
            }
        }

        fun complete(result: PlayerReportResult, index: Int = calls.lastIndex) {
            val call = calls[index]
            assertFalse(call.completed)
            call.completed = true
            if (!call.canceled) inFlight--
            call.callback(result)
        }
    }

    private class VirtualScheduler : RoomSyncScheduler {
        private data class Task(
            val at: Long,
            val order: Long,
            val action: () -> Unit,
            var canceled: Boolean = false,
        ) : Comparable<Task> {
            override fun compareTo(other: Task): Int = compareValuesBy(this, other, Task::at, Task::order)
        }

        private val tasks = PriorityQueue<Task>()
        private var now = 0L
        private var order = 0L

        override fun schedule(delayMillis: Long, action: () -> Unit): Cancelable {
            val task = Task(now + delayMillis, order++, action)
            tasks += task
            return Cancelable { task.canceled = true }
        }

        fun advanceBy(millis: Long) {
            val target = now + millis
            while (tasks.peek()?.at?.let { it <= target } == true) {
                val task = tasks.remove()
                now = task.at
                if (!task.canceled) task.action()
            }
            now = target
        }
    }
}
