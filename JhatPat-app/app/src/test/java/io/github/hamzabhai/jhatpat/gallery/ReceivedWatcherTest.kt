package io.github.hamzabhai.jhatpat.gallery

import io.github.hamzabhai.jhatpat.logic.GalleryFolder
import io.github.hamzabhai.jhatpat.server.ItemMover
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class ReceivedWatcherTest {
    private class FakeMover(private val answers: MutableList<Boolean> = mutableListOf(true)) : ItemMover {
        val calls = mutableListOf<Pair<String, String>>()
        override fun markMoved(id: String, where: String): Boolean {
            calls += id to where
            return if (answers.size > 1) answers.removeAt(0) else answers.first()
        }
    }

    private val temp = mutableListOf<File>()
    private val saved = ConcurrentHashMap<String, String>()
    private val failures = mutableListOf<String>()

    private fun file(suffix: String) = File.createTempFile("received", suffix).also { it.writeText("bytes"); temp += it }

    @After
    fun cleanUp() = temp.forEach { it.delete() }

    private fun event(id: String, name: String, path: String) =
        """{"id":"$id","name":"$name","size":5,"path":${io.github.hamzabhai.jhatpat.util.Json.quote(path)}}"""

    private fun watcher(gateway: GallerySaverTest.FakeGateway, mover: ItemMover, retryDelayMs: Long = 0) =
        ReceivedWatcher(mover, GallerySaver(gateway), saved, { failures += it }, retryDelayMs)

    @Test
    fun movesTheFileToTheGalleryAndMarksTheItem() {
        val gateway = GallerySaverTest.FakeGateway()
        val mover = FakeMover()
        val source = file(".jpg")
        watcher(gateway, mover).handle("received", event("i1", "photo.jpg", source.path))
        assertEquals(GalleryFolder.Pictures, gateway.written.single().folder)
        assertEquals(listOf("i1" to "Gallery"), mover.calls)
        assertEquals("content://media/photo.jpg", saved["i1"])
        assertFalse(source.exists())
    }

    @Test
    fun ignoresOtherEventTypes() {
        val gateway = GallerySaverTest.FakeGateway()
        val mover = FakeMover()
        watcher(gateway, mover).handle("item", event("i1", "photo.jpg", file(".jpg").path))
        assertTrue(gateway.written.isEmpty())
        assertTrue(mover.calls.isEmpty())
    }

    @Test
    fun aFailedSaveLeavesTheFileAndTheItemAlone() {
        val gateway = GallerySaverTest.FakeGateway(failWith = IOException("disk full"))
        val mover = FakeMover()
        val source = file(".jpg")
        watcher(gateway, mover).handle("received", event("i1", "photo.jpg", source.path))
        assertTrue(source.exists())
        assertTrue(mover.calls.isEmpty())
        assertTrue(saved.isEmpty())
        assertEquals(listOf("photo.jpg"), failures)
    }

    @Test
    fun aMissingFileIsReportedNotMoved() {
        val mover = FakeMover()
        watcher(GallerySaverTest.FakeGateway(), mover).handle("received", event("i1", "gone.jpg", "no-such-file-xyz"))
        assertTrue(mover.calls.isEmpty())
        assertEquals(listOf("gone.jpg"), failures)
    }

    @Test
    fun badEventDataIsIgnored() {
        val mover = FakeMover()
        val w = watcher(GallerySaverTest.FakeGateway(), mover)
        w.handle("received", "not json")
        w.handle("received", """{"id":"x"}""")
        w.handle("received", """{"id":1,"name":2,"path":3}""")
        assertTrue(mover.calls.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun markMovedIsRetriedUntilItWorks() {
        val mover = FakeMover(mutableListOf(false, false, true))
        watcher(GallerySaverTest.FakeGateway(), mover).handle("received", event("i1", "a.pdf", file(".pdf").path))
        assertEquals(3, mover.calls.size)
        assertEquals("content://media/a.pdf", saved["i1"])
    }

    @Test
    fun uriForReturnsTheSavedUriOrNull() {
        val w = watcher(GallerySaverTest.FakeGateway(), FakeMover())
        w.handle("received", event("i1", "a.pdf", file(".pdf").path))
        assertEquals("content://media/a.pdf", w.uriFor("i1"))
        assertEquals(null, w.uriFor("nope"))
    }

    @Test
    fun theLabelNamesWhereTheFileWentGalleryOrDownloads() {
        val mover = FakeMover()
        val w = watcher(GallerySaverTest.FakeGateway(), mover)
        w.handle("received", event("p", "photo.jpg", file(".jpg").path))
        w.handle("received", event("v", "clip.mp4", file(".mp4").path))
        w.handle("received", event("d", "doc.pdf", file(".pdf").path))
        assertEquals(listOf("p" to "Gallery", "v" to "Gallery", "d" to "Downloads"), mover.calls)
    }
}
