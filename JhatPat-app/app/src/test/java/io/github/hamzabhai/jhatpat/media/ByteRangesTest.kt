package io.github.hamzabhai.jhatpat.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteRangesTest {
    private val total = 1000L

    private fun parse(header: String?) = ByteRanges.parse(header, total)

    @Test
    fun noHeaderMeansTheWholeFile() {
        assertEquals(RangeRequest.Whole, parse(null))
        assertEquals(RangeRequest.Whole, parse(""))
        assertEquals(RangeRequest.Whole, parse("   "))
    }

    @Test
    fun anExplicitRange() {
        assertEquals(RangeRequest.Partial(ByteRange(0, 99)), parse("bytes=0-99"))
        assertEquals(RangeRequest.Partial(ByteRange(0, 0)), parse("bytes=0-0"))
        assertEquals(RangeRequest.Partial(ByteRange(500, 999)), parse("bytes=500-999"))
    }

    @Test
    fun anOpenEndedRangeRunsToTheEnd() {
        assertEquals(RangeRequest.Partial(ByteRange(100, 999)), parse("bytes=100-"))
    }

    @Test
    fun aSuffixRangeIsTheLastBytes() {
        assertEquals(RangeRequest.Partial(ByteRange(800, 999)), parse("bytes=-200"))
        assertEquals(RangeRequest.Partial(ByteRange(0, 999)), parse("bytes=-5000"))
    }

    @Test
    fun anEndPastTheFileIsClamped() {
        assertEquals(RangeRequest.Partial(ByteRange(900, 999)), parse("bytes=900-5000"))
    }

    @Test
    fun aStartPastTheFileCannotBeSatisfied() {
        assertEquals(RangeRequest.Unsatisfiable, parse("bytes=1000-"))
        assertEquals(RangeRequest.Unsatisfiable, parse("bytes=2000-3000"))
        assertEquals(RangeRequest.Unsatisfiable, parse("bytes=-0"))
    }

    @Test
    fun anEmptyFileHasNoSatisfiableRange() {
        assertEquals(RangeRequest.Unsatisfiable, ByteRanges.parse("bytes=0-", 0))
        assertEquals(RangeRequest.Whole, ByteRanges.parse(null, 0))
    }

    @Test
    fun headersWeDoNotUnderstandAreIgnored() {
        assertEquals(RangeRequest.Whole, parse("items=1-2"))
        assertEquals(RangeRequest.Whole, parse("bytes=abc"))
        assertEquals(RangeRequest.Whole, parse("bytes=0-1,5-6")) // several ranges: send everything
        assertEquals(RangeRequest.Whole, parse("bytes=50-10")) // backwards
        assertEquals(RangeRequest.Whole, parse("bytes=-"))
        assertEquals(RangeRequest.Whole, parse("bytes=1.5-3"))
    }

    @Test
    fun spacesAndCaseAreTolerated() {
        assertEquals(RangeRequest.Partial(ByteRange(0, 9)), parse(" Bytes = 0 - 9 "))
    }

    @Test
    fun theLengthOfARange() {
        assertEquals(10L, ByteRange(0, 9).length)
        assertEquals(1L, ByteRange(5, 5).length)
    }

    @Test
    fun hugeNumbersDoNotOverflow() {
        assertEquals(RangeRequest.Whole, parse("bytes=99999999999999999999-"))
        assertEquals(RangeRequest.Partial(ByteRange(0, 999)), parse("bytes=0-9223372036854775807"))
    }
}
