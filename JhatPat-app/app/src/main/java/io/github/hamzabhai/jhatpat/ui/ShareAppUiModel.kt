package io.github.hamzabhai.jhatpat.ui

import io.github.hamzabhai.jhatpat.service.SharingState

/** What the "Share this app" screen shows. */
data class ShareAppUi(val url: String, val address: String, val countdown: String, val expired: Boolean)

object ShareAppUiModel {
    /** How long the APK stays available after the host opens the screen. */
    const val MINUTES = 10

    fun of(state: SharingState.Running, remainingSeconds: Int): ShareAppUi {
        val ip = state.info.ip
        val address = if (ip.isEmpty()) "" else "$ip:${state.info.port}/app.apk"
        return ShareAppUi(
            url = if (address.isEmpty()) "" else "http://$address",
            address = address,
            countdown = countdown(remainingSeconds),
            expired = remainingSeconds <= 0,
        )
    }

    fun countdown(remainingSeconds: Int): String {
        val s = remainingSeconds.coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
