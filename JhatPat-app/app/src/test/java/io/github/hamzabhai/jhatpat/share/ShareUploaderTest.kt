package io.github.hamzabhai.jhatpat.share

import io.github.hamzabhai.jhatpat.server.Target
import io.github.hamzabhai.jhatpat.server.Uploader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ShareUploaderTest {
    private class FakeSource(private val items: Map<String, Item>) : ShareSource {
        class Item(val bytes: ByteArray?, val name: String?, val mime: String? = null, val size: Long? = bytes?.size?.toLong())

        override fun open(id: String): InputStream? = items[id]?.bytes?.let { ByteArrayInputStream(it) }
        override fun name(id: String) = items[id]?.name
        override fun size(id: String) = items[id]?.size
        override fun mime(id: String) = items[id]?.mime
    }

    private class Sent(val name: String, val size: Long, val to: Target, val body: ByteArray)

    private class FakeUploader(val failFor: Set<String> = emptySet()) : Uploader {
        val sent = mutableListOf<Sent>()
        override fun upload(name: String, size: Long, to: Target, open: () -> InputStream): Result<Unit> {
            if (name in failFor) return Result.failure(IOException("disk full"))
            sent += Sent(name, size, to, open().use { it.readBytes() })
            return Result.success(Unit)
        }
    }

    private fun source(vararg entries: Pair<String, FakeSource.Item>) = FakeSource(entries.toMap())

    @Test
    fun sendsEachItemInOrderAndReportsProgress() = runBlocking {
        val up = FakeUploader()
        val src = source("u1" to FakeSource.Item(byteArrayOf(1), "a.jpg"), "u2" to FakeSource.Item(byteArrayOf(2, 2), "b.jpg"), "u3" to FakeSource.Item(byteArrayOf(3), "c.jpg"))
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = ShareUploader(up, src).send(listOf("u1", "u2", "u3"), Target("all")) { done, total -> progress += done to total }
        assertEquals(ShareResult(3, emptyList()), result)
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), up.sent.map { it.name })
        assertEquals(listOf(1L, 2L, 1L), up.sent.map { it.size })
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun anUnreadableItemDoesNotStopTheRest() = runBlocking {
        val up = FakeUploader()
        val src = source("u1" to FakeSource.Item(byteArrayOf(1), "a.jpg"), "u2" to FakeSource.Item(null, "gone.jpg"), "u3" to FakeSource.Item(byteArrayOf(3), "c.jpg"))
        val result = ShareUploader(up, src).send(listOf("u1", "u2", "u3"), Target("all")) { _, _ -> }
        assertEquals(ShareResult(2, listOf("gone.jpg")), result)
        assertEquals(listOf("a.jpg", "c.jpg"), up.sent.map { it.name })
    }

    @Test
    fun aFailedUploadIsReportedAndTheRestContinue() = runBlocking {
        val up = FakeUploader(failFor = setOf("b.jpg"))
        val src = source("u1" to FakeSource.Item(byteArrayOf(1), "a.jpg"), "u2" to FakeSource.Item(byteArrayOf(2), "b.jpg"), "u3" to FakeSource.Item(byteArrayOf(3), "c.jpg"))
        val result = ShareUploader(up, src).send(listOf("u1", "u2", "u3"), Target("all")) { _, _ -> }
        assertEquals(ShareResult(2, listOf("b.jpg")), result)
    }

    @Test
    fun nothingToSendSendsNothing() = runBlocking {
        val up = FakeUploader()
        assertEquals(ShareResult(0, emptyList()), ShareUploader(up, source()).send(emptyList(), Target("all")) { _, _ -> })
        assertTrue(up.sent.isEmpty())
    }

    @Test
    fun twoHundredFilesAreAllSent() = runBlocking {
        val up = FakeUploader()
        val ids = (1..200).map { "u$it" }
        val src = FakeSource(ids.associateWith { FakeSource.Item(byteArrayOf(1), "f$it.bin") })
        val result = ShareUploader(up, src).send(ids, Target("device", "d1")) { _, _ -> }
        assertEquals(ShareResult(200, emptyList()), result)
        assertTrue(up.sent.all { it.to == Target("device", "d1") })
    }

    @Test
    fun missingOrUnsafeNamesGetSafeOnes() = runBlocking {
        val up = FakeUploader()
        val src = source(
            "u1" to FakeSource.Item(byteArrayOf(1), null, mime = "image/jpeg"),
            "u2" to FakeSource.Item(byteArrayOf(1), "  ", mime = "video/mp4"),
            "u3" to FakeSource.Item(byteArrayOf(1), "../x/y.png"),
            "u4" to FakeSource.Item(byteArrayOf(1), "🎉 shaadi.mp4"),
        )
        ShareUploader(up, src).send(listOf("u1", "u2", "u3", "u4"), Target("all")) { _, _ -> }
        assertEquals(listOf("shared-1.jpg", "shared-2.mp4", "y.png", "🎉 shaadi.mp4"), up.sent.map { it.name })
    }

    @Test
    fun anUnknownSizeIsCountedFromTheData() = runBlocking {
        val up = FakeUploader()
        val src = source("u1" to FakeSource.Item(ByteArray(1234), "a.bin", size = null))
        ShareUploader(up, src).send(listOf("u1"), Target("all")) { _, _ -> }
        assertEquals(1234L, up.sent.single().size)
        assertEquals(1234, up.sent.single().body.size)
    }

    @Test
    fun aDeclaredSizeIsTrustedWhenKnown() = runBlocking {
        val up = FakeUploader()
        val src = source("u1" to FakeSource.Item(ByteArray(10), "a.bin", size = 10))
        ShareUploader(up, src).send(listOf("u1"), Target("all")) { _, _ -> }
        assertEquals(10L, up.sent.single().size)
    }

    @Test
    fun anItemThatThrowsWhileOpeningIsAFailureNotACrash() = runBlocking {
        val up = FakeUploader()
        val throwing = object : ShareSource {
            override fun open(id: String): InputStream? = throw SecurityException("permission revoked")
            override fun name(id: String) = "locked.pdf"
            override fun size(id: String) = 5L
            override fun mime(id: String) = "application/pdf"
        }
        assertEquals(ShareResult(0, listOf("locked.pdf")), ShareUploader(up, throwing).send(listOf("u1"), Target("all")) { _, _ -> })
    }
}
