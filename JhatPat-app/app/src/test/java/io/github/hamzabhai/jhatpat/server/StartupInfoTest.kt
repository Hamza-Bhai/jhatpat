package io.github.hamzabhai.jhatpat.server

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class StartupInfoTest {
    // The exact shape the Go server prints (web/internal/launch).
    private val line = """{"port":9400,"hostToken":"tok","pin":"1831","secret":"sec","ip":"192.168.43.1","hostUrl":"http://localhost:9400/host?t=tok"}"""

    @Test
    fun parsesTheGoServersLine() {
        val info = StartupInfo.parse(line)
        assertEquals(StartupInfo(9400, "tok", "1831", "sec", "192.168.43.1", "http://localhost:9400/host?t=tok"), info)
    }

    @Test
    fun ignoresUnknownFields() {
        assertEquals(9400, StartupInfo.parse(line.replace("{", """{"extra":true,""")).port)
    }

    @Test
    fun rejectsEmptyGarbageAndMissingFields() {
        for (bad in listOf("", "starting up...", "[]", """{"port":1}""", """{"port":"x","hostToken":"t","pin":"p","secret":"s","ip":"","hostUrl":"u"}""")) {
            try {
                StartupInfo.parse(bad)
                fail("accepted: $bad")
            } catch (expected: IllegalArgumentException) {
                // fine
            }
        }
    }
}
