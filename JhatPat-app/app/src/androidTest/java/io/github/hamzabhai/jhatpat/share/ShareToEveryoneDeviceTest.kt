package io.github.hamzabhai.jhatpat.share

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hamzabhai.jhatpat.MainActivity
import io.github.hamzabhai.jhatpat.server.HostApi
import io.github.hamzabhai.jhatpat.server.Target
import io.github.hamzabhai.jhatpat.service.Mode
import io.github.hamzabhai.jhatpat.service.SharingController
import io.github.hamzabhai.jhatpat.service.SharingState
import io.github.hamzabhai.jhatpat.util.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random

/**
 * Two "laptops" join the phone's own server over loopback, three files from MediaStore are shared
 * to Everyone, and both laptops must receive all three byte for byte.
 */
@RunWith(AndroidJUnit4::class)
class ShareToEveryoneDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A tiny HTTP client that keeps cookies, like a browser tab. */
    private class Client(private val base: String) {
        private val cookies = linkedMapOf<String, String>()

        class Reply(val code: Int, val bytes: ByteArray) {
            val text: String get() = String(bytes)
        }

        fun call(method: String, path: String, json: String? = null): Reply {
            val conn = URL(base + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = conn.responseCode
            conn.headerFields["Set-Cookie"]?.forEach { line ->
                val pair = line.substringBefore(';')
                cookies[pair.substringBefore('=')] = pair.substringAfter('=')
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return Reply(code, stream?.readBytes() ?: ByteArray(0))
        }
    }

    private fun createDownload(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/JhatPatTest")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri
    }

    @Test
    fun sharesThreeFilesToEveryoneAndBothLaptopsReceiveThem() = runBlocking<Unit> {
        val files = (1..3).associate { "jp_share_$it.bin" to ByteArray(300_000 + it) { i -> Random(it.toLong()).nextInt().toByte().plus(i).toByte() } }
        val uris = files.map { (name, bytes) -> createDownload(name, bytes) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                SharingController.start(context, Mode.Wifi)
                val running = withTimeout(30_000) { SharingController.state.first { it is SharingState.Running } } as SharingState.Running
                val base = "http://127.0.0.1:${running.info.port}"

                val host = Client(base).also { it.call("GET", "/host?t=${running.info.hostToken}") }
                val laptops = listOf("Laptop A", "Laptop B").map { name ->
                    val client = Client(base)
                    val join = client.call("POST", "/api/join", """{"pin":"${running.info.pin}","name":"$name"}""")
                    assertEquals(join.text, 200, join.code)
                    val id = (Json.parse(join.text) as Map<*, *>)["id"] as String
                    assertEquals(200, host.call("POST", "/api/host/devices/$id/approve").code)
                    client
                }
                val api = HostApi(base, running.info.hostToken)
                withTimeout(15_000) { while (api.summary()?.connected != 2) delay(200) }

                val result = ShareUploader(api, ResolverShareSource(context.contentResolver))
                    .send(uris.map { it.toString() }, Target("all")) { _, _ -> }
                assertEquals(ShareResult(3, emptyList()), result)

                for ((index, laptop) in laptops.withIndex()) {
                    val items = Json.parse(laptop.call("GET", "/api/dev/items").text) as List<*>
                    val byName = items.map { it as Map<*, *> }.associateBy { it["name"] as String }
                    assertEquals("laptop $index items", files.keys, byName.keys)
                    for ((name, bytes) in files) {
                        val download = laptop.call("GET", "/api/dev/items/${byName.getValue(name)["id"]}/download")
                        assertEquals(200, download.code)
                        assertTrue("laptop $index: $name differs", download.bytes.contentEquals(bytes))
                    }
                }
                SharingController.stop(context)
                withTimeout(15_000) { SharingController.state.first { it is SharingState.Idle } }
            }
        } finally {
            uris.forEach { context.contentResolver.delete(it, null, null) }
        }
    }
}
