package io.github.hamzabhai.jhatpat.net

import android.os.Build
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import io.github.hamzabhai.jhatpat.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exploratory device check: does a local-only hotspot start on this phone while it is on WiFi?
 * The outcome is written to logcat (tag JhatPatHotspot); the test only fails if start() throws.
 */
@RunWith(AndroidJUnit4::class)
class HotspotDeviceTest {
    @get:Rule
    val permission: GrantPermissionRule =
        GrantPermissionRule.grant(*HotspotLogic.requiredPermissions(Build.VERSION.SDK_INT).toTypedArray())

    @Test
    fun startsAndStopsAHotspot() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = HotspotController(context)
        Log.i(TAG, "wifi client address before: ${controller.currentWifiIp()}")
        // Android only grants the location check to a foreground app, like the real Start button.
        ActivityScenario.launch(MainActivity::class.java).use {
            val t0 = System.currentTimeMillis()
            val result = controller.start()
            Log.i(TAG, "start() took ${System.currentTimeMillis() - t0} ms: ${describe(result)}")
            Log.i(TAG, "interfaces while the hotspot is up: ${SystemNetworkInfo.candidates()}")
            try {
                assertNotNull(result)
            } finally {
                controller.stop()
            }
        }
        Log.i(TAG, "stopped; wifi client address after: ${controller.currentWifiIp()}")
    }

    private fun describe(r: HotspotResult): String = when (r) {
        is HotspotResult.Started -> "Started ssid=${r.ssid} passwordLength=${r.password.length} ip=${r.ip}"
        is HotspotResult.Failed -> "Failed reason=${r.reason}"
    }

    private companion object {
        const val TAG = "JhatPatHotspot"
    }
}
