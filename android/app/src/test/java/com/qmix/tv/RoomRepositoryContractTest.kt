package com.qmix.tv

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomRepositoryContractTest {
    /** qmix#179: observation is cold and preserves repeated equal snapshots. */
    @Test
    fun observation_is_a_cold_non_conflated_flow() = runTest {
        var collections = 0
        val expected = RoomSyncState.Active(
            roomCode = "ABCD",
            room = null,
            freshness = Freshness.LOADING,
            connection = LiveConnection.CONNECTING,
        )
        val repository: RoomRepository = object : RoomRepository {
            override fun observe(roomCode: String): Flow<RoomSyncState> = flow {
                collections++
                emit(expected)
                emit(expected)
            }
        }

        val observation = repository.observe("ABCD")
        assertEquals(0, collections)

        assertEquals(listOf(expected, expected), observation.toList())
        assertEquals(1, collections)
        assertEquals(listOf(expected, expected), observation.toList())
        assertEquals(2, collections)
    }
}
