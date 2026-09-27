@file:Suppress("ktlint:standard:property-naming")

package com.aliucord.plugins

import com.discord.models.domain.NonceGenerator
import com.discord.utilities.time.ClockFactory

internal class AttachmentBody(
    filename: String,
    size: Long,
) {
    val files = listOf(File(filename, size))

    class File(
        val filename: String,
        val file_size: Long,
        val id: Int = 0,
    )
}

internal class VoiceMessageBody(
    val channel_id: Long,
    attachment: Attachment,
    val message_reference: MessageReference?,
    mentionReply: Boolean,
) {
    val content = ""
    val type = 0
    val flags = 8192
    val nonce = NonceGenerator.computeNonce(ClockFactory.get()).toString()
    val attachments = listOf(attachment)

    val allowed_mentions = AllowedMentions(mentionReply)

    class AllowedMentions(
        val replied_user: Boolean,
    ) {
        val parse = emptyList<String>()
    }

    data class MessageReference(
        val message_id: String,
        val channel_id: Long,
        val guild_id: Long?,
    )

    class Attachment(
        val filename: String,
        val uploaded_filename: String,
        val duration_secs: Float,
        val waveform: String,
        val id: String = "0",
    )
}

