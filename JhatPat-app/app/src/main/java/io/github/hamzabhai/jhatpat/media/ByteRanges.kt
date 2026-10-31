package io.github.hamzabhai.jhatpat.media

/** An inclusive range of bytes, both ends inside the file. */
data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

/** What a `Range` request header asks for. */
sealed interface RangeRequest {
    /** No usable range: send the whole file. */
    data object Whole : RangeRequest

    data class Partial(val range: ByteRange) : RangeRequest

    /** The range starts after the end of the file (HTTP 416). */
    data object Unsatisfiable : RangeRequest
}

object ByteRanges {
    /**
     * Understands the single-range forms players use: `bytes=a-b`, `bytes=a-` and `bytes=-n`. Anything
     * else (several ranges, another unit, nonsense, a backwards range) is ignored, so the whole file is sent.
     */
    fun parse(header: String?, total: Long): RangeRequest {
        val compact = header.orEmpty().lowercase().filterNot { it.isWhitespace() }
        if (!compact.startsWith("bytes=")) return RangeRequest.Whole
        val spec = compact.removePrefix("bytes=")
        val dash = spec.indexOf('-')
        if (spec.contains(',') || dash < 0 || spec.indexOf('-', dash + 1) >= 0) return RangeRequest.Whole
        val left = spec.substring(0, dash)
        val right = spec.substring(dash + 1)
        if (left.isEmpty() && right.isEmpty()) return RangeRequest.Whole
        if (!left.all { it in '0'..'9' } || !right.all { it in '0'..'9' }) return RangeRequest.Whole

        if (left.isEmpty()) { // the last n bytes
            val n = right.toLongOrNull() ?: return RangeRequest.Whole
            if (n == 0L || total <= 0) return RangeRequest.Unsatisfiable
            return RangeRequest.Partial(ByteRange(maxOf(0, total - n), total - 1))
        }
        val start = left.toLongOrNull() ?: return RangeRequest.Whole
        val end = if (right.isEmpty()) Long.MAX_VALUE else right.toLongOrNull() ?: return RangeRequest.Whole
        if (right.isNotEmpty() && end < start) return RangeRequest.Whole
        if (total <= 0 || start >= total) return RangeRequest.Unsatisfiable
        return RangeRequest.Partial(ByteRange(start, minOf(end, total - 1)))
    }
}
