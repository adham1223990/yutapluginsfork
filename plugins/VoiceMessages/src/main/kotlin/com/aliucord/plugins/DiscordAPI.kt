package com.aliucord.plugins

import com.aliucord.Http
import com.aliucord.utils.GsonUtils
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException

internal object DiscordAPI {
    fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Voice message cancelled")
    }

    fun uploadFile(
        file: File,
        channel: Long,
        mime: String,
        checkActive: () -> Unit = ::checkCancelled,
    ): String {
        require(file.isFile && file.length() > 0) { "Cannot upload an empty audio file" }
        checkActive()
        val attachment =
            Http.Request.newDiscordRNRequest("/channels/$channel/attachments", "POST").use { request ->
                request.setRequestTimeout(30_000).setHeader("Content-Type", "application/json")
                request.executeWithBody(GsonUtils.toJson(AttachmentBody(file.name, file.length()))).use { response ->
                    response.assertOk()
                    JSONObject(response.text()).getJSONArray("attachments").getJSONObject(0)
                }
            }
        checkActive()
        // Stream to the signed URL without Discord authentication or buffering the entire file.
        Http.Request(attachment.getString("upload_url"), "PUT").use { request ->
            request.setRequestTimeout(60_000).setHeader("Content-Type", mime)
            request.conn.apply {
                doOutput = true
                setFixedLengthStreamingMode(file.length())
            }
            request.conn.outputStream.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            request.execute().use { it.assertOk() }
        }
        return attachment.getString("upload_filename")
    }

    fun sendVoiceMessage(
        uploadedFilename: String,
        duration: Float,
        waveform: String,
        channel: Long,
        extension: String,
        reply: VoiceMessageBody.MessageReference?,
        mentionReply: Boolean,
        checkActive: () -> Unit = ::checkCancelled,
    ) {
        checkActive()
        require(duration.isFinite() && duration > 0) { "Invalid audio duration" }
        val body =
            VoiceMessageBody(
                channel,
                VoiceMessageBody.Attachment(
                    "voice-message$extension",
                    uploadedFilename,
                    duration,
                    waveform,
                ),
                reply,
                mentionReply,
            )
        Http.Request.newDiscordRNRequest("/channels/$channel/messages", "POST").use { request ->
            request.setRequestTimeout(30_000).setHeader("Content-Type", "application/json")
            request.executeWithBody(GsonUtils.toJson(body)).use { it.assertOk() }
        }
    }
}
