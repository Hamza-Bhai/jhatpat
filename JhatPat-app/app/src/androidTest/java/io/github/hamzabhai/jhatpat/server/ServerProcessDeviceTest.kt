package io.github.hamzabhai.jhatpat.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Runs the real bundled server on the device, from the native library directory. */
@RunWith(AndroidJUnit4::class)
class ServerProcessDeviceTest {
    @Test
    fun startsTheBundledServerTalksToItAndStopsIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binary = File(context.applicationInfo.nativeLibraryDir, "libjhatpat.so")
        assertTrue("server binary missing: $binary", binary.exists())
        val home = context.filesDir
        val args = listOf(
            "-json-startup", "-exit-on-stdin-close", "-no-browser", "-no-doctor",
            "-ip", "127.0.0.1", "-port", "9500",
            "-dir", File(home, "recv").path, "-config-dir", File(home, "cfg").path,
        )
        val server = ServerProcess(binary, args, mapOf("HOME" to home.path))
        try {
            val info = server.start(timeoutMs = 20_000)
            assertEquals("127.0.0.1", info.ip)

            val health = URL("http://127.0.0.1:${info.port}/api/health").readText()
            assertTrue(health, health.contains("JhatPat"))

            // The host cookie from the startup line is accepted over loopback.
            val api = HostApi("http://127.0.0.1:${info.port}", info.hostToken)
            assertTrue("setIp should be accepted", api.setIp("192.168.43.1"))
            assertFalse("no APK is configured, so enabling must be refused", api.enableApk(10))
            assertFalse("a wrong token must be refused", HostApi("http://127.0.0.1:${info.port}", "wrong").setIp("192.168.43.1"))

            server.stop()
            assertFalse(server.isAlive)
            assertFalse("port should be closed after stop", canConnect(info.port))
        } finally {
            server.stop()
        }
    }

    private fun canConnect(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) }
        true
    } catch (_: Exception) {
        false
    }
}
