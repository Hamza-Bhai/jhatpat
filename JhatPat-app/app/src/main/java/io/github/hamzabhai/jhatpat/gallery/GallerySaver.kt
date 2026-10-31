package io.github.hamzabhai.jhatpat.gallery

import io.github.hamzabhai.jhatpat.logic.GalleryFolder
import io.github.hamzabhai.jhatpat.logic.MediaTarget
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Shared storage as the saver sees it: names already taken, and a place to write a new file. */
interface MediaStoreGateway {
    /** File names already in `<folder>/JhatPat`. */
    fun existingNames(folder: GalleryFolder): Set<String>

    /** Writes [source] into `<folder>/JhatPat` as [displayName] and returns the new item's URI text. */
    fun write(folder: GalleryFolder, displayName: String, mime: String, source: InputStream): Result<String>
}

/** Moves received files into the Gallery (photos, videos) or Downloads (everything else). */
class GallerySaver(private val gateway: MediaStoreGateway) {
    /**
     * Writes [source] into shared storage under a name that does not clash with an existing file,
     * and deletes [source] only once the write succeeded. Returns the URI text of the saved file.
     */
    fun save(source: File, name: String, mime: String?): Result<String> {
        if (!source.isFile) return Result.failure(FileNotFoundException(source.path))
        val folder = MediaTarget.folderFor(mime, name)
        val unique = MediaTarget.uniqueName(name, gateway.existingNames(folder))
        val result = try {
            source.inputStream().use { gateway.write(folder, unique, MediaTarget.mimeFor(folder, name, mime), it) }
        } catch (e: IOException) {
            Result.failure(e)
        }
        if (result.isSuccess) source.delete()
        return result
    }
}
