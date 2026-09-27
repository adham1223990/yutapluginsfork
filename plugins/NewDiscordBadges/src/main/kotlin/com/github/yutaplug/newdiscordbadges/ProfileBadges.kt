package com.github.yutaplug.newdiscordbadges

import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.regex.Pattern

internal data class ProfileBadge(val id: String, val description: String, val images: List<String>)

/** Only the profile's visible badges are authoritative; never traverse the owned badge catalog. */
internal object ProfileBadges {
    const val MARKER = "newdiscordbadges:"
    private const val CDN = "https://cdn.discordapp.com"
    private val iconHash = Pattern.compile("[a-fA-F0-9]{32}(\\.png)?")

    fun parse(root: Any?): List<ProfileBadge> {
        val badges = (root as? Map<*, *>)?.get("badges") ?: return Collections.emptyList()
        val result = ArrayList<ProfileBadge>()
        val seen = HashSet<String>()
        when (badges) {
            is List<*> -> for (element in badges) append(element, "", result, seen)
            is Map<*, *> -> for (entry in badges.entries) append(entry.value, entry.key.toString(), result, seen)
        }
        return result
    }

    private fun append(element: Any?, key: String, result: MutableList<ProfileBadge>, seen: MutableSet<String>) {
        val badge = element as? Map<*, *> ?: return
        if (badge["hidden"] == true || badge["visible"] == false) return
        var id = string(badge, "id")
        if (id.length == 0) id = key
        if (id.length == 0 || seen.contains(id)) return
        val images = ArrayList<String>()
        for (field in arrayOf("simple_icon_url", "simpleIconUrl", "icon", "complex_icon_static_url")) {
            val url = imageUrl(string(badge, field)) ?: continue
            if (!images.contains(url)) images.add(url)
        }
        if (images.isEmpty()) return
        var description = string(badge, "description")
        if (description.length == 0) description = id
        seen.add(id)
        result.add(ProfileBadge(id, description, images))
    }

    private fun string(badge: Map<*, *>, field: String): String {
        val value = badge[field] as? String ?: return ""
        // Discord's obfuscated primitive iterators are incompatible with some
        // unmapped Kotlin text helpers. Use indexed characters, never isBlank/ranges.
        var start = 0
        var end = value.length
        while (start < end && whitespace(value[start])) start++
        while (end > start && whitespace(value[end - 1])) end--
        return value.substring(start, end)
    }

    private fun whitespace(char: Char) = Character.isWhitespace(char) || Character.isSpaceChar(char)

    fun hasPrefix(value: String, prefix: String): Boolean {
        if (value.length < prefix.length) return false
        var index = 0
        while (index < prefix.length) {
            if (value[index] != prefix[index]) return false
            index++
        }
        return true
    }

    fun imageUrl(value: String): String? = when {
        value.length == 0 -> null
        hasPrefix(value, "https://") -> value
        hasPrefix(value, "//") -> "https:$value"
        hasPrefix(value, "/") -> CDN + value
        hasPrefix(value, "badge-icons/") -> "$CDN/$value"
        iconHash.matcher(value).matches() ->
            "$CDN/badge-icons/$value" + if (value.length == 36) "" else ".png"
        else -> null
    }
}
