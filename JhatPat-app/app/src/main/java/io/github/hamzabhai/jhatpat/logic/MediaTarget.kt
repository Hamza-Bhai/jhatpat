package io.github.hamzabhai.jhatpat.logic

/** Where received files go in shared storage: `<folder>/JhatPat`. */
enum class GalleryFolder { Pictures, Movies, Download }

object MediaTarget {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
    private val videoExtensions = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v")

    fun folderFor(mime: String?, name: String): GalleryFolder {
        val type = mime.orEmpty().lowercase()
        return when {
            type.startsWith("image/") -> GalleryFolder.Pictures
            type.startsWith("video/") -> GalleryFolder.Movies
            type.isEmpty() || type == "application/octet-stream" -> when (extensionOf(name)) {
                in imageExtensions -> GalleryFolder.Pictures
                in videoExtensions -> GalleryFolder.Movies
                else -> GalleryFolder.Download
            }
            else -> GalleryFolder.Download
        }
    }

    /** The MIME type to store the file under; the media collections only accept their own kind. */
    fun mimeFor(folder: GalleryFolder, name: String, mime: String?): String {
        val given = mime.orEmpty().lowercase()
        return when (folder) {
            GalleryFolder.Pictures ->
                if (given.startsWith("image/")) given else MimeTypes.forName(name)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            GalleryFolder.Movies ->
                if (given.startsWith("video/")) given else MimeTypes.forName(name)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
            GalleryFolder.Download ->
                if (given.isNotEmpty() && given != "application/octet-stream") given else MimeTypes.forName(name) ?: "application/octet-stream"
        }
    }

    /** Returns [name], or `base (n).ext` with the first n that is not in [taken] (compared ignoring case). */
    fun uniqueName(name: String, taken: Set<String>): String {
        val takenLower = taken.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in takenLower) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot <= 0) name else name.substring(0, dot)
        val ext = if (dot <= 0) "" else name.substring(dot)
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate.lowercase() !in takenLower) return candidate
            n++
        }
    }

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
    }
}
