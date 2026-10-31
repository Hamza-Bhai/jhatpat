package io.github.hamzabhai.jhatpat.service

import io.github.hamzabhai.jhatpat.server.HostApi

/** Turns the uploads in progress into the one progress bar of the notification. */
object TransferProgress {
    data class Summary(val percent: Int, val label: String)

    /** Null when nothing is being received (or the files are empty, so there is nothing to measure). */
    fun summarize(transfers: List<HostApi.Transfer>): Summary? {
        val total = transfers.sumOf { it.size }
        if (transfers.isEmpty() || total <= 0) return null
        val received = transfers.sumOf { it.received.coerceIn(0, it.size) }
        val percent = (received * 100 / total).toInt()
        val what = if (transfers.size == 1) transfers.single().name else "${transfers.size} files"
        return Summary(percent, "Receiving $what · $percent%")
    }
}
