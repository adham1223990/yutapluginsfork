package com.github.yutaplug.newlinks

import java.net.URI
import java.util.Locale

/** Validate the origin before displaying a Discord URL as a compact label. */
internal fun parseDiscordUrl(url: String, hosts: Set<String>): URI? {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "https" && scheme != "http") return null
    if (uri.host?.lowercase(Locale.ROOT) !in hosts || uri.rawUserInfo != null) return null
    if (uri.port != -1 && uri.port != if (scheme == "https") 443 else 80) return null
    return uri
}

internal fun parseSnowflake(value: String): Long? = try {
    java.lang.Long.parseLong(value)
} catch (_: NumberFormatException) {
    null
}
