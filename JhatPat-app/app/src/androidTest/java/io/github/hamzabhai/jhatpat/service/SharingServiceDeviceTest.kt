package io.github.hamzabhai.jhatpat.service

import android.content.Context
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hamzabhai.jhatpat.MainActivity
import io.github.hamzabhai.jhatpat.server.HostApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/** Starts and stops the real foreground service on the device, twice, in WiFi mode. */
@RunWith(AndroidJUnit4::class)
class SharingServiceDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun awaitState(timeoutMs: Long = 30_000, match: (SharingState) -> Boolean): SharingState =
        try {
            withTimeout(timeoutMs) { SharingController.state.first(match) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out; state is ${SharingController.state.value}", e)
        }

    @Test
    fun startsRunsAndStopsTwice() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(2) { round ->
                Log.i("JhatPatTest", "round $round: start, state ${SharingController.state.value}")
                SharingController.start(context, Mode.Wifi)
                val running = awaitState { it is SharingState.Running || it is SharingState.Error }
                assertTrue("round $round: $running", running is SharingState.Running)
                running as SharingState.Running

                val api = HostApi("http://127.0.0.1:${running.info.port}", running.info.hostToken)
                assertNotNull("round $round: the host API must answer", api.summary())
                assertEquals(0, api.summary()!!.connected)

                Log.i("JhatPatTest", "round $round: running on port ${running.info.port}, stopping")
                SharingController.stop(context)
                awaitState { s -> s is SharingState.Idle }
                assertFalse("round $round: port must be closed after stop", canConnect(running.info.port))
            }
        }
    }

    private fun canConnect(port: Int): Boolean {
        repeat(10) {
            val open = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                true
            } catch (_: Exception) {
                false
            }
            if (!open) return false
            Thread.sleep(300)
        }
        return true
    }
}
