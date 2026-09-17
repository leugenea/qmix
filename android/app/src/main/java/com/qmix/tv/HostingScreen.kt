package com.qmix.tv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    liveRoomHandler: LiveRoomHandler = NoOpLiveRoomHandler,
    onExitLiveRoom: () -> Unit = {},
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
            is HostingState.LiveRoom -> LiveRoomContent(state, liveRoomHandler, onExitLiveRoom)
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
private fun InvitationContent(
    invite: GuestInvite,
    onAction: () -> Unit,
    actionText: String = "Enter room",
) {
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
            FocusedButton(
                text = actionText,
                enabled = true,
                focusRequester = actionFocus,
                onClick = onAction,
            )
        }
    }
}

@Composable
private fun LiveRoomContent(
    state: HostingState.LiveRoom,
    handler: LiveRoomHandler,
    onExitLiveRoom: () -> Unit,
) {
    BackHandler {
        handleLiveRoomBack(handler, onExitLiveRoom)
    }
    val focusMemory = remember(state.invite.code) { LiveRoomFocusMemory() }
    if (state.invitationVisible) {
        InvitationContent(
            invite = state.invite,
            onAction = { handleLiveRoomBack(handler, onExitLiveRoom) },
            actionText = "Back to room",
        )
        return
    }
    val room = (state.synchronization as? RoomSyncState.Active)?.room
    val primaryFocus = remember { FocusRequester() }
    val inviteFocus = remember { FocusRequester() }
    val queueIds = room?.queue.orEmpty().map { track -> track.id }
    val queueState = rememberLazyListState()
    val trackFocusRequesters = remember(queueIds) {
        queueIds.associateWith { FocusRequester() }
    }
    val firstTrackFocus = queueIds.firstOrNull()?.let(trackFocusRequesters::get)
    val restoration = remember(queueIds, state.isPrimaryActionEnabled) {
        focusMemory.reconcile(queueIds, state.isPrimaryActionEnabled)
    }
    LaunchedEffect(queueIds, restoration) {
        if (queueIds.isEmpty()) {
            queueState.requestScrollToItem(0)
        }
        when (val target = restoration.target) {
            LiveRoomFocusTarget.Primary -> {
                if (focusMemory.isCurrent(restoration)) primaryFocus.requestFocus()
            }
            LiveRoomFocusTarget.Invite -> {
                if (focusMemory.isCurrent(restoration)) inviteFocus.requestFocus()
            }
            is LiveRoomFocusTarget.Track -> {
                val index = queueIds.indexOf(target.id)
                if (index >= 0) {
                    queueState.scrollToItem(index)
                    withFrameNanos { }
                    if (focusMemory.isCurrent(restoration)) {
                        trackFocusRequesters[target.id]?.requestFocus()
                    }
                }
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101218))
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key.isDirectional()) {
                    focusMemory.markDirectionalInput()
                }
                false
            }
            .padding(48.dp),
    ) {
        Text("Room ${state.invite.code}", fontSize = 42.sp)
        synchronizationMessage(state.synchronization)?.let { message ->
            Text(message, color = Color(0xFFFFDDB3), modifier = Modifier.padding(top = 12.dp))
        }
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FocusedButton(
                text = if (state.primaryAction == LiveRoomPrimaryAction.START) "Start" else "Next",
                enabled = state.isPrimaryActionEnabled,
                focusRequester = primaryFocus,
                focusProperties = {
                    right = inviteFocus
                    down = firstTrackFocus ?: FocusRequester.Cancel
                },
                onFocused = { focusMemory.record(LiveRoomFocusTarget.Primary) },
                onClick = handler::onStartOrNext,
            )
            FocusedButton(
                text = "Invite",
                enabled = true,
                focusRequester = inviteFocus,
                focusProperties = {
                    left = if (state.isPrimaryActionEnabled) primaryFocus else FocusRequester.Cancel
                    down = firstTrackFocus ?: FocusRequester.Cancel
                },
                onFocused = { focusMemory.record(LiveRoomFocusTarget.Invite) },
                onClick = handler::onInvite,
            )
        }
        if (state.commandPending) {
            Text("Command pending…", modifier = Modifier.padding(top = 8.dp))
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
                    state = queueState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(room.queue, key = { _, track -> track.id }) { index, track ->
                        QueueTrackRow(
                            track = track,
                            focusRequester = checkNotNull(trackFocusRequesters[track.id]),
                            upFocusRequester = if (index == 0) {
                                if (state.isPrimaryActionEnabled) primaryFocus else inviteFocus
                            } else {
                                null
                            },
                            onFocused = { focusMemory.record(LiveRoomFocusTarget.Track(track.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueTrackRow(
    track: QueuedTrack,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .focusProperties {
                upFocusRequester?.let { up = it }
            }
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .semantics(mergeDescendants = true) {}
            .testTag("queue-track-${track.id}")
            .background(Color(0xFF252833), RoundedCornerShape(8.dp))
            .border(
                if (focused) 4.dp else 1.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFFCAC4D0))
        Text(formatDuration(track.durationSeconds), fontSize = 14.sp, color = Color(0xFFCAC4D0))
    }
}

private object NoOpLiveRoomHandler : LiveRoomHandler {
    override fun onStartOrNext() = Unit
    override fun onInvite() = Unit
    override fun onBack(): LiveRoomBackResult = LiveRoomBackResult.IGNORED
}

internal fun handleLiveRoomBack(handler: LiveRoomHandler, onExitLiveRoom: () -> Unit) {
    if (handler.onBack() == LiveRoomBackResult.EXIT_ACTIVITY) {
        onExitLiveRoom()
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
    focusProperties: FocusProperties.() -> Unit = {},
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .focusProperties(focusProperties)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .border(
                if (focused) 4.dp else 1.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(16.dp),
            ),
    ) { Text(text) }
}

internal sealed interface LiveRoomFocusTarget {
    data object Primary : LiveRoomFocusTarget
    data object Invite : LiveRoomFocusTarget
    data class Track(val id: String) : LiveRoomFocusTarget
}

internal data class LiveRoomFocusRestoration(
    val target: LiveRoomFocusTarget,
    val inputGeneration: Long,
)

internal class LiveRoomFocusMemory {
    private var previousQueueIds = emptyList<String>()
    private var focusedTarget: LiveRoomFocusTarget? = null
    private var inputGeneration = 0L

    fun record(target: LiveRoomFocusTarget) {
        focusedTarget = target
    }

    fun markDirectionalInput() {
        inputGeneration++
    }

    fun isCurrent(restoration: LiveRoomFocusRestoration): Boolean =
        restoration.inputGeneration == inputGeneration

    fun reconcile(queueIds: List<String>, primaryEnabled: Boolean): LiveRoomFocusRestoration {
        val current = focusedTarget
        val next = when (current) {
            null -> if (primaryEnabled) LiveRoomFocusTarget.Primary else LiveRoomFocusTarget.Invite
            LiveRoomFocusTarget.Primary ->
                if (primaryEnabled) LiveRoomFocusTarget.Primary else LiveRoomFocusTarget.Invite
            LiveRoomFocusTarget.Invite -> LiveRoomFocusTarget.Invite
            is LiveRoomFocusTarget.Track -> when {
                current.id in queueIds -> current
                previousQueueIds.indexOf(current.id) in queueIds.indices ->
                    LiveRoomFocusTarget.Track(queueIds[previousQueueIds.indexOf(current.id)])
                previousQueueIds.indexOf(current.id) > 0 && queueIds.isNotEmpty() ->
                    LiveRoomFocusTarget.Track(queueIds[minOf(previousQueueIds.indexOf(current.id) - 1, queueIds.lastIndex)])
                primaryEnabled -> LiveRoomFocusTarget.Primary
                else -> LiveRoomFocusTarget.Invite
            }
        }
        previousQueueIds = queueIds
        focusedTarget = next
        return LiveRoomFocusRestoration(next, inputGeneration)
    }
}

private fun Key.isDirectional(): Boolean = when (this) {
    Key.DirectionUp,
    Key.DirectionDown,
    Key.DirectionLeft,
    Key.DirectionRight,
    -> true
    else -> false
}
