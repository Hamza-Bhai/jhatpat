package io.github.hamzabhai.jhatpat.server

import io.github.hamzabhai.jhatpat.util.Json
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Who an upload is for: kind is "host", "device", "group" or "all" (see the server's Target). */
data class Target(val kind: String, val id: String = "")

/**
 * The host API of the local JhatPat server, reached over loopback. Every request carries the
 * per-run host cookie.
 */
class HostApi(private val base: String, private val token: String) {
    private class Reply(val code: Int, val body: String) {
        val ok get() = code in 200..299

        fun errorMessage(): String =
            (runCatching { Json.parse(body) }.getOrNull() as? Map<*, *>)?.get("error") as? String ?: "HTTP $code"
    }

    /** How many devices are connected and how many files or folders others have sent to the host. */
    data class Summary(val connected: Int, val received: Int)

    /** Reads the host state for the notification. Null when the server cannot be reached or refuses. */
    fun summary(): Summary? = try {
        val reply = request("GET", "/api/host/state", null, 0)
        if (!reply.ok) {
            null
        } else {
            val state = Json.parse(reply.body) as? Map<*, *>
            val devices = state?.get("devices") as? List<*> ?: emptyList<Any?>()
            val items = state?.get("items") as? List<*> ?: emptyList<Any?>()
            Summary(
                connected = devices.count { (it as? Map<*, *>)?.get("state") == "approved" },
                received = items.count {
                    val item = it as? Map<*, *>
                    item?.get("from") != "host" && item?.get("kind") != "text"
                },
            )
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun enableApk(minutes: Int): Boolean = post("/api/host/apk/enable", """{"minutes":$minutes}""")

    fun setIp(ip: String): Boolean = post("/api/host/ip", """{"ip":${Json.quote(ip)}}""")

    fun markMoved(id: String, where: String): Boolean =
        post("/api/host/items/${segment(id)}/moved", """{"where":${Json.quote(where)}}""")

    /**
     * Sends one file to [to] through the chunked, resumable upload API. [open] is called once to
     * read the data. A failed upload is aborted on the server (best effort).
     */
    fun upload(name: String, size: Long, to: Target, open: () -> InputStream): Result<Unit> = runCatching {
        val initBody = """{"name":${Json.quote(name)},"size":$size,"to":{"kind":${Json.quote(to.kind)},"id":${Json.quote(to.id)}}}"""
        val initBytes = initBody.toByteArray()
        val init = request("POST", "/api/host/uploads", initBytes, initBytes.size)
        if (!init.ok) throw IOException(init.errorMessage())
        val reply = Json.parse(init.body) as Map<*, *>
        val id = reply["id"] as String
        val chunkSize = (reply["chunkSize"] as Long).toInt()
        val path = "/api/host/uploads/${segment(id)}"
        try {
            open().use { input ->
                val buffer = ByteArray(chunkSize)
                var offset = 0L
                while (true) {
                    val n = readFully(input, buffer)
                    if (n <= 0) break
                    val put = request("PUT", "$path?offset=$offset", buffer, n, "application/octet-stream")
                    if (!put.ok) throw IOException(put.errorMessage())
                    offset += n
                    if (n < chunkSize) break
                }
            }
            val finish = request("POST", "$path/finish", ByteArray(0), 0)
            if (!finish.ok) throw IOException(finish.errorMessage())
        } catch (e: Exception) {
            runCatching { request("DELETE", path, null, 0) }
            throw e
        }
    }

    /**
     * Reads the host's server-sent events and blocks until the stream ends (or the connection
     * fails), calling [onEvent] with each event's type and raw JSON data. Callers reconnect.
     */
    fun events(onEvent: (type: String, data: String) -> Unit) {
        val conn = open("GET", "/api/host/events")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.readTimeout = 0
        try {
            if (conn.responseCode !in 200..299) return
            var type: String? = null
            var data = StringBuilder()
            conn.inputStream.bufferedReader().forEachLine { line ->
                when {
                    line.isEmpty() -> {
                        if (type != null) onEvent(type!!, data.toString())
                        type = null
                        data = StringBuilder()
                    }
                    line.startsWith(":") -> Unit
                    line.startsWith("event:") -> type = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                }
            }
        } catch (_: IOException) {
            // the connection dropped; the caller will reconnect
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, json: String): Boolean = try {
        val bytes = json.toByteArray()
        request("POST", path, bytes, bytes.size).ok
    } catch (_: IOException) {
        false
    }

    private fun request(method: String, path: String, body: ByteArray?, length: Int, contentType: String = "application/json"): Reply {
        val conn = open(method, path)
        try {
            if (body != null && (method == "POST" || method == "PUT")) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(length)
                conn.outputStream.use { it.write(body, 0, length) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return Reply(code, stream?.bufferedReader()?.readText().orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, path: String): HttpURLConnection {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Cookie", "jp_host=$token")
        return conn
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
