package io.github.hamzabhai.jhatpat.gallery

import io.github.hamzabhai.jhatpat.logic.GalleryFolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream

class GallerySaverTest {
    class FakeGateway(var existing: Set<String> = emptySet(), var failWith: IOException? = null) : MediaStoreGateway {
        class Written(val folder: GalleryFolder, val name: String, val mime: String, val bytes: ByteArray)

        val written = mutableListOf<Written>()
        override fun existingNames(folder: GalleryFolder): Set<String> = existing
        override fun write(folder: GalleryFolder, displayName: String, mime: String, source: InputStream): Result<String> {
            val bytes = source.readBytes()
            failWith?.let { return Result.failure(it) }
            written += Written(folder, displayName, mime, bytes)
            return Result.success("content://media/$displayName")
        }
    }

    private val temp = mutableListOf<File>()

    private fun file(name: String, content: String = "data"): File =
        File.createTempFile("gallery", name).also { it.writeText(content); temp += it }

    @After
    fun cleanUp() = temp.forEach { it.delete() }

    @Test
    fun imageGoesToPictures() {
        val g = FakeGateway()
        val result = GallerySaver(g).save(file(".jpg", "jpegbytes"), "photo.jpg", "image/jpeg")
        assertEquals("content://media/photo.jpg", result.getOrNull())
        val w = g.written.single()
        assertEquals(GalleryFolder.Pictures, w.folder)
        assertEquals("image/jpeg", w.mime)
        assertEquals("jpegbytes", String(w.bytes))
    }

    @Test
    fun videoGoesToMovies() {
        val g = FakeGateway()
        GallerySaver(g).save(file(".mp4"), "clip.mp4", "video/mp4")
        assertEquals(GalleryFolder.Movies, g.written.single().folder)
    }

    @Test
    fun otherFilesGoToDownload() {
        val g = FakeGateway()
        GallerySaver(g).save(file(".pdf"), "doc.pdf", "application/pdf")
        GallerySaver(g).save(file(".bin"), "mystery", null)
        assertEquals(listOf(GalleryFolder.Download, GalleryFolder.Download), g.written.map { it.folder })
        assertEquals("application/octet-stream", g.written[1].mime)
    }

    @Test
    fun theExtensionDecidesWhenNoMimeIsGiven() {
        val g = FakeGateway()
        GallerySaver(g).save(file(".heic"), "IMG_9.HEIC", null)
        assertEquals(GalleryFolder.Pictures, g.written.single().folder)
        assertEquals("image/heic", g.written.single().mime)
    }

    @Test
    fun aNameThatAlreadyExistsGetsASuffix() {
        val g = FakeGateway(existing = setOf("a.jpg", "a (1).jpg"))
        GallerySaver(g).save(file(".jpg"), "a.jpg", "image/jpeg")
        assertEquals("a (2).jpg", g.written.single().name)
    }

    @Test
    fun aFailedWriteKeepsTheSourceFile() {
        val source = file(".jpg")
        val g = FakeGateway(failWith = IOException("disk full"))
        val result = GallerySaver(g).save(source, "a.jpg", "image/jpeg")
        assertTrue(result.isFailure)
        assertTrue(source.exists())
    }

    @Test
    fun aSuccessfulWriteDeletesTheSourceFile() {
        val source = file(".jpg")
        GallerySaver(FakeGateway()).save(source, "a.jpg", "image/jpeg")
        assertFalse(source.exists())
    }

    @Test
    fun aMissingSourceIsAFailure() {
        val result = GallerySaver(FakeGateway()).save(File("does-not-exist-12345.jpg"), "a.jpg", "image/jpeg")
        assertTrue(result.isFailure)
    }
}
