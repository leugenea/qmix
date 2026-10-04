package com.qmix.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
internal fun MissingAwareLiveRoomScreen(
    state: HostingState.LiveRoom,
    handler: LiveRoomHandler,
    onExitLiveRoom: () -> Unit,
) {
    if (state.synchronization is RoomSyncState.Missing) {
        BackHandler { handleLiveRoomBack(handler, onExitLiveRoom) }
        MissingRoomScreen(state.replacementError, handler::onNewRoom)
    } else LiveRoomScreen(state, handler, onExitLiveRoom)
}

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
    val controls = PlaybackControlVisibility(state.playback)
    val focus = rememberLiveRoomFocus(state, room, controls, focusMemory)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key.isDirectional()) {
                    focusMemory.markDirectionalInput()
                }
                false
            }
            .padding(48.dp),
    ) {
        LiveRoomHeader(state)
        RoomActions(state, handler, focus, focusMemory)
        RoomNotices(state)
        PlaybackControls(state, handler, controls, focus, focusMemory)
        ServerRoomContent(room, state.isPrimaryActionEnabled, focus, focusMemory)
    }
}

@Composable
private fun LiveRoomHeader(state: HostingState.LiveRoom) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(R.string.room_title, state.invite.code), style = MaterialTheme.typography.displaySmall)
        Text(
            stringResource(
                R.string.local_playback,
                stringResource(localPlaybackStatusResource(state.playback.status)),
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    synchronizationMessageResource(state.synchronization)?.let { resource ->
        Text(
            stringResource(resource),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun RoomActions(
    state: HostingState.LiveRoom,
    handler: LiveRoomHandler,
    focus: LiveRoomFocusBindings,
    focusMemory: LiveRoomFocusMemory,
) {
    Row(
        // Reserve space for the existing Button focus scale inside the 48dp screen margin.
        modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        FocusedButton(
            text = stringResource(
                if (state.primaryAction == LiveRoomPrimaryAction.START) R.string.start else R.string.next,
            ),
            enabled = state.isPrimaryActionEnabled,
            focusRequester = focus.primary,
            focusProperties = {
                right = focus.invite
                down = focus.firstPlayback ?: focus.firstTrack ?: FocusRequester.Cancel
            },
            onFocused = { focusMemory.record(LiveRoomFocusTarget.Primary) },
            testTag = if (state.primaryAction == LiveRoomPrimaryAction.START) "room-start" else "room-next",
            onClick = handler::onStartOrNext,
        )
        FocusedButton(
            text = stringResource(R.string.invite),
            enabled = true,
            focusRequester = focus.invite,
            focusProperties = {
                left = if (state.isPrimaryActionEnabled) focus.primary else FocusRequester.Cancel
                down = focus.firstPlayback ?: focus.firstTrack ?: FocusRequester.Cancel
            },
            onFocused = { focusMemory.record(LiveRoomFocusTarget.Invite) },
            testTag = "room-invite",
            onClick = handler::onInvite,
        )
    }
}

@Composable
private fun RoomNotices(state: HostingState.LiveRoom) {
    if (state.commandPending) {
        Text(stringResource(R.string.command_pending), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
    }
    state.playback.error?.let { error ->
        val detail = localPlaybackErrorText(error)
        val detailText = detail.argument?.let { stringResource(detail.resource, it) }
            ?: stringResource(detail.resource)
        Text(
            stringResource(R.string.playback_error, detailText),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PlaybackControls(
    state: HostingState.LiveRoom,
    handler: LiveRoomHandler,
    controls: PlaybackControlVisibility,
    focus: LiveRoomFocusBindings,
    focusMemory: LiveRoomFocusMemory,
) {
    if (controls.playPause || controls.retry) {
        Row(
            modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (controls.playPause) {
                PlayPauseControl(state.playback.status, handler, controls, focus, focusMemory)
                if (controls.seek) {
                    SeekControls(handler, focus, focusMemory)
                }
            }
            if (controls.retry) {
                FocusedButton(
                    text = stringResource(R.string.retry_current),
                    enabled = true,
                    focusRequester = focus.retry,
                    focusProperties = {
                        up = focus.playbackUp
                        down = focus.firstTrack ?: FocusRequester.Cancel
                    },
                    onFocused = { focusMemory.record(LiveRoomFocusTarget.Retry) },
                    testTag = "playback-retry",
                    onClick = handler::onRetryCurrent,
                )
            }
        }
    }
}

@Composable
private fun PlayPauseControl(
    status: LocalPlaybackStatus,
    handler: LiveRoomHandler,
    controls: PlaybackControlVisibility,
    focus: LiveRoomFocusBindings,
    focusMemory: LiveRoomFocusMemory,
) {
    FocusedButton(
        text = stringResource(if (status == LocalPlaybackStatus.PAUSED) R.string.play else R.string.pause),
        enabled = true,
        focusRequester = focus.playPause,
        focusProperties = {
            up = focus.playbackUp
            if (controls.seek) right = focus.seekBack
            down = focus.firstTrack ?: FocusRequester.Cancel
        },
        onFocused = { focusMemory.record(LiveRoomFocusTarget.PlayPause) },
        testTag = "playback-play-pause",
        onClick = handler::onPlayPause,
    )
}

@Composable
private fun SeekControls(
    handler: LiveRoomHandler,
    focus: LiveRoomFocusBindings,
    focusMemory: LiveRoomFocusMemory,
) {
    FocusedButton(
        text = stringResource(R.string.seek_back_ten_seconds),
        enabled = true,
        focusRequester = focus.seekBack,
        focusProperties = {
            left = focus.playPause
            right = focus.seekForward
            up = focus.playbackUp
            down = focus.firstTrack ?: FocusRequester.Cancel
        },
        onFocused = { focusMemory.record(LiveRoomFocusTarget.SeekBack) },
        testTag = "playback-seek-back",
        onClick = { handler.onSeekBy(-10_000) },
    )
    FocusedButton(
        text = stringResource(R.string.seek_forward_ten_seconds),
        enabled = true,
        focusRequester = focus.seekForward,
        focusProperties = {
            left = focus.seekBack
            up = focus.playbackUp
            down = focus.firstTrack ?: FocusRequester.Cancel
        },
        onFocused = { focusMemory.record(LiveRoomFocusTarget.SeekForward) },
        testTag = "playback-seek-forward",
        onClick = { handler.onSeekBy(10_000) },
    )
}

@Composable
private fun ColumnScope.ServerRoomContent(
    room: RoomState?,
    primaryEnabled: Boolean,
    focus: LiveRoomFocusBindings,
    focusMemory: LiveRoomFocusMemory,
) {
    if (room != null) {
        // qmix#319: side-by-side content preserves a usable queue viewport with long current/notices.
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f)) { ServerCurrentTrack(room.current) }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(stringResource(R.string.queue), style = MaterialTheme.typography.headlineMedium)
                if (room.queue.isEmpty()) {
                    Text(stringResource(R.string.queue_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).testTag("queue-list"),
                        state = focus.queueState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(room.queue, key = { _, track -> track.id }) { index, track ->
                            QueueTrackRow(
                                track = track,
                                focusRequester = checkNotNull(focus.tracks[track.id]),
                                upFocusRequester = if (index == 0) {
                                    focus.lastPlayback ?: if (primaryEnabled) focus.primary else focus.invite
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
}

@Composable
private fun ServerCurrentTrack(current: CurrentTrack?) {
    Text(
        stringResource(R.string.server_selected_track),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    if (current == null) {
        Text(stringResource(R.string.no_track_playing), style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp))
    } else {
        Text(
            current.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            current.artist,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** One visibility decision drives both the controls and their focus destinations. */
private class PlaybackControlVisibility(playback: LocalPlaybackState) {
    val playPause = playback.trackId != null && playback.status in setOf(
        LocalPlaybackStatus.BUFFERING,
        LocalPlaybackStatus.PLAYING,
        LocalPlaybackStatus.PAUSED,
    )
    val retry = playback.trackId != null && playback.status == LocalPlaybackStatus.ERROR
    val seek = playPause && playback.isSeekable && playback.durationMs != null

    val targets = buildList {
        if (playPause) add(LiveRoomFocusTarget.PlayPause)
        if (seek) {
            add(LiveRoomFocusTarget.SeekBack)
            add(LiveRoomFocusTarget.SeekForward)
        }
        if (retry) add(LiveRoomFocusTarget.Retry)
    }
}

private class LiveRoomFocusBindings(
    val primary: FocusRequester,
    val invite: FocusRequester,
    val playPause: FocusRequester,
    val seekBack: FocusRequester,
    val seekForward: FocusRequester,
    val retry: FocusRequester,
    controls: PlaybackControlVisibility,
    primaryEnabled: Boolean,
    val firstTrack: FocusRequester?,
    val queueState: LazyListState,
    val tracks: Map<String, FocusRequester>,
) {
    val firstPlayback: FocusRequester? = when {
        controls.playPause -> playPause
        controls.retry -> retry
        else -> null
    }
    val lastPlayback: FocusRequester? = when {
        controls.seek -> seekForward
        controls.playPause -> playPause
        controls.retry -> retry
        else -> null
    }
    val playbackUp: FocusRequester = if (primaryEnabled) primary else invite

    fun requesterFor(target: LiveRoomFocusTarget): FocusRequester? = when (target) {
        LiveRoomFocusTarget.Primary -> primary
        LiveRoomFocusTarget.Invite -> invite
        LiveRoomFocusTarget.PlayPause -> playPause
        LiveRoomFocusTarget.SeekBack -> seekBack
        LiveRoomFocusTarget.SeekForward -> seekForward
        LiveRoomFocusTarget.Retry -> retry
        is LiveRoomFocusTarget.Track -> tracks[target.id]
    }
}

/** Own the queue-keyed requesters and the generation-guarded restoration in one place. */
@Composable
private fun rememberLiveRoomFocus(
    state: HostingState.LiveRoom,
    room: RoomState?,
    controls: PlaybackControlVisibility,
    memory: LiveRoomFocusMemory,
): LiveRoomFocusBindings {
    val primary = remember { FocusRequester() }
    val invite = remember { FocusRequester() }
    val playPause = remember { FocusRequester() }
    val seekBack = remember { FocusRequester() }
    val seekForward = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val queueIds = room?.queue.orEmpty().map { track -> track.id }
    val queueState = rememberLazyListState()
    val tracks = remember(queueIds) { queueIds.associateWith { FocusRequester() } }
    val firstTrack = queueIds.firstOrNull()?.let(tracks::get)
    val restoration = remember(queueIds, state.isPrimaryActionEnabled, controls.targets) {
        memory.reconcile(queueIds, state.isPrimaryActionEnabled, controls.targets)
    }
    val focus = LiveRoomFocusBindings(
        primary = primary,
        invite = invite,
        playPause = playPause,
        seekBack = seekBack,
        seekForward = seekForward,
        retry = retry,
        controls = controls,
        primaryEnabled = state.isPrimaryActionEnabled,
        firstTrack = firstTrack,
        queueState = queueState,
        tracks = tracks,
    )
    LaunchedEffect(queueIds, restoration) {
        if (queueIds.isEmpty()) queueState.requestScrollToItem(0)
        when (val target = restoration.target) {
            is LiveRoomFocusTarget.Track -> {
                val index = queueIds.indexOf(target.id)
                if (index >= 0) {
                    queueState.scrollToItem(index)
                    withFrameNanos { }
                    if (memory.isCurrent(restoration)) tracks[target.id]?.requestFocus()
                }
            }
            else -> if (memory.isCurrent(restoration)) focus.requesterFor(target)?.requestFocus()
        }
    }
    return focus
}

/** A terminal room must not display its old invite, queue, or playback as actionable data. */
@Composable
private fun MissingRoomScreen(replacementError: UserMessage?, onNewRoom: () -> Unit) {
    val newRoomFocus = remember { FocusRequester() }
    LaunchedEffect(newRoomFocus) { newRoomFocus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.room_unavailable_title), style = MaterialTheme.typography.displaySmall)
        Text(stringResource(R.string.sync_room_missing), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
        replacementError?.let { error ->
            Text(stringResource(error.resourceId()), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
        Row(Modifier.padding(16.dp)) {
            FocusedButton(
                text = stringResource(R.string.new_room),
                enabled = true,
                focusRequester = newRoomFocus,
                onClick = onNewRoom,
            )
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
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    // qmix#319: keep the read-only shell and exact focus modifier order from #104.
    // A clickable/selectable TV Surface would add an action the server queue does not have.
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
            .background(colors.surfaceVariant, shape)
            .border(
                if (focused) 4.dp else 1.dp,
                if (focused) colors.onSurface else Color.Transparent,
                shape,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(track.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artist, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
        Text(
            formatDuration(track.durationSeconds, stringResource(R.string.duration_unknown)),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
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
