package com.github.yutaplug.onboarding

import com.aliucord.Http
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

internal data class PromptOption(
    val id: String,
    val title: String,
    val description: String,
    val emojiId: Long?,
    val emojiName: String,
    val emojiAnimated: Boolean,
    val channelIds: Set<Long>,
)

internal data class OnboardingPrompt(
    val id: String,
    val title: String,
    val type: Int,
    val singleSelect: Boolean,
    val required: Boolean,
    val inOnboarding: Boolean,
    val options: List<PromptOption>,
)

internal data class OnboardingConfig(
    val guildId: Long,
    val prompts: List<OnboardingPrompt>,
    val defaultChannelIds: Set<Long>,
    val responses: Set<String>,
    val enabled: Boolean = false,
    val belowRequirements: Boolean = false,
)

internal data class BrowseChannel(
    val id: Long,
    val name: String,
    val type: Int,
    val parentId: Long,
    val position: Int,
)

internal class OnboardingApi(private val expectedToken: String) {
    fun hasCompletedOnboarding(guildId: Long): Boolean {
        val member = request("/users/@me/guilds/$guildId/member")
        check(member.has("flags")) { "Discord did not return onboarding status" }
        return member.optInt("flags") and COMPLETED_ONBOARDING_FLAG != 0
    }

    fun getConfig(guildId: Long): OnboardingConfig? {
        val response = request("/guilds/$guildId/onboarding")
        val json = response.optJSONObject("onboarding") ?: response
        val prompts = mutableListOf<OnboardingPrompt>()
        json.optJSONArray("prompts")?.forEachObject { item ->
            val id = item.optString("id")
            if (!id.hasVisibleText()) return@forEachObject
            val options = mutableListOf<PromptOption>()
            item.optJSONArray("options")?.forEachObject { option ->
                val optionId = option.optString("id")
                if (!optionId.hasVisibleText()) return@forEachObject
                val emoji = option.optJSONObject("emoji")
                options += PromptOption(
                    optionId,
                    option.optString("title"),
                    option.optString("description"),
                    emoji?.optString("id")?.toSnowflakeOrNull(),
                    emoji?.optString("name").orEmpty(),
                    emoji?.optBoolean("animated", false) ?: false,
                    option.optJSONArray("channel_ids").snowflakes(),
                )
            }
            prompts += OnboardingPrompt(
                id,
                item.optString("title"),
                item.optInt("type", 0),
                item.optBoolean("single_select", false),
                item.optBoolean("required", false),
                item.optBoolean("in_onboarding", false),
                options,
            )
        }
        val defaults = json.optJSONArray("default_channel_ids").snowflakes()
        if (!json.optBoolean("enabled", false) && prompts.isEmpty() && defaults.isEmpty()) {
            return null
        }
        return OnboardingConfig(
            guildId,
            prompts,
            defaults,
            json.optJSONArray("responses").strings(),
            json.optBoolean("enabled", false),
            json.optBoolean("below_requirements", false),
        )
    }

    fun saveResponses(config: OnboardingConfig, selected: Set<String>, initial: Boolean) {
        val validOptions = config.prompts.flatMap { prompt -> prompt.options.map { it.id } }.toSet()
        require(selected.all { it in validOptions }) { "Invalid onboarding option" }
        require(selected.size <= 750) { "Too many onboarding choices" }
        val now = System.currentTimeMillis()
        val body = JSONObject().put("onboarding_responses", JSONArray(selected.toList()))
        if (initial) {
            val seenPrompts = JSONObject()
            val seenResponses = JSONObject()
            config.prompts.filter { it.inOnboarding }.forEach { seenPrompts.put(it.id, now) }
            selected.forEach { seenResponses.put(it, now) }
            body.put("onboarding_prompts_seen", seenPrompts)
            body.put("onboarding_responses_seen", seenResponses)
        }
        val route = "/guilds/${config.guildId}/onboarding-responses"
        try {
            request(route, if (initial) "POST" else "PUT", body)
        } catch (error: OnboardingHttpError) {
            if (error.status == 404 || error.status == 409 || (initial && error.status == 403)) {
                try {
                    request(route, if (initial) "PUT" else "POST", body)
                } catch (retryError: OnboardingHttpError) {
                    retryError.addSuppressed(error)
                    throw retryError
                }
            } else throw error
        }
    }

    fun getChannels(guildId: Long): List<BrowseChannel> {
        val response = requestArray("/guilds/$guildId/channels?permissions=true")
        val localPermissions = StoreStream.getPermissions().permissionsByChannel
        val result = mutableListOf<BrowseChannel>()
        response.forEachObject { channel ->
            val id = channel.optString("id").toSnowflakeOrNull() ?: return@forEachObject
            val type = channel.optInt("type", -1)
            if (type !in BROWSABLE_TYPES && type != CATEGORY_TYPE) return@forEachObject
            val remotePermissions = channel.optString("permissions")
                .takeIf(String::hasVisibleText)
                ?.let { runCatching { BigInteger(it).testBit(10) }.getOrNull() }
            val canView = remotePermissions
                ?: localPermissions[id]?.let { it and VIEW_CHANNEL_PERMISSION != 0L }
            if (canView != true) return@forEachObject
            result += BrowseChannel(
                id,
                channel.optString("name"),
                type,
                channel.optString("parent_id").toSnowflakeOrNull() ?: 0L,
                channel.optInt("position", 0),
            )
        }
        return result.sortedWith(compareBy(BrowseChannel::position, BrowseChannel::name))
    }

