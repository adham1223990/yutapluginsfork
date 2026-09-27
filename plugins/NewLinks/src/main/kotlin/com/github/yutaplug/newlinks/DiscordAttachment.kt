package com.github.yutaplug.newlinks

import java.util.regex.Pattern

/** Attachment labels do not require the linked channel to be in the local store. */
internal data class DiscordAttachment(val filename: String) {
    companion object {
        private val hosts = setOf("cdn.discordapp.com", "media.discordapp.net")
        private val path = Pattern.compile("^/(?:attachments|ephemeral-attachments)/([1-9][0-9]{0,18})/([1-9][0-9]{0,18})/([^/]+)$")

        fun parse(url: String): DiscordAttachment? {
            val uri = parseDiscordUrl(url, hosts) ?: return null
            val raw = path.matcher(uri.rawPath ?: return null)
            if (!raw.matches()) return null
            parseSnowflake(raw.group(1) ?: return null) ?: return null
            parseSnowflake(raw.group(2) ?: return null) ?: return null

            // URI decodes UTF-8 escapes without treating a literal '+' as a space.
            // Match again so an encoded slash cannot masquerade as a filename.
            val decoded = path.matcher(uri.path ?: return null)
            if (!decoded.matches()) return null
            val filename = decoded.group(3) ?: return null
            if (filename == "." || filename == "..") return null
            var hasVisibleCharacter = false
            var index = 0
            while (index < filename.length) {
                val char = filename[index++]
                if (char == '\\' || char == '\uFFFD' || Character.isISOControl(char) ||
                    Character.getType(char) == Character.FORMAT.toInt()
                ) return null
                if (!Character.isWhitespace(char)) hasVisibleCharacter = true
            }
            if (!hasVisibleCharacter) return null
            return DiscordAttachment(filename)
        }
    }
}
