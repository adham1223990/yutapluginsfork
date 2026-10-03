package com.github.yutaplug.imagedescriptions

import org.json.JSONArray
import org.json.JSONObject

/** Works on wire JSON, independent of the legacy client's missing model fields. */
internal object AttachmentMetadata {
    fun description(json: String): String? {
        val attachment = JSONObject(json)
        return if (attachment.isNull("description")) null else attachment.optString("description")
    }

    fun withDescription(json: String, text: String): String = JSONObject(json).put("description", text).toString()

    fun withUploads(json: String, filenames: Array<String?>, descriptions: Array<String?>): String {
        require(filenames.size == descriptions.size) { "Upload metadata mismatch" }
        val message = JSONObject(json)
        val attachments = JSONArray()
        var index = 0
        while (index < filenames.size) {
            val attachment = JSONObject().put("id", index).put("filename", filenames[index])
            if (DescriptionText.hasText(descriptions[index])) attachment.put("description", descriptions[index])
            attachments.put(attachment)
            index++
        }
        return message.put("attachments", attachments).toString()
    }

    fun withCloudUploads(json: String, descriptions: List<String?>): String {
        val message = JSONObject(json)
        val attachments = message.getJSONArray("attachments")
        require(attachments.length() == descriptions.size) { "Cloud upload metadata mismatch" }
        var index = 0
        while (index < attachments.length()) {
            val text = descriptions[index]
            if (DescriptionText.hasText(text)) attachments.getJSONObject(index).put("description", text)
            index++
        }
        return message.toString()
    }
}
