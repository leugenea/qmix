package com.qmix.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** qmix#211: only active local playback in a live room prevents ambient mode. */
class KeepScreenOnTest {
    private val invite = GuestInvite("ABCD", "https://guest.example/r/ABCD")
    private val room = HostingState.LiveRoom(invite, RoomSyncState.Missing("ABCD"))

    @Test
    fun buffering_and_playing_keep_screen_on() {
        listOf(LocalPlaybackStatus.BUFFERING, LocalPlaybackStatus.PLAYING).forEach { status ->
            assertTrue(status.name, shouldKeepScreenOn(room.copy(
                playback = LocalPlaybackState(trackId = "current", status = status),
            )))
        }
    }

    @Test
    fun inactive_local_playback_clears_keep_screen_on() {
        listOf(
            LocalPlaybackStatus.IDLE,
            LocalPlaybackStatus.PAUSED,
            LocalPlaybackStatus.COMPLETED,
            LocalPlaybackStatus.ERROR,
        ).forEach { status ->
            assertFalse(status.name, shouldKeepScreenOn(room.copy(
                playback = LocalPlaybackState(trackId = "current", status = status),
            )))
        }
    }

    @Test
    fun no_live_room_clears_keep_screen_on() {
        assertFalse(shouldKeepScreenOn(HostingState.Ending))
        assertFalse(shouldKeepScreenOn(HostingState.Setup("", "")))
        assertFalse(shouldKeepScreenOn(HostingState.Invitation(invite)))
    }
}
