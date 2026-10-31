package io.github.hamzabhai.jhatpat.gallery

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.hamzabhai.jhatpat.logic.GalleryFolder
import java.io.File
import java.io.InputStream

/**
 * Saves into `Pictures/JhatPat`, `Movies/JhatPat` or `Download/JhatPat`.
 *
 * Android 10 and newer: through MediaStore, which needs no storage permission for files the app
 * creates. Android 8 and 9: straight into the public folder (needs the storage permission, which
 * the Start screen asks for) and then registered with MediaStore.
 */
class AndroidMediaStoreGateway(context: Context) : MediaStoreGateway {
    private val resolver = context.applicationContext.contentResolver

    override fun existingNames(folder: GalleryFolder): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) scopedNames(folder) else legacyDirectory(folder).list()?.toSet().orEmpty()

    override fun write(folder: GalleryFolder, displayName: String, mime: String, source: InputStream): Result<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writeScoped(folder, displayName, mime, source) else writeLegacy(folder, displayName, mime, source)

    // ---- Android 10+ ----

    private fun scopedNames(folder: GalleryFolder): Set<String> {
        val names = mutableSetOf<String>()
        try {
            resolver.query(
                collection(folder), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("${directoryName(folder)}/JhatPat%"), null,
            )?.use { while (it.moveToNext()) names += it.getString(0) }
        } catch (_: Exception) {
            // an unreadable collection only means a clash is not detected; MediaStore renames on its own
        }
        return names
    }

    private fun writeScoped(folder: GalleryFolder, displayName: String, mime: String, source: InputStream): Result<String> {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${directoryName(folder)}/JhatPat")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection(folder), values)
        } catch (e: Exception) {
            return Result.failure(e)
        } ?: return Result.failure(java.io.IOException("MediaStore refused $displayName"))
        return try {
            resolver.openOutputStream(uri)?.use { out -> source.copyTo(out, BUFFER) } ?: throw java.io.IOException("cannot write $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Result.success(uri.toString())
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            Result.failure(e)
        }
    }

    private fun collection(folder: GalleryFolder): Uri = when (folder) {
        GalleryFolder.Pictures -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        GalleryFolder.Movies -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        GalleryFolder.Download ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")
    }

    // ---- Android 8 and 9 ----

    @Suppress("DEPRECATION")
    private fun legacyDirectory(folder: GalleryFolder): File =
        File(Environment.getExternalStoragePublicDirectory(directoryName(folder)), "JhatPat")

    @Suppress("DEPRECATION")
    private fun writeLegacy(folder: GalleryFolder, displayName: String, mime: String, source: InputStream): Result<String> {
        val target = File(legacyDirectory(folder).also { it.mkdirs() }, displayName)
        return try {
            target.outputStream().use { out -> source.copyTo(out, BUFFER) }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, target.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
            }
            val uri = resolver.insert(collection(folder), values) ?: throw java.io.IOException("MediaStore refused $displayName")
            Result.success(uri.toString())
        } catch (e: Exception) {
            target.delete()
            Result.failure(e)
        }
    }

    private fun directoryName(folder: GalleryFolder): String = when (folder) {
        GalleryFolder.Pictures -> Environment.DIRECTORY_PICTURES
        GalleryFolder.Movies -> Environment.DIRECTORY_MOVIES
        GalleryFolder.Download -> Environment.DIRECTORY_DOWNLOADS
    }

    private companion object {
        const val BUFFER = 256 * 1024
    }
}
