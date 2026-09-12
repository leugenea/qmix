package com.qmix.tv

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
internal fun HostingScreen(
    state: HostingState,
    onSettingsChanged: (String, String) -> Unit,
    onCreate: () -> Unit,
    onEnterRoom: () -> Unit,
) {
    MaterialTheme {
        when (state) {
            is HostingState.Setup -> SetupContent(state, false, null, onSettingsChanged, onCreate)
            is HostingState.Pending -> SetupContent(
                HostingState.Setup(state.backendUrl, state.guestOrigin),
                true,
                null,
                onSettingsChanged,
                onCreate,
            )
            is HostingState.Error -> SetupContent(
                HostingState.Setup(state.backendUrl, state.guestOrigin),
                false,
                state.message,
                onSettingsChanged,
                onCreate,
            )
            is HostingState.Invitation -> InvitationContent(state.invite, onEnterRoom)
            is HostingState.RoomPlaceholder -> RoomPlaceholderContent(state.code)
        }
    }
}

@Composable
private fun SetupContent(
    settings: HostingState.Setup,
    pending: Boolean,
    error: String?,
    onSettingsChanged: (String, String) -> Unit,
    onCreate: () -> Unit,
) {
    var backend by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl) }
    var origin by remember(settings.guestOrigin) { mutableStateOf(settings.guestOrigin) }
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(pending, error) { actionFocus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101218)).padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("QMix TV", fontSize = 42.sp)
        UrlInput("Backend URL", backend, !pending) {
            backend = it
            onSettingsChanged(backend, origin)
        }
        UrlInput("Guest origin", origin, !pending) {
            origin = it
            onSettingsChanged(backend, origin)
        }
        if (error != null) Text(error, color = Color(0xFFFFB4AB), modifier = Modifier.padding(12.dp))
        if (pending) Text("Creating room…", modifier = Modifier.padding(12.dp))
        FocusedButton(
            text = if (error == null) "Create room" else "Retry",
            enabled = !pending,
            focusRequester = actionFocus,
            onClick = onCreate,
        )
    }
}

@Composable
private fun UrlInput(label: String, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    Column(Modifier.width(620.dp).padding(top = 16.dp)) {
        Text(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            modifier = Modifier
                .width(620.dp)
                .background(Color(0xFF252833), RoundedCornerShape(8.dp))
                .padding(14.dp)
                .semantics { contentDescription = label },
        )
    }
}

@Composable
private fun InvitationContent(invite: GuestInvite, onEnterRoom: () -> Unit) {
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { actionFocus.requestFocus() }
    val qr = remember(invite.guestUrl) { QrCodeGenerator.generate(invite.guestUrl, 360) }
    val bitmap = remember(qr) {
        Bitmap.createBitmap(qr.pixels, qr.width, qr.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Row(
        Modifier.fillMaxSize().background(Color(0xFF101218)).padding(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "QR code for ${invite.guestUrl}",
            modifier = Modifier.size(360.dp),
        )
        Spacer(Modifier.width(48.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Join this room", fontSize = 32.sp)
            Text(invite.code, fontSize = 64.sp, modifier = Modifier.padding(12.dp))
            Text(invite.guestUrl, fontSize = 20.sp, modifier = Modifier.padding(bottom = 24.dp))
            FocusedButton("Enter room", true, actionFocus, onEnterRoom)
        }
    }
}

@Composable
private fun RoomPlaceholderContent(code: String) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101218)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Room $code", fontSize = 42.sp)
        Text("Playback and live updates are coming next.", modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun FocusedButton(
    text: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .border(
                if (focused) 4.dp else 1.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(16.dp),
            ),
    ) { Text(text) }
}
