package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class SerialExecutorTest {
    @Test(timeout = 5_000L)
    fun commands_never_overlap_on_a_parallel_delegate() {
        val executor = SerialExecutor(Executor { command -> Thread(command).start() })
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)

        executor.execute {
            firstStarted.countDown()
            releaseFirst.await()
        }
        firstStarted.await()
        executor.execute { secondStarted.countDown() }

        assertEquals(false, secondStarted.await(100, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()
        assertEquals(true, secondStarted.await(2, TimeUnit.SECONDS))
    }

    @Test(timeout = 5_000L)
    fun a_command_arriving_during_rejection_is_resubmitted_not_dropped() {
        val firstSubmissionEntered = CountDownLatch(1)
        val releaseFirstSubmission = CountDownLatch(1)
        var attempts = 0
        val executor = SerialExecutor(Executor { command ->
            if (attempts++ == 0) {
                firstSubmissionEntered.countDown()
                releaseFirstSubmission.await()
                throw RejectedExecutionException("first submission")
            }
            command.run()
        })
        val firstRejected = CountDownLatch(1)
        val secondReturned = CountDownLatch(1)
        var secondRan = false
        Thread {
            try {
                executor.execute { }
            } catch (_: RejectedExecutionException) {
                firstRejected.countDown()
            }
        }.start()
        firstSubmissionEntered.await()
        Thread {
            executor.execute { secondRan = true }
            secondReturned.countDown()
        }.start()

        assertEquals(false, secondReturned.await(100, TimeUnit.MILLISECONDS))
        releaseFirstSubmission.countDown()

        assertEquals(true, firstRejected.await(2, TimeUnit.SECONDS))
        assertEquals(true, secondReturned.await(2, TimeUnit.SECONDS))
        assertEquals(true, secondRan)
    }

    @Test
    fun delegate_rejection_does_not_wedge_later_commands() {
        var attempts = 0
        val executor = SerialExecutor(Executor { command ->
            if (attempts++ == 0) throw RejectedExecutionException("first submission")
            command.run()
        })
        var rejectedCommandRan = false
        var laterCommandRan = false

        assertThrows(RejectedExecutionException::class.java) {
            executor.execute { rejectedCommandRan = true }
        }
        executor.execute { laterCommandRan = true }

        assertEquals(false, rejectedCommandRan)
        assertEquals(true, laterCommandRan)
    }
}
