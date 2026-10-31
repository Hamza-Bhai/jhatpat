package io.github.hamzabhai.jhatpat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {
    @Test
    fun parsesNestedStructures() {
        val v = Json.parse("""{"a":1,"b":[true,false,null,"x"],"c":{"d":2.5}}""") as Map<*, *>
        assertEquals(1L, v["a"])
        assertEquals(listOf(true, false, null, "x"), v["b"])
        assertEquals(2.5, (v["c"] as Map<*, *>)["d"])
    }

    @Test
    fun integersAreLongAndLargeOnesSurvive() {
        assertEquals(8388608L, Json.parse("8388608"))
        assertEquals(-5L, Json.parse("-5"))
        assertEquals(1.5e3, Json.parse("1.5e3"))
    }

    @Test
    fun parsesStringEscapesAndUnicode() {
        assertEquals("a\"b\\c\nd\u00e9", Json.parse("\"a\\\"b\\\\c\\nd\\u00e9\""))
        assertEquals("🎉", Json.parse("\"\\ud83c\\udf89\""))
        assertEquals("🎉 shaadi", Json.parse("\"🎉 shaadi\""))
    }

    @Test
    fun parsesNullAndWhitespace() {
        assertNull(Json.parse("  null  "))
        assertEquals(emptyList<Any?>(), Json.parse(" [ ] "))
        assertEquals(emptyMap<String, Any?>(), Json.parse(" { } "))
    }

    @Test
    fun rejectsBadInput() {
        for (bad in listOf("", "{", "[1,", "nope", "{} x", """{"a":}""", """{"a" 1}""", "\"unterminated", "[1 2]", """{"a":1,}""")) {
            try {
                Json.parse(bad)
                fail("accepted: $bad")
            } catch (expected: IllegalArgumentException) {
                // fine
            }
        }
    }

    @Test
    fun quoteEscapes() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\"", Json.quote("a\"b\\c\n\t"))
        assertEquals("\"\\u0001\"", Json.quote("\u0001"))
        assertEquals("\"🎉 é\"", Json.quote("🎉 é"))
    }

    @Test
    fun quoteRoundTripsThroughParse() {
        val tricky = "he said \"hi\" \\ / \n \u0000 🎉 </script>"
        assertEquals(tricky, Json.parse(Json.quote(tricky)))
        assertTrue(Json.quote("x").startsWith("\""))
    }
}
