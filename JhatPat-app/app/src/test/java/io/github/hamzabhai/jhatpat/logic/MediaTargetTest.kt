package io.github.hamzabhai.jhatpat.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTargetTest {
    @Test
    fun imagesGoToPictures() {
        assertEquals(GalleryFolder.Pictures, MediaTarget.folderFor("image/jpeg", "a.jpg"))
    }

    @Test
    fun videosGoToMovies() {
        assertEquals(GalleryFolder.Movies, MediaTarget.folderFor("video/mp4", "a.mp4"))
    }

    @Test
    fun everythingElseGoesToDownload() {
        assertEquals(GalleryFolder.Download, MediaTarget.folderFor("application/pdf", "a.pdf"))
        assertEquals(GalleryFolder.Download, MediaTarget.folderFor(null, "notes"))
        assertEquals(GalleryFolder.Download, MediaTarget.folderFor("x/y", "weird"))
    }

    @Test
    fun extensionDecidesWhenMimeIsMissingOrGeneric() {
        assertEquals(GalleryFolder.Pictures, MediaTarget.folderFor(null, "IMG_1.HEIC"))
        assertEquals(GalleryFolder.Movies, MediaTarget.folderFor("application/octet-stream", "clip.mkv"))
        assertEquals(GalleryFolder.Pictures, MediaTarget.folderFor("", "x.webp"))
    }

    @Test
    fun aKnownNonMediaMimeIsNotOverriddenByExtension() {
        assertEquals(GalleryFolder.Download, MediaTarget.folderFor("application/pdf", "scan.jpg"))
    }

    @Test
    fun uniqueNameKeepsFreeNames() {
        assertEquals("a.jpg", MediaTarget.uniqueName("a.jpg", emptySet()))
    }

    @Test
    fun uniqueNameAddsTheFirstFreeSuffix() {
        assertEquals("a (1).jpg", MediaTarget.uniqueName("a.jpg", setOf("a.jpg")))
        assertEquals("a (2).jpg", MediaTarget.uniqueName("a.jpg", setOf("a.jpg", "a (1).jpg")))
    }

    @Test
    fun uniqueNameWorksWithoutExtensionAndIgnoresCase() {
        assertEquals("file (1)", MediaTarget.uniqueName("file", setOf("file")))
        assertEquals("Photo (1).JPG", MediaTarget.uniqueName("Photo.JPG", setOf("photo.jpg")))
    }

    @Test
    fun uniqueNameTreatsALeadingDotAsPartOfTheName() {
        assertEquals(".bashrc (1)", MediaTarget.uniqueName(".bashrc", setOf(".bashrc")))
    }
}
