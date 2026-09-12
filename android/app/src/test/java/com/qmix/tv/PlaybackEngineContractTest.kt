package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEngineContractTest {
    @Test
    fun engine_accepts_stream_and_can_stop() {
        var playedUrl: String? = null
        var stopped = false
        val engine: PlaybackEngine = object : PlaybackEngine {
            override fun play(streamUrl: String) {
                playedUrl = streamUrl
            }

            override fun stop() {
                stopped = true
            }
        }

        engine.play("https://qmix.test/current/stream")
        engine.stop()

        assertEquals("https://qmix.test/current/stream", playedUrl)
        assertTrue(stopped)
    }
}
