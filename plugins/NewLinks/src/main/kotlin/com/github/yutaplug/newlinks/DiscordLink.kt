package com.github.yutaplug.newlinks

import java.util.regex.Pattern

/** Only canonical Discord destinations may have their URL hidden behind a label. */
internal data class DiscordLink(val guildId: Long?, val channelId: Long, val messageId: Long?) {
    companion object {
        private val hosts = setOf(
            "discord.com", "discordapp.com",
            "www.discord.com", "www.discordapp.com",
            "canary.discord.com", "canary.discordapp.com",
            "ptb.discord.com", "ptb.discordapp.com",
        )
        private val path = Pattern.compile("^/channels/(@me|[1-9][0-9]{0,18})/([1-9][0-9]{0,18})(?:/([1-9][0-9]{0,18}))?/?$")

        fun parse(url: String): DiscordLink? {
            val uri = parseDiscordUrl(url, hosts) ?: return null
            val match = path.matcher(uri.rawPath ?: return null)
            if (!match.matches()) return null
            val guild = match.group(1) ?: return null
            val guildId = if (guild == "@me") null else parseSnowflake(guild) ?: return null
            val channelId = parseSnowflake(match.group(2) ?: return null) ?: return null
            val message = match.group(3)
            val messageId = if (message == null) null else parseSnowflake(message) ?: return null
            return DiscordLink(guildId, channelId, messageId)
        }
    }
}
