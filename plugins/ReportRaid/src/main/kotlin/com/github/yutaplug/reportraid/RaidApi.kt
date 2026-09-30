package com.github.yutaplug.reportraid

import com.aliucord.Http
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject

internal object RaidApi {
    fun submit(guildId: Long, behaviors: List<String>, authToken: String, analyticsToken: String) {
        check(RestAPI.AppHeadersProvider.INSTANCE.authToken == authToken) {
            "Discord account changed. Reopen Report Raid."
        }
        check(!Thread.currentThread().isInterrupted) { "Request cancelled." }
        require(behaviors.isNotEmpty() && behaviors.all { it in ReportRaidPage.BEHAVIORS })
        // Discord's five-choice menu submits GUILD_RAID_REPORTED through /science.
        // Send this explicit report separately because Aliucord NoTrack disables
        // AnalyticsUtils.Tracker. See save/scripts/96390.js in
        // https://github.com/discordexperimenthub/datamining.
        val properties = JSONObject().put("guild_id", guildId.toString())
            .put("raid_types", JSONArray(behaviors))
        val event = JSONObject().put("type", "guild_raid_reported").put("properties", properties)
        val body = JSONObject().put("token", analyticsToken).put("events", JSONArray().put(event))
        Http.Request.newDiscordRequest("/science", "POST").use { request ->
            request.setRequestTimeout(15_000)
            request.setHeader("Authorization", authToken)
            request.setHeader("Content-Type", "application/json")
            request.executeWithBody(body.toString()).use { response ->
                if (!response.ok()) {
                    val error = runCatching {
                        request.conn.errorStream?.bufferedReader()?.use { reader ->
                            JSONObject(reader.readText().take(16_384))
                        }
                    }.getOrNull()
                    val message = when (response.statusCode) {
                        401 -> "Your Discord session expired. Sign in again."
                        403 -> "Discord denied the raid report."
                        429 -> "Too many requests. Wait before trying again."
                        else -> error?.optString("message")?.takeIf(::hasText)
                            ?: "Discord returned HTTP ${response.statusCode}."
                    }
                    throw IllegalStateException(message)
                }
            }
        }
    }
}

// Avoid Kotlin's isBlank: its range iterator is incompatible with Discord's
// bundled, obfuscated Kotlin runtime. Index characters without a range iterator.
internal fun hasText(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return true
    }
    return false
}
