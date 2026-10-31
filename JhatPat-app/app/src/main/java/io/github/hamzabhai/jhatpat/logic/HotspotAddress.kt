package io.github.hamzabhai.jhatpat.logic

/** Picks the address of the hotspot interface out of the phone's network interfaces. */
object HotspotAddress {
    /** Interface name matchers, most likely hotspot first. */
    private val preferred: List<(String) -> Boolean> = listOf(
        { name -> name.startsWith("ap") },
        { name -> name.startsWith("swlan") },
        { name -> name == "wlan1" },
    )

    /** [candidates] are (interface name, IPv4 text) pairs. Returns null when none is usable. */
    fun pick(candidates: List<Pair<String, String>>): String? {
        val usable = candidates.filter { isPrivateIpv4(it.second) }
        for (matches in preferred) {
            usable.firstOrNull { matches(it.first) }?.let { return it.second }
        }
        return usable.firstOrNull()?.second
    }

    internal fun isPrivateIpv4(text: String): Boolean {
        val parts = text.split(".")
        if (parts.size != 4) return false
        val n = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return false
            part.toInt().also { if (it > 255) return false }
        }
        return n[0] == 10 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] in 16..31)
    }
}
