package io.github.hamzabhai.jhatpat.media

/** Recognises the URLs the host page uses to show or download a chat item. */
object HostItemUrl {
    enum class Kind { View, Download }

    data class Match(val id: String, val kind: Kind)

    private val pattern = Regex("^/api/host/items/([A-Za-z0-9_-]+)/(view|download)$")

    /** [path] is the URL path without the query. Ids may only hold letters, digits, `-` and `_`. */
    fun parse(path: String): Match? {
        val m = pattern.matchEntire(path) ?: return null
        val kind = if (m.groupValues[2] == "view") Kind.View else Kind.Download
        return Match(m.groupValues[1], kind)
    }
}
