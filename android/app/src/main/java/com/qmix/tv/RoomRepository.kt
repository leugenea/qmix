package com.qmix.tv

data class RoomSnapshot(
    val code: String,
    val currentStreamUrl: String?,
)

fun interface RoomRepository {
    fun observe(
        roomCode: String,
        onUpdate: (RoomSnapshot) -> Unit,
    ): AutoCloseable
}
