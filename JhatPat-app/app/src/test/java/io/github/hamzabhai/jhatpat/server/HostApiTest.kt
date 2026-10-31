package io.github.hamzabhai.jhatpat.server

import io.github.hamzabhai.jhatpat.server.FakeHttpServer.Request
import io.github.hamzabhai.jhatpat.server.FakeHttpServer.Response
import io.github.hamzabhai.jhatpat.util.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList

class HostApiTest {
    private val seen = CopyOnWriteArrayList<Request>()
    private var fake: FakeHttpServer? = null

    private fun start(handler: (Request) -> Response = { Response(200, """{"ok":true}""") }): HostApi {
        val server = FakeHttpServer { req ->
            seen += req
            handler(req)
        }
        fake = server
        return HostApi("http://127.0.0.1:${server.port}", "TOKEN")
    }

    @After
    fun stopServer() {
        fake?.close()
    }

    private fun json(r: Request) = Json.parse(String(r.body)) as Map<*, *>

    @Test
    fun everyRequestCarriesTheHostCookie() {
        val api = start()
        api.enableApk(10)
        api.setIp("192.168.43.1")
        api.markMoved("abc", "Gallery")
        assertEquals(3, seen.size)
        assertTrue(seen.all { it.headers["cookie"] == "jp_host=TOKEN" })
    }

    @Test
    fun enableApkPostsMinutes() {
        val api = start()
        assertTrue(api.enableApk(10))
        val r = seen.single()
        assertEquals("POST /api/host/apk/enable", "${r.method} ${r.target}")
        assertEquals(10L, json(r)["minutes"])
    }

    @Test
    fun setIpPostsTheAddress() {
        val api = start()
        assertTrue(api.setIp("192.168.43.1"))
        assertEquals("/api/host/ip", seen.single().target)
        assertEquals("192.168.43.1", json(seen.single())["ip"])
    }

    @Test
    fun markMovedPostsWhere() {
        val api = start()
        assertTrue(api.markMoved("item1", "Gallery"))
        assertEquals("/api/host/items/item1/moved", seen.single().target)
        assertEquals("Gallery", json(seen.single())["where"])
    }

    @Test
    fun refusedRequestsReturnFalse() {
        val api = start { Response(403, """{"error":"host only"}""") }
        assertFalse(api.enableApk(10))
        assertFalse(api.setIp("192.168.43.1"))
        assertFalse(api.markMoved("x", "Gallery"))
    }

    @Test
    fun unreachableServerReturnsFalseInsteadOfThrowing() {
        val api = start()
        fake!!.close()
        assertFalse(api.enableApk(10))
    }

    @Test
    fun uploadsTwentyMiBInThreeChunksThenFinishes() {
        val chunk = 8_388_608
        val api = start { r ->
            when {
                r.method == "POST" && r.target == "/api/host/uploads" -> Response(200, """{"id":"u1","chunkSize":$chunk}""")
                r.method == "PUT" -> Response(200, """{"offset":1}""")
                else -> Response(200, """{"id":"item"}""")
            }
        }
        val data = ByteArray(20 * 1024 * 1024) { (it % 251).toByte() }
        val result = api.upload("clip.mp4", data.size.toLong(), Target("all")) { ByteArrayInputStream(data) }
        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        val puts = seen.filter { it.method == "PUT" }
        assertEquals(listOf("/api/host/uploads/u1?offset=0", "/api/host/uploads/u1?offset=$chunk", "/api/host/uploads/u1?offset=${2 * chunk}"), puts.map { it.target })
        assertEquals(listOf(chunk, chunk, data.size - 2 * chunk), puts.map { it.body.size })
        assertTrue(puts[0].body.contentEquals(data.copyOfRange(0, chunk)))
        assertEquals("POST /api/host/uploads/u1/finish", seen.last().let { "${it.method} ${it.target}" })
        val init = json(seen.first())
        assertEquals("clip.mp4", init["name"])
        assertEquals(data.size.toLong(), init["size"])
        assertEquals("all", (init["to"] as Map<*, *>)["kind"])
    }

    @Test
    fun emptyFileIsInitThenFinish() {
        val api = start { r ->
            if (r.target == "/api/host/uploads") Response(200, """{"id":"u2","chunkSize":8388608}""") else Response(200, "{}")
        }
        assertTrue(api.upload("empty.txt", 0, Target("device", "d1")) { ByteArrayInputStream(ByteArray(0)) }.isSuccess)
        assertEquals(listOf("POST", "POST"), seen.map { it.method })
        assertEquals("d1", (json(seen.first())["to"] as Map<*, *>)["id"])
    }

    @Test
    fun trickyNamesSurviveJsonEncoding() {
        val api = start { r ->
            if (r.target == "/api/host/uploads") Response(200, """{"id":"u3","chunkSize":8388608}""") else Response(200, "{}")
        }
        val name = "🎉 \"shaadi\" \\ video.mp4"
        api.upload(name, 1, Target("all")) { ByteArrayInputStream(byteArrayOf(1)) }
        assertEquals(name, json(seen.first())["name"])
    }

