package com.qmix.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as QMixApplication).hostSession
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
            )
        }
    }
}
