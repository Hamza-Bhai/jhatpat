package io.github.hamzabhai.jhatpat.gallery

import io.github.hamzabhai.jhatpat.logic.GalleryFolder
import io.github.hamzabhai.jhatpat.logic.MediaTarget
import io.github.hamzabhai.jhatpat.server.ItemMover
import io.github.hamzabhai.jhatpat.util.Json
import java.io.File

/**
 * Reacts to the server's "received" events: moves the file into the Gallery, then tells the server
 * so the chat shows "Saved to Gallery". If saving fails the file and the chat item stay as they were.
 *
 * [saved] maps item id to the saved file's URI text (for the chat's "Open" button).
 */
class ReceivedWatcher(
    private val mover: ItemMover,
    private val saver: GallerySaver,
    private val saved: MutableMap<String, String>,
    private val onSaveFailed: (name: String) -> Unit = {},
    private val retryDelayMs: Long = 250,
) {
    fun uriFor(id: String): String? = saved[id]

    /** Call with each SSE event; only "received" events do anything. Blocks while the file is copied. */
    fun handle(type: String, data: String) {
        if (type != "received") return
        val event = try {
            Json.parse(data) as? Map<*, *>
        } catch (_: IllegalArgumentException) {
            null
        } ?: return
        val id = event["id"] as? String ?: return
        val name = event["name"] as? String ?: return
        val path = event["path"] as? String ?: return

        val file = File(path)
        if (!file.isFile) {
            onSaveFailed(name)
            return
        }
        val uri = saver.save(file, name, null).getOrNull()
        if (uri == null) {
            onSaveFailed(name)
            return
        }
        saved[id] = uri
        // The file is already saved, so make sure the chat learns where it went.
        val where = if (MediaTarget.folderFor(null, name) == GalleryFolder.Download) "Downloads" else "Gallery"
        repeat(MARK_ATTEMPTS) { attempt ->
            if (mover.markMoved(id, where)) return
            if (attempt < MARK_ATTEMPTS - 1 && retryDelayMs > 0) Thread.sleep(retryDelayMs)
        }
    }

    private companion object {
        const val MARK_ATTEMPTS = 3
    }
}
