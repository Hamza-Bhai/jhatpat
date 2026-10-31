package io.github.hamzabhai.jhatpat.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.InputStream

/** What another app shared with us: files (by URI text), or a text/link when there are no files. */
data class ShareRequest(val uris: List<String>, val text: String?) {
    val isEmpty: Boolean get() = uris.isEmpty() && text == null
}

object ShareIntents {
    fun parse(intent: Intent): ShareRequest {
        val uris = linkedSetOf<String>()
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it.toString() }
        }
        when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)?.let { uris += it.toString() }
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent).forEach { uris += it.toString() }
        }
        val text = if (uris.isEmpty()) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } else null
        return ShareRequest(uris.toList(), text)
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
}

/** Reads shared items through the content resolver. */
class ResolverShareSource(private val resolver: ContentResolver) : ShareSource {
    override fun open(id: String): InputStream? = resolver.openInputStream(Uri.parse(id))

    override fun name(id: String): String? {
        val uri = Uri.parse(id)
        query(uri, OpenableColumns.DISPLAY_NAME) { it.getString(0) }?.let { return it }
        return uri.lastPathSegment
    }

    override fun size(id: String): Long? {
        val uri = Uri.parse(id)
        val fromColumn = query(uri, OpenableColumns.SIZE) { if (it.isNull(0)) null else it.getLong(0) }
        if (fromColumn != null && fromColumn >= 0) return fromColumn
        return try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { len -> len >= 0 } }
        } catch (_: Exception) {
            null
        }
    }

    override fun mime(id: String): String? = resolver.getType(Uri.parse(id))

    private fun <T> query(uri: Uri, column: String, read: (android.database.Cursor) -> T?): T? = try {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) read(it) else null }
    } catch (_: Exception) {
        null
    }
}
