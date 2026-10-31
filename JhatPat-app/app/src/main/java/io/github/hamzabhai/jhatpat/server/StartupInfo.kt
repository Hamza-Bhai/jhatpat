package io.github.hamzabhai.jhatpat.server

import io.github.hamzabhai.jhatpat.util.Json

/** What the Go server prints, as one JSON line, once it is listening (see web/internal/launch). */
data class StartupInfo(
    val port: Int,
    val hostToken: String,
    val pin: String,
    val secret: String,
    val ip: String,
    val hostUrl: String,
) {
    companion object {
        /** Throws [IllegalArgumentException] when [line] is not a complete startup line. */
        fun parse(line: String): StartupInfo {
            val map = Json.parse(line) as? Map<*, *> ?: throw IllegalArgumentException("startup line is not a JSON object")
            fun text(key: String): String = map[key] as? String ?: throw IllegalArgumentException("startup line has no text field \"$key\"")
            val port = map["port"] as? Long ?: throw IllegalArgumentException("startup line has no numeric field \"port\"")
            return StartupInfo(port.toInt(), text("hostToken"), text("pin"), text("secret"), text("ip"), text("hostUrl"))
        }
    }
}
