package io.github.hamzabhai.jhatpat.media

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.InputStream

/** Finds moved files through the URIs the Gallery gave them ([saved] maps chat item id to URI text). */
class ContentResolverMedia(private val resolver: ContentResolver, private val saved: Map<String, String>) : MediaSource {
    override fun find(id: String): MediaSource.Found? {
        val uri = Uri.parse(saved[id] ?: return null)
        val length = try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return null
        } catch (_: Exception) {
            return null // the file was deleted from the Gallery, or the URI is no longer readable
        }
        if (length < 0) return null // unknown size: ranges cannot be served, so let the page show its fallback
        return MediaSource.Found(resolver.getType(uri).orEmpty(), length) { start -> openAt(uri, start) }
    }

    private fun openAt(uri: Uri, start: Long): InputStream {
        val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("cannot open $uri")
        val stream = descriptor.createInputStream()
        var left = start
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped <= 0) break
            left -= skipped
        }
        return stream
    }
}
