package io.github.hamzabhai.jhatpat.logic

/** Turns the name another app gives a shared file into a safe display name. */
object ShareNames {
    private val extensionByMime = mapOf(
        "image/jpeg" to ".jpg",
        "image/png" to ".png",
        "image/gif" to ".gif",
        "image/webp" to ".webp",
        "image/heic" to ".heic",
        "video/mp4" to ".mp4",
        "video/quicktime" to ".mov",
        "video/webm" to ".webm",
        "audio/mpeg" to ".mp3",
        "application/pdf" to ".pdf",
        "text/plain" to ".txt",
    )

    /** [index] is the file's position in the share (used when it has no usable name). */
    fun displayName(raw: String?, index: Int, mime: String?): String {
        val cleaned = raw.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filterNot { it.isISOControl() }
            .trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            return "shared-$index" + extensionByMime[mime.orEmpty().lowercase()].orEmpty()
        }
        return cleaned
    }
}
