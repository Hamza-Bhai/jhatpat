package io.github.hamzabhai.jhatpat.service

import io.github.hamzabhai.jhatpat.server.HostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferProgressTest {
    private fun t(name: String, size: Long, received: Long) = HostApi.Transfer(name, "Ali", size, received)

    @Test
    fun nothingInProgressMeansNoBar() {
        assertNull(TransferProgress.summarize(emptyList()))
    }

    @Test
    fun oneFileShowsItsNameAndPercent() {
        assertEquals(TransferProgress.Summary(62, "Receiving clip.mp4 · 62%"), TransferProgress.summarize(listOf(t("clip.mp4", 100, 62))))
    }

    @Test
    fun severalFilesAreAddedUp() {
        val s = TransferProgress.summarize(listOf(t("a", 100, 50), t("b", 100, 50)))
        assertEquals(TransferProgress.Summary(50, "Receiving 2 files · 50%"), s)
    }

    @Test
    fun aTransferThatHasNotStartedIsZeroPercent() {
        assertEquals(0, TransferProgress.summarize(listOf(t("a.bin", 1000, 0)))!!.percent)
    }

    @Test
    fun emptyFilesGiveNoBarAndPercentIsClamped() {
        assertNull(TransferProgress.summarize(listOf(t("empty", 0, 0))))
        assertEquals(100, TransferProgress.summarize(listOf(t("a", 10, 25)))!!.percent)
    }

    @Test
    fun hugeSizesDoNotOverflow() {
        val gb = 1L shl 30
        val s = TransferProgress.summarize(listOf(t("big1", 3 * gb, gb), t("big2", 3 * gb, gb)))
        assertEquals(33, s!!.percent)
    }
}