    @Test
    fun failedInitIsAFailureAndNothingElseIsSent() {
        val api = start { Response(400, """{"error":"no recipients are connected"}""") }
        val result = api.upload("a.bin", 3, Target("all")) { ByteArrayInputStream(ByteArray(3)) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no recipients are connected"))
        assertEquals(1, seen.size)
    }

    @Test
    fun failedChunkAbortsTheUpload() {
        val api = start { r ->
            when {
                r.target == "/api/host/uploads" -> Response(200, """{"id":"u4","chunkSize":2}""")
                r.method == "PUT" -> Response(500, """{"error":"disk full"}""")
                else -> Response(200, "{}")
            }
        }
        val result = api.upload("a.bin", 4, Target("all")) { ByteArrayInputStream(ByteArray(4)) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("disk full"))
        assertEquals("DELETE", seen.last().method) // best-effort abort
    }

    @Test
    fun eventsDeliversEachEventAndIgnoresComments() {
        val api = start {
            val body = ": connected\n\nevent: received\ndata: {\"id\":\"a\"}\n\n: ping\n\nevent: item\ndata: {\"id\":\"b\"}\n\n"
            Response(200, body, "text/event-stream")
        }
        val got = mutableListOf<Pair<String, String>>()
        api.events { type, data -> got += type to data }
        assertEquals(listOf("received" to """{"id":"a"}""", "item" to """{"id":"b"}"""), got)
        assertEquals("/api/host/events", seen.single().target)
        assertEquals("jp_host=TOKEN", seen.single().headers["cookie"])
    }

    @Test
    fun summaryCountsConnectedDevicesAndReceivedItems() {
        val api = start {
            Response(
                200,
                """{"devices":[{"state":"approved"},{"state":"pending"},{"state":"approved"},{"state":"blocked"}],""" +
                    """"items":[{"from":"host"},{"from":"d1"},{"from":"d2"},{"from":"d1"}]}""",
            )
        }
        assertEquals(HostApi.Summary(connected = 2, received = 3), api.summary())
        assertEquals("/api/host/state", seen.single().target)
    }

    @Test
    fun summaryIsNullWhenTheServerRefusesOrIsUnreachable() {
        val api = start { Response(403, """{"error":"host only"}""") }
        assertEquals(null, api.summary())
        fake!!.close()
        assertEquals(null, api.summary())
    }

    @Test
    fun sendTextPostsTheTextAndTarget() {
        val api = start()
        assertTrue(api.sendText("hello \"world\" 🎉", Target("device", "d1")).isSuccess)
        val r = seen.single()
        assertEquals("POST /api/host/send", "${r.method} ${r.target}")
        assertEquals("hello \"world\" 🎉", json(r)["text"])
        assertEquals("d1", (json(r)["to"] as Map<*, *>)["id"])
        assertEquals("device", (json(r)["to"] as Map<*, *>)["kind"])
    }

    @Test
    fun sendTextFailureCarriesTheServersMessage() {
        val api = start { Response(400, """{"error":"no recipients are connected"}""") }
        val result = api.sendText("hi", Target("all"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no recipients are connected"))
    }

    @Test
    fun recipientsAreEveryoneThenApprovedDevicesThenGroups() {
        val api = start {
            Response(
                200,
                """{"devices":[{"id":"d1","name":"Ali","state":"approved"},{"id":"d2","name":"Pending","state":"pending"},{"id":"d3","name":"Sara","state":"approved"}],""" +
                    """"groups":[{"id":"g1","name":"Family"}]}""",
            )
        }
        assertEquals(
            listOf(
                HostApi.Recipient(Target("all"), "Everyone"),
                HostApi.Recipient(Target("device", "d1"), "Ali"),
                HostApi.Recipient(Target("device", "d3"), "Sara"),
                HostApi.Recipient(Target("group", "g1"), "Family"),
            ),
            api.recipients(),
        )
    }

    @Test
    fun recipientsAreNullWhenTheServerRefuses() {
        val api = start { Response(403, """{"error":"host only"}""") }
        assertEquals(null, api.recipients())
    }

    @Test
    fun disableApkPostsToTheDisableRoute() {
        val api = start()
        assertTrue(api.disableApk())
        val r = seen.single()
        assertEquals("POST /api/host/apk/disable", "${r.method} ${r.target}")
        assertEquals("jp_host=TOKEN", r.headers["cookie"])
    }

    @Test
    fun disableApkReturnsFalseWhenRefused() {
        val api = start { Response(403, """{"error":"host only"}""") }
        assertFalse(api.disableApk())
    }

    @Test
    fun transfersAreTheUploadsInProgress() {
        val api = start {
            Response(200, """{"transfers":[{"name":"clip.mp4","from":"Ali","size":100,"received":40},{"name":"b.bin","from":"Sara","size":8,"received":0}]}""")
        }
        assertEquals(
            listOf(HostApi.Transfer("clip.mp4", "Ali", 100, 40), HostApi.Transfer("b.bin", "Sara", 8, 0)),
            api.transfers(),
        )
        assertEquals("/api/host/transfers", seen.single().target)
    }

    @Test
    fun transfersAreNullWhenTheServerRefusesOrIsUnreachable() {
        val api = start { Response(403, """{"error":"host only"}""") }
        assertEquals(null, api.transfers())
        fake!!.close()
        assertEquals(null, api.transfers())
    }
}
