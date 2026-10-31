package io.github.hamzabhai.jhatpat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class MovedMediaResponderTest {
    private val data = ByteArray(100) { it.toByte() }

    private class FakeMedia(val bytes: ByteArray, val mime: String = "video/mp4") : MediaSource {
        var opened = 0
        override fun find(id: String): MediaSource.Found? {
            if (id != "known") return null
            return MediaSource.Found(mime, bytes.size.toLong()) { start ->
                opened++
                ByteArrayInputStream(bytes, start.toInt(), bytes.size - start.toInt())
            }
        }
    }

    private fun InputStream.all(): ByteArray = use { it.readBytes() }

    private fun responder(media: FakeMedia = FakeMedia(data)) = MovedMediaResponder(media)

    @Test
    fun aKnownItemWithoutRangeIsServedWhole() {
        val reply = responder().respond("known", null)!!
        assertEquals(200, reply.status)
        assertEquals("video/mp4", reply.mime)
        assertEquals("bytes", reply.headers["Accept-Ranges"])
        assertEquals("100", reply.headers["Content-Length"])
        assertTrue(reply.body.all().contentEquals(data))
    }

    @Test
    fun aRangeIsServedAs206WithExactlyThoseBytes() {
        val reply = responder().respond("known", "bytes=10-19")!!
        assertEquals(206, reply.status)
        assertEquals("bytes 10-19/100", reply.headers["Content-Range"])
        assertEquals("10", reply.headers["Content-Length"])
        assertTrue(reply.body.all().contentEquals(data.copyOfRange(10, 20)))
    }

    @Test
    fun anOpenEndedRangeGoesToTheEnd() {
        val reply = responder().respond("known", "bytes=90-")!!
        assertEquals("bytes 90-99/100", reply.headers["Content-Range"])
        assertTrue(reply.body.all().contentEquals(data.copyOfRange(90, 100)))
    }

    @Test
    fun theBodyNeverRunsPastTheRequestedRange() {
        val reply = responder().respond("known", "bytes=0-4")!!
        assertEquals(5, reply.body.all().size)
    }

    @Test
    fun anUnsatisfiableRangeIs416AndOpensNothing() {
        val media = FakeMedia(data)
        val reply = responder(media).respond("known", "bytes=500-")!!
        assertEquals(416, reply.status)
        assertEquals("bytes */100", reply.headers["Content-Range"])
        assertEquals(0, reply.body.all().size)
        assertEquals(0, media.opened)
    }

    @Test
    fun anUnknownItemIsLeftToTheNetwork() {
        assertNull(responder().respond("other", null))
    }

    @Test
    fun theMimeTypeComesFromTheSource() {
        assertEquals("image/jpeg", MovedMediaResponder(FakeMedia(data, "image/jpeg")).respond("known", null)!!.mime)
    }

    @Test
    fun aMissingMimeTypeFallsBackToBinary() {
        val reply = MovedMediaResponder(FakeMedia(data, mime = "")).respond("known", null)!!
        assertEquals("application/octet-stream", reply.mime)
        assertNotNull(reply.body)
    }

    @Test
    fun aSourceThatReturnsFewerBytesThanItsLengthDoesNotHang() {
        val short = object : MediaSource {
            override fun find(id: String) = MediaSource.Found("video/mp4", 100) { _ -> ByteArrayInputStream(ByteArray(10)) }
        }
        assertEquals(10, MovedMediaResponder(short).respond("known", null)!!.body.all().size)
    }
}
