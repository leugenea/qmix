package com.qmix.tv

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme

@Composable
internal fun HostingScreen(
    state: HostingState,
    onSettingsChanged: (String, String) -> Unit,
    onCreate: () -> Unit,
    onEnterRoom: () -> Unit,
    onConfirmHttpWarning: () -> Unit = {},
    onCancelHttpWarning: () -> Unit = {},
    liveRoomHandler: LiveRoomHandler = NoOpLiveRoomHandler,
    onExitLiveRoom: () -> Unit = {},
) {
    MaterialTheme {
        when (state) {
            is HostingState.Setup -> SetupScreen(state, false, null, onSettingsChanged, onCreate)
            is HostingState.HttpWarning -> HttpWarningScreen(onConfirmHttpWarning, onCancelHttpWarning)
            is HostingState.Pending -> SetupScreen(
                HostingState.Setup(state.backendUrl, state.guestOrigin),
                true,
                null,
                onSettingsChanged,
                onCreate,
            )
            is HostingState.Error -> SetupScreen(
                HostingState.Setup(state.backendUrl, state.guestOrigin),
                false,
                state.message,
                onSettingsChanged,
                onCreate,
            )
            is HostingState.Invitation -> InvitationScreen(state.invite, onEnterRoom)
            is HostingState.LiveRoom -> LiveRoomScreen(state, liveRoomHandler, onExitLiveRoom)
        }
    }
}

private object NoOpLiveRoomHandler : LiveRoomHandler {
    override fun onStartOrNext() = Unit
    override fun onInvite() = Unit
    override fun onBack(): LiveRoomBackResult = LiveRoomBackResult.IGNORED
}
