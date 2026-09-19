package com.qmix.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private lateinit var controller: HostSessionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = (application as QMixApplication).hostSessionForActivity()
        var uiState by mutableStateOf(controller.state)
        setContent {
            DisposableEffect(controller) {
                val subscription = controller.observe { newState ->
                    runOnUiThread { uiState = newState }
                }
                onDispose { subscription.close() }
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
