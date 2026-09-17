package com.qmix.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

@Composable
internal fun LiveRoomScreen(
    state: HostingState.LiveRoom,
    handler: LiveRoomHandler,
    onExitLiveRoom: () -> Unit,
) {
    BackHandler {
        handleLiveRoomBack(handler, onExitLiveRoom)
    }
    val focusMemory = remember(state.invite.code) { LiveRoomFocusMemory() }
    if (state.invitationVisible) {
        InvitationScreen(
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

private fun Key.isDirectional(): Boolean = when (this) {
    Key.DirectionUp,
    Key.DirectionDown,
    Key.DirectionLeft,
    Key.DirectionRight,
    -> true
    else -> false
}
