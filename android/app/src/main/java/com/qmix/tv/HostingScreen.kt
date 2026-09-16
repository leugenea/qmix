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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
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
            is HostingState.LiveRoom -> LiveRoomContent(state)
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
        if (error != null) Text(error, color = Color(0xFFFFB4AB), modifier = Modifier.padding(12.dp))
        if (pending) Text("Creating room…", modifier = Modifier.padding(12.dp))
        FocusedButton(
            text = if (error == null) "Create room" else "Retry",
            enabled = !pending,
            focusRequester = actionFocus,
            onClick = onCreate,
        )
        UrlInput("Backend URL", backend, !pending) {
            backend = it
            onSettingsChanged(backend, origin)
        }
        UrlInput("Guest origin", origin, !pending) {
            origin = it
            onSettingsChanged(backend, origin)
        }
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
private fun LiveRoomContent(state: HostingState.LiveRoom) {
    val room = (state.synchronization as? RoomSyncState.Active)?.room
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101218)).padding(48.dp),
    ) {
        Text("Room ${state.invite.code}", fontSize = 42.sp)
        synchronizationMessage(state.synchronization)?.let { message ->
            Text(message, color = Color(0xFFFFDDB3), modifier = Modifier.padding(top = 12.dp))
        }
        if (room != null) {
            Text("Now playing", fontSize = 28.sp, modifier = Modifier.padding(top = 24.dp))
            if (room.current == null) {
                Text("No track playing", modifier = Modifier.padding(top = 8.dp))
            } else {
                Text(
                    room.current.title,
                    fontSize = 24.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    room.current.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text("Queue", fontSize = 28.sp, modifier = Modifier.padding(top = 24.dp))
            if (room.queue.isEmpty()) {
                Text("Queue is empty", modifier = Modifier.padding(top = 8.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).testTag("queue-list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(room.queue, key = { track -> track.id }) { track ->
                        QueueTrackRow(track)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueTrackRow(track: QueuedTrack) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag("queue-track-${track.id}")
            .background(Color(0xFF252833), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFFCAC4D0))
        Text(formatDuration(track.durationSeconds), fontSize = 14.sp, color = Color(0xFFCAC4D0))
    }
}

private fun formatDuration(durationSeconds: Int): String {
    if (durationSeconds <= 0) return "Duration unknown"
    val hours = durationSeconds / 3600
    val minutes = (durationSeconds % 3600) / 60
    val seconds = durationSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private fun synchronizationMessage(synchronization: RoomSyncState): String? = when (synchronization) {
    is RoomSyncState.Missing -> "Room not found."
    is RoomSyncState.Active -> when {
        synchronization.connection == LiveConnection.CONNECTING -> "Connecting to room…"
        synchronization.connection == LiveConnection.RECONNECTING && synchronization.room != null ->
            "Reconnecting… Showing last known room."
        synchronization.connection == LiveConnection.RECONNECTING -> "Reconnecting…"
        synchronization.freshness == Freshness.STALE && synchronization.room != null ->
            "Updates are stale. Showing last known room."
        synchronization.freshness == Freshness.STALE -> "Could not refresh the room."
        synchronization.freshness == Freshness.LOADING -> "Waiting for room data…"
        else -> null
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
