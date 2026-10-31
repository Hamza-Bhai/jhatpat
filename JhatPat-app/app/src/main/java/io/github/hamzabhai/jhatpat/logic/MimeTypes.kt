package io.github.hamzabhai.jhatpat.logic

/** File extension to MIME type for the files people usually share. Android's own table needs a Context. */
object MimeTypes {
    private val byExtension = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "mov" to "video/quicktime",
        "webm" to "video/webm", "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "wav" to "audio/wav", "ogg" to "audio/ogg",
        "pdf" to "application/pdf", "txt" to "text/plain", "zip" to "application/zip",
        "apk" to "application/vnd.android.package-archive",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    fun forName(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return byExtension[name.substring(dot + 1).lowercase()]
    }
}
