package com.qmix.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import java.io.IOException

object PlaybackEngines {
    @MainThread
    fun create(context: Context, lifecycleOwner: LifecycleOwner? = null): PlaybackEngine {
        requireMainPlaybackThread()
        return create(ExoPlayerBackend(context.applicationContext), lifecycleOwner)
    }

    @MainThread
    internal fun create(backend: PlayerBackend, lifecycleOwner: LifecycleOwner? = null): PlaybackEngine {
        requireMainPlaybackThread()
        val engine = Media3PlaybackEngine(backend, ::requireMainPlaybackThread)
        lifecycleOwner?.lifecycle?.addObserver(PlaybackLifecycleObserver(engine))
        return engine
    }
}

internal class PlaybackLifecycleObserver(
    private val engine: PlaybackEngine,
) : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) {
        engine.pause()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        engine.release()
        owner.lifecycle.removeObserver(this)
    }
}

internal fun interface MediaAudioFocusTarget {
    fun setAudioAttributes(attributes: AudioAttributes, handleAudioFocus: Boolean)
}

internal fun configureMediaAudioFocus(target: MediaAudioFocusTarget, enabled: Boolean) {
    val attributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()
    target.setAudioAttributes(attributes, enabled)
}

internal class ExoPlayerBackend @MainThread constructor(context: Context) : PlayerBackend {
    init {
        requireMainPlaybackThread()
    }

    private val player = ExoPlayer.Builder(context).build()
    private val handler = Handler(player.applicationLooper)
    private var media3Listener: Player.Listener? = null
    private var listener: ((PlayerBackend.Event) -> Unit)? = null
    private var released = false
    private val progressPublisher = object : Runnable {
        override fun run() {
            if (released) return
            listener?.invoke(PlayerBackend.Event.StateChanged(snapshot()))
            if (player.isPlaying) handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    @MainThread
    override fun configureAudioFocus(enabled: Boolean) {
        requireMainPlaybackThread()
        configureMediaAudioFocus(
            MediaAudioFocusTarget { attributes, handleAudioFocus ->
                player.setAudioAttributes(attributes, handleAudioFocus)
            },
            enabled,
        )
    }

    @MainThread
    override fun setMedia(media: PlaybackMedia) {
        requireMainPlaybackThread()
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId(media.trackId)
                .setUri(media.streamUrl)
                .build(),
            true,
        )
    }

    @MainThread
    override fun setListener(listener: (PlayerBackend.Event) -> Unit) {
        requireMainPlaybackThread()
        media3Listener?.let(player::removeListener)
        this.listener = listener
        val adapter = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val current = snapshot()
                when (playbackState) {
                    Player.STATE_IDLE -> Unit
                    Player.STATE_BUFFERING -> listener(PlayerBackend.Event.Buffering(current))
                    Player.STATE_READY -> listener(PlayerBackend.Event.Ready(current))
                    Player.STATE_ENDED -> listener(PlayerBackend.Event.Ended(current))
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(progressPublisher)
                listener(PlayerBackend.Event.StateChanged(snapshot()))
                if (isPlaying) handler.postDelayed(progressPublisher, PROGRESS_INTERVAL_MS)
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                listener(PlayerBackend.Event.StateChanged(snapshot()))
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                listener(PlayerBackend.Event.StateChanged(snapshot()))
            }

            override fun onPlayerError(error: PlaybackException) {
                handler.removeCallbacks(progressPublisher)
                listener(PlayerBackend.Event.Failed(error.toBackendFailure()))
            }
        }
        media3Listener = adapter
        player.addListener(adapter)
    }

    @MainThread
    override fun prepare() = onMainPlaybackThread { player.prepare() }
    @MainThread
    override fun play() = onMainPlaybackThread { player.play() }
    @MainThread
    override fun pause() = onMainPlaybackThread { player.pause() }
    @MainThread
    override fun seekTo(positionMs: Long) = onMainPlaybackThread { player.seekTo(positionMs) }

    @MainThread
    override fun release() {
        requireMainPlaybackThread()
        if (released) return
        released = true
        handler.removeCallbacks(progressPublisher)
        media3Listener?.let(player::removeListener)
        media3Listener = null
        listener = null
        player.release()
    }

    private fun snapshot() = PlayerBackend.Snapshot(
        positionMs = player.currentPosition.coerceAtLeast(0),
        durationMs = player.duration.takeUnless { it == C.TIME_UNSET || it < 0 },
        isSeekable = player.isCurrentMediaItemSeekable,
        isPlaying = player.isPlaying,
    )

    private companion object {
        const val PROGRESS_INTERVAL_MS = 100L
    }
}

private const val MAIN_THREAD_ERROR = "Playback must be created and operated on the main thread"

internal fun requireMainPlaybackThread() {
    check(Looper.myLooper() === Looper.getMainLooper()) { MAIN_THREAD_ERROR }
}

private inline fun onMainPlaybackThread(block: () -> Unit) {
    requireMainPlaybackThread()
    block()
}

private fun PlaybackException.toBackendFailure(): BackendFailure {
    val response = findCause<HttpDataSource.InvalidResponseCodeException>()
    if (response != null) {
        return if (response.responseCode == 416) {
            BackendFailure.Range(messageOrFallback(), responseCode = 416, cause = this)
        } else {
            BackendFailure.Http(response.responseCode, messageOrFallback(), this)
        }
    }
    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
            BackendFailure.Range(messageOrFallback(), cause = this)
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            BackendFailure.Decode(messageOrFallback(), this)
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            BackendFailure.Network(messageOrFallback(), this)
        else -> if (findCause<IOException>() != null) {
            BackendFailure.Network(messageOrFallback(), this)
        } else {
            BackendFailure.Unknown(messageOrFallback(), this)
        }
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private fun PlaybackException.messageOrFallback() = message ?: "Media3 playback error $errorCode"
