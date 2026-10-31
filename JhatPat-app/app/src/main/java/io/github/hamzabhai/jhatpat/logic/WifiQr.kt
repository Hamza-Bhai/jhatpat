package io.github.hamzabhai.jhatpat.logic

/** Builds the text of a QR code that makes a phone's camera join a WiFi network. */
object WifiQr {
    private const val SPECIAL = "\\;,:\""

    fun build(ssid: String, password: String): String = "WIFI:T:WPA;S:${escape(ssid)};P:${escape(password)};;"

    private fun escape(value: String): String = buildString {
        for (c in value) {
            if (c in SPECIAL) append('\\')
            append(c)
        }
    }
}
