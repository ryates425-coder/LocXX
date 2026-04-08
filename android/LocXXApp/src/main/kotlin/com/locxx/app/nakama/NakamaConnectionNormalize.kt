package com.locxx.app.nakama

/**
 * [com.heroiclabs.nakama.DefaultClient] expects a bare hostname (no scheme, no path).
 * `local.properties` often uses a full URL copied from Azure; normalize that here.
 */
data class NakamaConnectionParams(
    val host: String,
    val port: Int,
    val useSsl: Boolean,
)

internal fun normalizeNakamaConnection(hostRaw: String, port: Int, useSsl: Boolean): NakamaConnectionParams {
    var h = hostRaw.trim()
    var ssl = useSsl
    when {
        h.startsWith("https://", ignoreCase = true) -> {
            ssl = true
            h = h.substring(8)
        }
        h.startsWith("http://", ignoreCase = true) -> {
            ssl = false
            h = h.substring(7)
        }
    }
    val slash = h.indexOf('/')
    if (slash >= 0) h = h.substring(0, slash)
    h = h.trim().trimEnd('.')

    var p = port
    if (h.startsWith("[")) {
        val close = h.indexOf(']')
        if (close > 0 && close + 1 < h.length && h[close + 1] == ':') {
            val tail = h.substring(close + 2)
            if (tail.isNotEmpty() && tail.all { it.isDigit() }) {
                p = tail.toInt()
                h = h.substring(0, close + 1)
            }
        }
    } else if (h.count { it == ':' } == 1) {
        val colon = h.lastIndexOf(':')
        val tail = h.substring(colon + 1)
        if (tail.isNotEmpty() && tail.all { it.isDigit() }) {
            p = tail.toInt()
            h = h.substring(0, colon)
        }
    }

    return NakamaConnectionParams(host = h, port = p, useSsl = ssl)
}
