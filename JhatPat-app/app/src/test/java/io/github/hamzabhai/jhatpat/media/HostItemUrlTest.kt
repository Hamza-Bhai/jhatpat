package io.github.hamzabhai.jhatpat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostItemUrlTest {
    @Test
    fun viewAndDownloadUrls() {
        assertEquals("rvaSHzsezfJlnt00", HostItemUrl.parse("/api/host/items/rvaSHzsezfJlnt00/view")?.id)
        assertEquals(HostItemUrl.Kind.View, HostItemUrl.parse("/api/host/items/abc/view")?.kind)
        assertEquals(HostItemUrl.Kind.Download, HostItemUrl.parse("/api/host/items/abc/download")?.kind)
    }

    @Test
    fun idsWithDashAndUnderscore() {
        assertEquals("a-b_C9", HostItemUrl.parse("/api/host/items/a-b_C9/view")?.id)
    }

    @Test
    fun otherPathsAreNotItemUrls() {
        for (path in listOf(
            "", "/", "/host", "/api/host/state", "/api/host/items", "/api/host/items/", "/api/host/items/abc",
            "/api/host/items/abc/other", "/api/host/items/abc/view/extra", "/api/dev/items/abc/view",
            "/api/host/items//view", "api/host/items/abc/view", "/x/api/host/items/abc/view",
        )) {
            assertNull(path, HostItemUrl.parse(path))
        }
    }

    @Test
    fun idsThatCouldTraverseOrSmuggleAreRejected() {
        for (path in listOf(
            "/api/host/items/../view", "/api/host/items/a/b/view", "/api/host/items/a%2Fb/view",
            "/api/host/items/a b/view", "/api/host/items/a.b/view", "/api/host/items/..%2F..%2Fetc/view",
        )) {
            assertNull(path, HostItemUrl.parse(path))
        }
    }
}
