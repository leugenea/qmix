package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomRepositoryContractTest {
    @Test
    fun observer_receives_room_snapshot_and_can_unsubscribe() {
        var observed: RoomSnapshot? = null
        var closed = false
        val repository: RoomRepository = object : RoomRepository {
            override fun observe(roomCode: String, onUpdate: (RoomSnapshot) -> Unit): AutoCloseable {
                onUpdate(RoomSnapshot(code = roomCode, currentStreamUrl = null))
                return AutoCloseable { closed = true }
            }
        }

        val subscription = repository.observe("ABCD") { observed = it }
        subscription.close()

        assertEquals("ABCD", observed?.code)
        assertEquals(true, closed)
    }
}
