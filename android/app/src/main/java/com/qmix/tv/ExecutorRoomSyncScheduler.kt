package com.qmix.tv

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ExecutorRoomSyncScheduler(
    private val executor: ScheduledExecutorService,
) : RoomSyncScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): Cancelable {
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return Cancelable { future.cancel(false) }
    }
}
