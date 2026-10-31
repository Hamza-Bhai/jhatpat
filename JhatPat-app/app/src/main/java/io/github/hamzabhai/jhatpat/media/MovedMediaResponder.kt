package io.github.hamzabhai.jhatpat.media

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream

/** Files that were moved out of JhatPat's folder (to the Gallery), found by chat item id. */
interface MediaSource {
    /** The moved file for [id], or null when this id was not moved. */
    fun find(id: String): Found?

    /** [openAt] returns a stream that starts [start] bytes into the file. */
    class Found(val mime: String, val length: Long, val openAt: (start: Long) -> InputStream)
}

/** An HTTP answer the WebView can show. */
class Reply(
    val status: Int,
    val reason: String,
    val mime: String,
    val headers: Map<String, String>,
    val body: InputStream,
)

/**
 * Answers the host page's requests for a chat item whose file was moved to the Gallery, straight from
 * that file, so photos and videos still preview (and videos can seek) without a second copy on the phone.
 */
class MovedMediaResponder(private val media: MediaSource) {
    /** Null when [id] is not a moved file: the request then goes to the server as usual. */
    fun respond(id: String, rangeHeader: String?): Reply? {
        val found = media.find(id) ?: return null
        val mime = found.mime.ifBlank { "application/octet-stream" }
        return when (val request = ByteRanges.parse(rangeHeader, found.length)) {
            RangeRequest.Whole -> Reply(
                200, "OK", mime,
                mapOf("Accept-Ranges" to "bytes", "Content-Length" to found.length.toString()),
                LimitedInputStream(found.openAt(0), found.length),
            )
            is RangeRequest.Partial -> Reply(
                206, "Partial Content", mime,
                mapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Range" to "bytes ${request.range.start}-${request.range.end}/${found.length}",
                    "Content-Length" to request.range.length.toString(),
                ),
                LimitedInputStream(found.openAt(request.range.start), request.range.length),
            )
            RangeRequest.Unsatisfiable -> Reply(
                416, "Range Not Satisfiable", mime,
                mapOf("Content-Range" to "bytes */${found.length}", "Content-Length" to "0"),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
    }
}

/** Ends after [limit] bytes even if the underlying stream has more. */
internal class LimitedInputStream(stream: InputStream, private var remaining: Long) : FilterInputStream(stream) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val n = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(minOf(n, remaining))
        remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

    override fun markSupported(): Boolean = false
}
