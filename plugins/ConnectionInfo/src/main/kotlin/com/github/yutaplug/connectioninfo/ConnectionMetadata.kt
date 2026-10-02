package com.github.yutaplug.connectioninfo

import org.json.JSONObject
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

internal object ConnectionMetadata {
    private val aliases = Pattern.compile("\\|")
    private val memberTimestamp = Pattern.compile("\\d{4}-\\d{2}-\\d{2}(?:T.*)?")

    fun lines(connection: JSONObject): List<String> {
        if (connection.optBoolean("revoked") || connection.optInt("metadata_visibility", 1) != 1) return emptyList()
        val metadata = connection.optJSONObject("metadata") ?: return emptyList()
        val lines = mutableListOf<String>()
        memberDate(metadata)?.let { lines.add("Member since $it") }
        val fields = when (connection.optString("type")) {
            "steam" -> listOf("game_count|games_count|games" to "Games", "item_count|items_count|items" to "Items")

            "reddit" -> listOf("total_karma" to "Karma")

            "twitter" -> listOf(
                "statuses_count|tweet_count" to "Posts",
                "followers_count|follower_count" to "Followers",
            )

            "tiktok" -> listOf(
                "follower_count" to "Followers",
                "following_count" to "Following",
                "likes_count" to "Likes",
            )

            "youtube" -> listOf("subscriber_count" to "Subscribers", "video_count" to "Videos")

            "github" -> listOf("public_repos" to "Repositories", "followers" to "Followers")

            "twitch" -> listOf("followers_count" to "Followers")

            else -> emptyList()
        }
        val counts = fields.mapNotNull { (key, label) ->
            val value = aliases.split(key).firstNotNullOfOrNull { readCount(metadata, it) }
                ?: return@mapNotNull null
            if (value < 0 && key != "total_karma") return@mapNotNull null
            "${NumberFormat.getIntegerInstance().format(value)} $label"
        }
        if (counts.isNotEmpty()) lines.add(counts.joinToString("  "))
        return lines
    }

    private fun memberDate(metadata: JSONObject): String? {
        val value = metadata.optString("created_at")
        // Only the calendar date is displayed, avoiding a timezone-dependent day shift.
        // Use Java string APIs: Discord's obfuscated Kotlin iterators are incompatible with isBlank().
        if (!memberTimestamp.matcher(value).matches()) return null
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = try {
            parser.parse(value.substring(0, 10))
        } catch (_: Exception) {
            null
        } ?: return null
        return DateFormat
            .getDateInstance(DateFormat.MEDIUM)
            .apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(date)
    }

    private fun readCount(metadata: JSONObject, key: String): Long? = try {
        java.lang.Long.valueOf(metadata.optString(key))
    } catch (_: NumberFormatException) {
        null
    }
}
