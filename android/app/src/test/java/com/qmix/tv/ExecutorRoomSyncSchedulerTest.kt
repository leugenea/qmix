package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExecutorRoomSyncSchedulerTest {
    @Test
    fun scheduled_work_runs_and_can_be_cancelled() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val scheduler = ExecutorRoomSyncScheduler(executor)
            val ran = CountDownLatch(1)
            scheduler.schedule(0L, ran::countDown)
            assertEquals(true, ran.await(2, TimeUnit.SECONDS))

            val workerBlocked = CountDownLatch(1)
            val releaseWorker = CountDownLatch(1)
            executor.execute {
                workerBlocked.countDown()
                releaseWorker.await()
            }
            assertEquals(true, workerBlocked.await(2, TimeUnit.SECONDS))
            val canceledTaskRan = CountDownLatch(1)
            val canceled = scheduler.schedule(0L, canceledTaskRan::countDown)
            canceled.cancel()
            val queueDrained = CountDownLatch(1)
            scheduler.schedule(0L, queueDrained::countDown)
            releaseWorker.countDown()

            assertEquals(true, queueDrained.await(2, TimeUnit.SECONDS))
            assertEquals(1L, canceledTaskRan.count)
        } finally {
            executor.shutdownNow()
        }
    }
}
