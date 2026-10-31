package io.github.hamzabhai.jhatpat.server

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ServerProcessTest {
    private val started = mutableListOf<ServerProcess>()

    private fun stub(mode: String, env: Map<String, String> = emptyMap()): ServerProcess {
        val exe = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$exe")
        val args = listOf("-cp", System.getProperty("java.class.path"), "io.github.hamzabhai.jhatpat.server.StubServer", mode)
        return ServerProcess(java, args, env).also { started += it }
    }

    @After
    fun cleanUp() = started.forEach { it.stop() }

    @Test
    fun returnsTheStartupLine() = runBlocking {
        val info = stub("ok").start(timeoutMs = 20_000)
        assertEquals(1234, info.port)
        assertEquals("stubtoken", info.hostToken)
        assertEquals("4321", info.pin)
    }

    @Test
    fun ignoresLinesBeforeTheJson() = runBlocking {
        assertEquals(1234, stub("noisy").start(timeoutMs = 20_000).port)
    }

    @Test
    fun passesTheEnvironment() = runBlocking {
        val info = stub("env", mapOf("JP_TEST_TOKEN" to "from-env")).start(timeoutMs = 20_000)
        assertEquals("from-env", info.hostToken)
    }

    @Test
    fun earlyExitReportsTheOutput() = runBlocking {
        try {
            stub("crash").start(timeoutMs = 20_000)
            fail("expected ServerStartException")
        } catch (e: ServerStartException) {
            assertTrue(e.message, e.message!!.contains("boom: cannot bind port"))
        }
    }

    @Test
    fun silentServerTimesOutAndIsKilled() = runBlocking {
        val p = stub("silent")
        val t0 = System.nanoTime()
        try {
            p.start(timeoutMs = 1500)
            fail("expected ServerStartException")
        } catch (e: ServerStartException) {
            assertTrue(e.message, e.message!!.contains("did not start"))
        }
        assertTrue("took too long", (System.nanoTime() - t0) / 1_000_000 < 15_000)
        assertFalse(p.isAlive)
    }

    @Test
    fun stopEndsTheProcessAndIsIdempotent() = runBlocking {
        val p = stub("ok")
        p.start(timeoutMs = 20_000)
        assertTrue(p.isAlive)
        p.stop()
        assertFalse(p.isAlive)
        p.stop() // must not throw
    }

    @Test
    fun missingBinaryIsAStartException() = runBlocking {
        val p = ServerProcess(File("does-not-exist-jhatpat"), emptyList())
        try {
            p.start(timeoutMs = 1000)
            fail("expected ServerStartException")
        } catch (e: ServerStartException) {
            assertTrue(e.message, e.message!!.contains("does-not-exist-jhatpat"))
        }
    }
}
