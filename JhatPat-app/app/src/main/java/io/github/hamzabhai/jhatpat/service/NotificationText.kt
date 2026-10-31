package io.github.hamzabhai.jhatpat.service

import io.github.hamzabhai.jhatpat.net.Reason

/** The words on the sharing notification. */
object NotificationText {
    fun title(state: SharingState): String = when (state) {
        SharingState.Idle -> "JhatPat"
        is SharingState.Starting -> "Starting JhatPat…"
        is SharingState.Running -> "JhatPat is sharing"
        is SharingState.Error -> "JhatPat stopped"
    }

    fun body(state: SharingState): String = when (state) {
        is SharingState.Running -> "${state.people} connected · ${state.received} ${if (state.received == 1) "file" else "files"} received"
        is SharingState.Error -> state.message
        else -> ""
    }
}

/**
 * The state the notification shows the moment a START command arrives. An already active session
 * keeps its own notification, so a second START never replaces "3 connected" with "Starting…".
 */
fun foregroundStateFor(current: SharingState, requested: Mode): SharingState =
    if (current is SharingState.Running || current is SharingState.Starting) current else SharingState.Starting(requested)

/** What to tell the person when the hotspot cannot start. */
object HotspotMessages {
    private const val FALLBACK = "Turn on your phone's Hotspot in Settings, then use your current network."

    fun forReason(reason: Reason): String = when (reason) {
        Reason.NoPermission -> "Allow the permission JhatPat asks for so it can create a hotspot."
        Reason.LocationOff -> "Turn on Location in your phone's settings. Android needs it to create a hotspot."
        Reason.Unsupported -> "This phone does not let apps create a hotspot. $FALLBACK"
        Reason.Busy -> "The phone can't start a hotspot right now. $FALLBACK"
        Reason.Unknown -> "JhatPat could not create a hotspot. $FALLBACK"
    }
}
