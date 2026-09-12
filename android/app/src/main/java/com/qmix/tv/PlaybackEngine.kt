package com.qmix.tv

interface PlaybackEngine {
    fun play(streamUrl: String)

    fun stop()
}
