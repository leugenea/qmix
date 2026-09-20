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
import androidx.compose.ui.res.stringResource
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
            actionText = R.string.back_to_room,
        )
        return
    }
    val room = (state.synchronization as? RoomSyncState.Active)?.room
    val primaryFocus = remember { FocusRequester() }
    val inviteFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val seekBackFocus = remember { FocusRequester() }
    val seekForwardFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val showPlayPause = state.playback.trackId != null && state.playback.status in setOf(
        LocalPlaybackStatus.BUFFERING,
        LocalPlaybackStatus.PLAYING,
        LocalPlaybackStatus.PAUSED,
    )
    val showRetry = state.playback.trackId != null && state.playback.status == LocalPlaybackStatus.ERROR
    val showSeek = showPlayPause && state.playback.isSeekable && state.playback.durationMs != null
    val playbackTargets = buildList {
        if (showPlayPause) add(LiveRoomFocusTarget.PlayPause)
        if (showSeek) {
            add(LiveRoomFocusTarget.SeekBack)
            add(LiveRoomFocusTarget.SeekForward)
        }
        if (showRetry) add(LiveRoomFocusTarget.Retry)
    }
    val firstPlaybackFocus = when {
        showPlayPause -> playPauseFocus
        showRetry -> retryFocus
        else -> null
    }
    val lastPlaybackFocus = when {
        showSeek -> seekForwardFocus
        showPlayPause -> playPauseFocus
        showRetry -> retryFocus
        else -> null
    }
    val queueIds = room?.queue.orEmpty().map { track -> track.id }
    val queueState = rememberLazyListState()
    val trackFocusRequesters = remember(queueIds) {
        queueIds.associateWith { FocusRequester() }
    }
    val firstTrackFocus = queueIds.firstOrNull()?.let(trackFocusRequesters::get)
    val playbackUpFocus = if (state.isPrimaryActionEnabled) primaryFocus else inviteFocus
    val restoration = remember(queueIds, state.isPrimaryActionEnabled, playbackTargets) {
        focusMemory.reconcile(queueIds, state.isPrimaryActionEnabled, playbackTargets)
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
            LiveRoomFocusTarget.PlayPause -> {
                if (focusMemory.isCurrent(restoration)) playPauseFocus.requestFocus()
            }
            LiveRoomFocusTarget.SeekBack -> {
                if (focusMemory.isCurrent(restoration)) seekBackFocus.requestFocus()
            }
            LiveRoomFocusTarget.SeekForward -> {
                if (focusMemory.isCurrent(restoration)) seekForwardFocus.requestFocus()
            }
            LiveRoomFocusTarget.Retry -> {
                if (focusMemory.isCurrent(restoration)) retryFocus.requestFocus()
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
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(stringResource(R.string.room_title, state.invite.code), fontSize = 42.sp)
            Text(
                stringResource(
                    R.string.local_playback,
                    stringResource(localPlaybackStatusResource(state.playback.status)),
                ),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        synchronizationMessageResource(state.synchronization)?.let { resource ->
            Text(stringResource(resource), color = Color(0xFFFFDDB3), modifier = Modifier.padding(top = 12.dp))
        }
        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FocusedButton(
                text = stringResource(
                    if (state.primaryAction == LiveRoomPrimaryAction.START) R.string.start else R.string.next,
                ),
                enabled = state.isPrimaryActionEnabled,
                focusRequester = primaryFocus,
                focusProperties = {
                    right = inviteFocus
                    down = firstPlaybackFocus ?: firstTrackFocus ?: FocusRequester.Cancel
                },
                onFocused = { focusMemory.record(LiveRoomFocusTarget.Primary) },
                testTag = if (state.primaryAction == LiveRoomPrimaryAction.START) "room-start" else "room-next",
                onClick = handler::onStartOrNext,
            )
            FocusedButton(
                text = stringResource(R.string.invite),
                enabled = true,
                focusRequester = inviteFocus,
                focusProperties = {
                    left = if (state.isPrimaryActionEnabled) primaryFocus else FocusRequester.Cancel
                    down = firstPlaybackFocus ?: firstTrackFocus ?: FocusRequester.Cancel
                },
                onFocused = { focusMemory.record(LiveRoomFocusTarget.Invite) },
                testTag = "room-invite",
                onClick = handler::onInvite,
            )
        }
        if (state.commandPending) {
            Text(stringResource(R.string.command_pending), modifier = Modifier.padding(top = 8.dp))
        }
        state.playback.error?.let { error ->
            val detail = localPlaybackErrorText(error)
            val detailText = detail.argument?.let { stringResource(detail.resource, it) }
                ?: stringResource(detail.resource)
            Text(
                stringResource(R.string.playback_error, detailText),
                color = Color(0xFFFFDDB3),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (showPlayPause || showRetry) {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showPlayPause) {
                    FocusedButton(
                        text = stringResource(
                            if (state.playback.status == LocalPlaybackStatus.PAUSED) R.string.play else R.string.pause,
                        ),
                        enabled = true,
                        focusRequester = playPauseFocus,
                        focusProperties = {
                            up = playbackUpFocus
                            if (showSeek) right = seekBackFocus
                            down = firstTrackFocus ?: FocusRequester.Cancel
                        },
                        onFocused = { focusMemory.record(LiveRoomFocusTarget.PlayPause) },
                        testTag = "playback-play-pause",
                        onClick = handler::onPlayPause,
                    )
                    if (showSeek) {
                        FocusedButton(
                            text = stringResource(R.string.seek_back_ten_seconds),
                            enabled = true,
                            focusRequester = seekBackFocus,
                            focusProperties = {
                                left = playPauseFocus
                                right = seekForwardFocus
                                up = playbackUpFocus
                                down = firstTrackFocus ?: FocusRequester.Cancel
                            },
                            onFocused = { focusMemory.record(LiveRoomFocusTarget.SeekBack) },
                            testTag = "playback-seek-back",
                            onClick = { handler.onSeekBy(-10_000) },
                        )
                        FocusedButton(
                            text = stringResource(R.string.seek_forward_ten_seconds),
                            enabled = true,
                            focusRequester = seekForwardFocus,
                            focusProperties = {
                                left = seekBackFocus
                                up = playbackUpFocus
                                down = firstTrackFocus ?: FocusRequester.Cancel
                            },
                            onFocused = { focusMemory.record(LiveRoomFocusTarget.SeekForward) },
                            testTag = "playback-seek-forward",
                            onClick = { handler.onSeekBy(10_000) },
                        )
                    }
                }
                if (showRetry) {
                    FocusedButton(
                        text = stringResource(R.string.retry_current),
                        enabled = true,
                        focusRequester = retryFocus,
                        focusProperties = {
                            up = playbackUpFocus
                            down = firstTrackFocus ?: FocusRequester.Cancel
                        },
                        onFocused = { focusMemory.record(LiveRoomFocusTarget.Retry) },
                        testTag = "playback-retry",
                        onClick = handler::onRetryCurrent,
                    )
                }
            }
        }
        if (room != null) {
            Text(stringResource(R.string.server_selected_track), fontSize = 28.sp, modifier = Modifier.padding(top = 24.dp))
            if (room.current == null) {
                Text(stringResource(R.string.no_track_playing), modifier = Modifier.padding(top = 8.dp))
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
            Text(stringResource(R.string.queue), fontSize = 28.sp, modifier = Modifier.padding(top = 24.dp))
            if (room.queue.isEmpty()) {
                Text(stringResource(R.string.queue_empty), modifier = Modifier.padding(top = 8.dp))
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
                                lastPlaybackFocus ?: if (state.isPrimaryActionEnabled) primaryFocus else inviteFocus
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
        Text(
            formatDuration(track.durationSeconds, stringResource(R.string.duration_unknown)),
            fontSize = 14.sp,
            color = Color(0xFFCAC4D0),
        )
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
