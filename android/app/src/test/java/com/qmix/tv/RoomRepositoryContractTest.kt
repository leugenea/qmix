package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomRepositoryContractTest {
    @Test
    fun observer_receives_room_state_and_can_unsubscribe() {
        var observed: RoomSyncState? = null
        var closed = false
        val repository: RoomRepository = object : RoomRepository {
            override fun observe(roomCode: String, onUpdate: (RoomSyncState) -> Unit): AutoCloseable {
                onUpdate(
                    RoomSyncState.Active(
                        roomCode = roomCode,
                        room = null,
                        freshness = Freshness.LOADING,
                        connection = LiveConnection.CONNECTING,
                    ),
                )
                return AutoCloseable { closed = true }
            }
        }

        val subscription = repository.observe("ABCD") { observed = it }
        subscription.close()

        assertEquals("ABCD", observed?.roomCode)
        assertEquals(true, closed)
    }
}
