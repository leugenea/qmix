package com.qmix.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private lateinit var controller: HostSessionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = (application as QMixApplication).hostSessionForActivity()
        setContent {
            val uiState by controller.states.collectAsStateWithLifecycle()
            val keepScreenOn = shouldKeepScreenOn(uiState)
            DisposableEffect(keepScreenOn) {
                if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            HostingScreen(
                state = uiState,
                onSettingsChanged = controller::updateSettings,
                onCreate = { controller.createRoom() },
                onEnterRoom = controller::enterRoom,
                onConfirmHttpWarning = { controller.confirmHttpWarning() },
                onCancelHttpWarning = controller::cancelHttpWarning,
                liveRoomHandler = controller,
                onExitLiveRoom = ::finish,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (::controller.isInitialized) controller.onHostStarted()
    }

    override fun onStop() {
        if (::controller.isInitialized) controller.onHostStopped()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (::controller.isInitialized && dispatchPlaybackMediaKey(event, controller)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (::controller.isInitialized && dispatchPlaybackMediaKey(event, controller)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        if (::controller.isInitialized && shouldEndHostSession(isFinishing, isChangingConfigurations)) {
            controller.endRoom()
        }
        super.onDestroy()
    }
}

internal fun shouldKeepScreenOn(state: HostingState): Boolean =
    (state as? HostingState.LiveRoom)?.playback?.status in setOf(
        LocalPlaybackStatus.PLAYING,
        LocalPlaybackStatus.BUFFERING,
    )

internal fun dispatchPlaybackMediaKey(event: KeyEvent, handler: LiveRoomHandler): Boolean {
    val action = when (event.keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> handler::onPlayPause
        KeyEvent.KEYCODE_MEDIA_PLAY -> handler::onPlay
        KeyEvent.KEYCODE_MEDIA_PAUSE -> handler::onPause
        else -> return false
    }
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) action()
    return true
}

internal fun shouldEndHostSession(isFinishing: Boolean, isChangingConfigurations: Boolean): Boolean =
    isFinishing && !isChangingConfigurations
