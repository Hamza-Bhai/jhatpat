package io.github.hamzabhai.jhatpat.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MimeTypesTest {
    @Test
    fun commonExtensions() {
        assertEquals("image/jpeg", MimeTypes.forName("a.jpg"))
        assertEquals("image/jpeg", MimeTypes.forName("A.JPEG"))
        assertEquals("image/png", MimeTypes.forName("x.png"))
        assertEquals("image/heic", MimeTypes.forName("IMG_1.HEIC"))
        assertEquals("video/mp4", MimeTypes.forName("clip.mp4"))
        assertEquals("video/quicktime", MimeTypes.forName("clip.mov"))
        assertEquals("application/pdf", MimeTypes.forName("doc.pdf"))
        assertEquals("audio/mpeg", MimeTypes.forName("song.mp3"))
        assertEquals("application/zip", MimeTypes.forName("folder.zip"))
    }

    @Test
    fun unknownOrMissingExtensionIsNull() {
        assertNull(MimeTypes.forName("noextension"))
        assertNull(MimeTypes.forName("weird.qqq"))
        assertNull(MimeTypes.forName(".hidden"))
        assertNull(MimeTypes.forName("trailingdot."))
    }

    @Test
    fun mimeForAFolderKeepsMediaTypesAndFixesMismatches() {
        assertEquals("image/jpeg", MediaTarget.mimeFor(GalleryFolder.Pictures, "a.jpg", "image/jpeg"))
        assertEquals("image/png", MediaTarget.mimeFor(GalleryFolder.Pictures, "a.png", "application/octet-stream"))
        assertEquals("image/jpeg", MediaTarget.mimeFor(GalleryFolder.Pictures, "mystery", null))
        assertEquals("video/mp4", MediaTarget.mimeFor(GalleryFolder.Movies, "mystery", ""))
        assertEquals("video/x-matroska", MediaTarget.mimeFor(GalleryFolder.Movies, "a.mkv", null))
        assertEquals("application/pdf", MediaTarget.mimeFor(GalleryFolder.Download, "a.pdf", null))
        assertEquals("application/octet-stream", MediaTarget.mimeFor(GalleryFolder.Download, "weird.qqq", null))
        assertEquals("text/csv", MediaTarget.mimeFor(GalleryFolder.Download, "t.bin", "text/csv"))
    }
}
