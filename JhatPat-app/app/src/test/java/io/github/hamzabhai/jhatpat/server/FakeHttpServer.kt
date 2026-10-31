package io.github.hamzabhai.jhatpat.server

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A minimal HTTP/1.1 server for tests (the JDK's own server is not on the Android unit-test
 * compile path). One thread per connection, `Connection: close`, request bodies must carry a
 * Content-Length.
 */
class FakeHttpServer(private val handler: (Request) -> Response) : AutoCloseable {
    class Request(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray)

    class Response(val code: Int, val body: String = "", val type: String = "application/json")

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    init {
        Thread({
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                Thread({ serve(client) }, "fake-http-conn").apply { isDaemon = true }.start()
            }
        }, "fake-http-accept").apply { isDaemon = true }.start()
    }

    private fun serve(client: Socket) {
        client.use {
            val input = client.getInputStream()
            val request = readRequest(input) ?: return
            val response = handler(request)
            val body = response.body.toByteArray()
            val head = "HTTP/1.1 ${response.code} X\r\nContent-Type: ${response.type}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            client.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val (method, target) = line.split(" ").let { it[0] to it[1] }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = readLine(input) ?: return null
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return Request(method, target, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            out.write(b)
        }
    }

    override fun close() {
        socket.close()
    }
}
