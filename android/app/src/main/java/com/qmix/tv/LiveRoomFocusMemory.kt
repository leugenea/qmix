package com.qmix.tv

internal sealed interface LiveRoomFocusTarget {
    data object Primary : LiveRoomFocusTarget
    data object Invite : LiveRoomFocusTarget
    data object PlayPause : LiveRoomFocusTarget
    data object SeekBack : LiveRoomFocusTarget
    data object SeekForward : LiveRoomFocusTarget
    data object Retry : LiveRoomFocusTarget
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

    fun reconcile(
        queueIds: List<String>,
        primaryEnabled: Boolean,
        playbackTargets: List<LiveRoomFocusTarget> = emptyList(),
    ): LiveRoomFocusRestoration {
        val current = focusedTarget
        val next = when (current) {
            null -> if (primaryEnabled) LiveRoomFocusTarget.Primary else LiveRoomFocusTarget.Invite
            LiveRoomFocusTarget.Primary ->
                if (primaryEnabled) LiveRoomFocusTarget.Primary else LiveRoomFocusTarget.Invite
            LiveRoomFocusTarget.Invite -> LiveRoomFocusTarget.Invite
            LiveRoomFocusTarget.PlayPause,
            LiveRoomFocusTarget.SeekBack,
            LiveRoomFocusTarget.SeekForward,
            LiveRoomFocusTarget.Retry,
            -> when {
                current in playbackTargets -> current
                playbackTargets.isNotEmpty() -> playbackTargets.first()
                primaryEnabled -> LiveRoomFocusTarget.Primary
                else -> LiveRoomFocusTarget.Invite
            }
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
