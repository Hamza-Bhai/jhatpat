package io.github.hamzabhai.jhatpat.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareNamesTest {
    @Test
    fun stripsPathComponents() {
        assertEquals("y.png", ShareNames.displayName("../../x/y.png", 1, "image/png"))
        assertEquals("c.txt", ShareNames.displayName("""a\b\c.txt""", 1, "text/plain"))
    }

    @Test
    fun missingNameGetsIndexedFallbackWithMimeExtension() {
        assertEquals("shared-3.png", ShareNames.displayName(null, 3, "image/png"))
        assertEquals("shared-1.mp4", ShareNames.displayName("", 1, "video/mp4"))
        assertEquals("shared-2", ShareNames.displayName("   ", 2, "application/x-unknown"))
    }

    @Test
    fun namesThatAreOnlyPathPiecesFallBack() {
        assertEquals("shared-5.pdf", ShareNames.displayName("foo/", 5, "application/pdf"))
        assertEquals("shared-6", ShareNames.displayName("..", 6, null))
        assertEquals("shared-7", ShareNames.displayName(".", 7, null))
    }

    @Test
    fun keepsEmojiAndSpaces() {
        assertEquals("🎉 shaadi.mp4", ShareNames.displayName("🎉 shaadi.mp4", 1, "video/mp4"))
    }

    @Test
    fun dropsControlCharacters() {
        assertEquals("ab.txt", ShareNames.displayName("a\u0000b\n.txt", 1, "text/plain"))
    }
}