    fun getChannelFlags(guildId: Long, channelId: Long): Int =
        StoreStream.getUserGuildSettings().guildSettings[guildId]
            ?.getChannelOverride(channelId)?.flags ?: 0

    fun setChannelOptIn(guildId: Long, channelId: Long, oldFlags: Int, enabled: Boolean): Int {
        return setChannelOptIns(guildId, mapOf(channelId to oldFlags), enabled).getValue(channelId)
    }

    fun setChannelOptIns(guildId: Long, oldFlags: Map<Long, Int>, enabled: Boolean): Map<Long, Int> {
        val updated = oldFlags.mapValues { (_, flags) ->
            if (enabled) flags or OPTED_IN_FLAG else flags and OPTED_IN_FLAG.inv()
        }
        val overrides = JSONObject()
        updated.forEach { (channelId, flags) ->
            overrides.put(channelId.toString(), JSONObject().put("flags", flags))
        }
        val body = JSONObject().put("channel_overrides", overrides)
        request("/users/@me/guilds/$guildId/settings", "PATCH", body)
        return updated
    }

    private fun requestArray(route: String): JSONArray = JSONArray(requestText(route, "GET", null))

    private fun request(route: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val text = requestText(route, method, body)
        return if (text.hasVisibleText()) JSONObject(text) else JSONObject()
    }

    private fun requestText(route: String, method: String, body: JSONObject?): String {
        check(currentToken() == expectedToken) { "Discord account changed. Reopen Channels & Roles." }
        return Http.Request.newDiscordRequest(route, method).use { request ->
            request.setRequestTimeout(15_000)
            request.setHeader("Authorization", expectedToken)
            request.setHeader("Content-Type", "application/json")
            val response = if (body == null) request.execute() else request.executeWithBody(body.toString())
            response.use {
                if (!it.ok()) {
                    val errorBody = runCatching {
                        request.conn.errorStream?.bufferedReader()?.use { reader ->
                            reader.readText().take(8_192)
                        }
                    }.getOrNull()
                    val errorJson = runCatching { JSONObject(errorBody.orEmpty()) }.getOrNull()
                    throw OnboardingHttpError(
                        it.statusCode,
                        method,
                        route,
                        errorJson?.optString("message").orEmpty(),
                        errorJson?.optInt("code", 0) ?: 0,
                    )
                }
                it.text()
            }
        }
    }

    companion object {
        private const val COMPLETED_ONBOARDING_FLAG = 1 shl 1
        const val OPTED_IN_FLAG = 1 shl 12
        private const val VIEW_CHANNEL_PERMISSION = 1L shl 10
        private const val CATEGORY_TYPE = 4
        private val BROWSABLE_TYPES = setOf(0, 2, 5, 13, 15, 16)

        fun currentToken(): String =
            StoreAuthentication.`access$getAuthState$p`(StoreStream.getAuthentication())?.token
                ?.takeIf(String::hasVisibleText)
                ?: RestAPI.AppHeadersProvider.INSTANCE.authToken?.takeIf(String::hasVisibleText)
                ?: error("Sign in to Discord to use Channels & Roles")
    }
}

internal class OnboardingHttpError(
    val status: Int,
    val method: String,
    val route: String,
    val discordMessage: String,
    val code: Int,
) : Exception("Discord $method $route returned HTTP $status" +
    if (discordMessage.hasVisibleText()) ": $discordMessage" else "")

internal fun String.hasVisibleText(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index++]
        if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return true
    }
    return false
}

private fun String.toSnowflakeOrNull(): Long? =
    try { java.lang.Long.parseLong(this) } catch (_: NumberFormatException) { null }

private inline fun JSONArray?.forEachObject(action: (JSONObject) -> Unit) {
    if (this == null) return
    var index = 0
    while (index < length()) {
        optJSONObject(index)?.let(action)
        index++
    }
}

private fun JSONArray?.snowflakes(): Set<Long> {
    if (this == null) return emptySet()
    val ids = mutableSetOf<Long>()
    var index = 0
    while (index < length()) {
        optString(index).toSnowflakeOrNull()?.let(ids::add)
        index++
    }
    return ids
}

private fun JSONArray?.strings(): Set<String> {
    if (this == null) return emptySet()
    val values = mutableSetOf<String>()
    var index = 0
    while (index < length()) {
        optString(index).takeIf(String::hasVisibleText)?.let(values::add)
        index++
    }
    return values
}
