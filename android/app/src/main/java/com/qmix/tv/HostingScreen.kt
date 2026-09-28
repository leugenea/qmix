package com.qmix.tv

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme

@Composable
internal fun HostingScreen(
    state: HostingState,
    onSettingsChanged: (String, String) -> Unit,
    onCreate: () -> Unit,
    onEnterRoom: () -> Unit,
    onEndInvitation: () -> Unit = {},
    onConfirmHttpWarning: () -> Unit = {},
    onCancelHttpWarning: () -> Unit = {},
    liveRoomHandler: LiveRoomHandler = NoOpLiveRoomHandler,
    onExitLiveRoom: () -> Unit = {},
) {
    MaterialTheme {
        when (state) {
            HostingState.Ending -> Unit // Admission remains closed until detached cleanup completes.
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
            is HostingState.Invitation -> {
                BackHandler(onBack = onEndInvitation)
                InvitationScreen(
                    state.invite,
                    onEnterRoom,
                    roomReplacementNotice = state.roomReplacementNotice,
                )
            }
            is HostingState.LiveRoom -> MissingAwareLiveRoomScreen(state, liveRoomHandler, onExitLiveRoom)
        }
    }
}

private object NoOpLiveRoomHandler : LiveRoomHandler {
    override fun onStartOrNext() = Unit
    override fun onInvite() = Unit
    override fun onBack(): LiveRoomBackResult = LiveRoomBackResult.IGNORED
}
