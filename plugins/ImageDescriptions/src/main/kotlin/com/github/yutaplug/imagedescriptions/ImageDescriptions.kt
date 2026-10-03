package com.github.yutaplug.imagedescriptions

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.entities.RNMessage
import com.aliucord.fragments.InputDialog
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.restapi.PayloadJSON
import com.discord.restapi.RestAPIParams
import com.discord.utilities.attachments.AttachmentUtilsKt
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.SendUtils
import com.discord.widgets.chat.input.attachments.AttachmentBottomSheet
import com.discord.widgets.chat.list.InlineMediaView
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemAttachment
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.lytefast.flexinput.model.Attachment
import de.robv.android.xposed.XposedBridge
import okhttp3.MultipartBody
import okhttp3.RequestBody
import rx.Observable
import java.io.StringReader
import java.io.StringWriter
import java.lang.ref.WeakReference

@AliucordPlugin
class ImageDescriptions : Plugin() {
    private val descriptions = WeakIdentityMap<MessageAttachment, String>()
    private val drafts = DescriptionDrafts()
    private val uploadDescriptions = WeakIdentityMap<SendUtils.FileUpload, String>()
    private val messageUploads = WeakIdentityMap<RestAPIParams.Message, List<SendUtils.FileUpload>>()
    private val jsonGson = Gson()
    private val badgeId = View.generateViewId()
    private val badges = mutableListOf<WeakReference<TextView>>()
    private val menus = mutableListOf<MenuState>()
    private val dialogs = mutableListOf<WeakReference<com.discord.app.AppDialog>>()
    private var active = false

    private data class MenuState(
        val edit: WeakReference<View>,
        val remove: WeakReference<View>,
        val previous: Int,
    )

    override fun start(context: Context) {
        active = true
        try {
            patchJson()
            patchUploads()
            patchMenu()
            patchImages()
        } catch (error: Throwable) {
            stop(context)
            throw error
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fields(adapter: Any) = ReflectUtils.getField(adapter, "b") as
        MutableMap<String, ReflectiveTypeAdapterFactory.a>

    private fun patchJson() {
        patcher.patch(
            ReflectiveTypeAdapterFactory.Adapter::class.java,
            "read",
            arrayOf(JsonReader::class.java),
            PreHook { call ->
                val fields = fields(call.thisObject)
                if (!fields.containsKey("filename") || !fields.containsKey("url")) return@PreHook
                // Capture the wire object before the legacy model discards description.
                val tree = jsonGson.d<JsonElement>(call.args[0] as JsonReader, JsonElement::class.java)
                val json = tree.toString()
                val value = XposedBridge.invokeOriginalMethod(
                    call.method,
                    call.thisObject,
                    arrayOf(JsonReader(StringReader(json))),
                )
                if (value is MessageAttachment) {
                    val text = AttachmentMetadata.description(json)
                    if (DescriptionText.hasText(text)) descriptions.put(value, text!!)
                }
                call.result = value
            },
        )
        patcher.patch(
            ReflectiveTypeAdapterFactory.Adapter::class.java,
            "write",
            arrayOf(JsonWriter::class.java, Any::class.java),
            PreHook { call ->
                val attachment = call.args[1] as? MessageAttachment ?: return@PreHook
                val text = descriptions[attachment] ?: return@PreHook
                // Preserve metadata in the client's disk cache as well as live messages.
                val buffer = StringWriter()
                val writer = JsonWriter(buffer)
                XposedBridge.invokeOriginalMethod(call.method, call.thisObject, arrayOf(writer, attachment))
                writer.flush()
                val json = AttachmentMetadata.withDescription(buffer.toString(), text)
                jsonGson.n(jsonGson.f(json, JsonElement::class.java), call.args[0] as JsonWriter)
                call.result = null
            },
        )
        patcher.patch(
            PayloadJSON.ConverterFactory.RequestBodyConverter::class.java,
            "convert",
            arrayOf(PayloadJSON::class.java),
            Hook { call ->
                val message = ReflectUtils.getField(call.args[0], "data") as? RestAPIParams.Message ?: return@Hook
                val uploads = messageUploads[message] ?: return@Hook
                val body = call.result as RequestBody
                val buffer = g0.e()
                body.writeTo(buffer)
                val filenames = arrayOfNulls<String>(uploads.size)
                val texts = arrayOfNulls<String>(uploads.size)
                var index = 0
                while (index < uploads.size) {
                    filenames[index] = uploads[index].name
                    texts[index] = uploadDescriptions[uploads[index]]
                    index++
                }
                val json = AttachmentMetadata.withUploads(buffer.D(), filenames, texts)
                call.result = RequestBody.create(json, body.contentType())
                logger.info("Encoded attachment descriptions in the message upload request")
            },
        )
    }

    private fun draftKey(attachment: Attachment<*>) = attachment.uri.toString()

    /** Inflate the actual client layout so text appearances remain view styles, never activity themes. */
    private fun nativeAttachmentText(context: Context, name: String): TextView {
        val template = LayoutInflater.from(context).inflate(
            Utils.getResId("widget_attachment_bottom_sheet", "layout"),
            null,
            false,
        )
        return template.findViewById<TextView>(Utils.getResId(name, "id")).also {
            (it.parent as ViewGroup).removeView(it)
        }
    }

    private fun patchUploads() {
        // Aliucord's required UploadSize core plugin bypasses Retrofit and sends a cloud-upload payload.
        val nonceField = RNMessage::class.java.getDeclaredField("nonce").apply { isAccessible = true }
        val cloudPayload = Class.forName("com.aliucord.coreplugins.UploadSize\$Companion\$MessagePayload")
        patcher.patch(
            Http.Request::class.java,
            "executeWithJson",
            arrayOf(Gson::class.java, Any::class.java),
            PreHook { call ->
                val payload = call.args[1] ?: return@PreHook
                if (!cloudPayload.isInstance(payload)) return@PreHook
                val nonce = nonceField.get(payload) as? String ?: return@PreHook
                val texts = drafts.forRequest(nonce) ?: return@PreHook
                if (!texts.any { it != null }) return@PreHook
                val gson = call.args[0] as Gson
                val json = AttachmentMetadata.withCloudUploads(gson.m(payload), texts)
                call.args[1] = gson.f(json, JsonElement::class.java)
                logger.info("Encoded attachment descriptions in Aliucord's cloud upload request")
            },
        )
        // Compression replaces both URI and ID. Transfer metadata in the actual completion callback.
        patcher.patch(
            SendUtils::class.java,
            "compressImageAttachments",
            arrayOf(Context::class.java, List::class.java, kotlin.jvm.functions.Function1::class.java),
            PreHook { call ->
                @Suppress("UNCHECKED_CAST")
                val original = (call.args[1] as List<Attachment<*>>).map { drafts.remove(draftKey(it)) }

                @Suppress("UNCHECKED_CAST")
                val callback = call.args[2] as (List<Attachment<*>>) -> Unit
                call.args[2] = { compressed: List<Attachment<*>> ->
                    if (active) {
                        compressed.forEachIndexed { index, attachment ->
                            original.getOrNull(index)?.let { drafts.save(draftKey(attachment), it) }
                        }
                    }
                    callback(compressed)
                }
            },
        )
        patcher.patch(
            SendUtils::class.java,
            "getSendPayload",
            arrayOf(ContentResolver::class.java, RestAPIParams.Message::class.java, List::class.java),
            Hook { call ->
                val message = call.args[1] as RestAPIParams.Message

                @Suppress("UNCHECKED_CAST")
                val attachments = call.args[2] as? List<Attachment<*>> ?: return@Hook
                val links = AttachmentUtilsKt.extractLinks(attachments, call.args[0] as ContentResolver)
                val files = attachments.filter { !links.contains(it) }
                val texts = drafts.snapshot(message.nonce, files.map { draftKey(it) })
                if (!texts.any { it != null }) return@Hook

                @Suppress("UNCHECKED_CAST")
                val result = call.result as Observable<SendUtils.SendPayload>
                // Observe the final payload, after filenames and message objects have been rebuilt.
                call.result = result.G { payload ->
                    if (active && payload is SendUtils.SendPayload.ReadyToSend) {
                        val uploads = payload.uploads
                        check(uploads.size == texts.size) { "Upload order changed while preparing image descriptions" }
                        uploads.forEachIndexed { index, upload ->
                            texts[index]?.let { uploadDescriptions.put(upload, it) }
                        }
                        if (uploads.any { uploadDescriptions[it] != null }) {
                            messageUploads.put(payload.message, uploads)
                            logger.info("Matched image descriptions to the prepared uploads")
                        }
                    }
                    payload
                }
            },
        )
        patcher.patch(
            RestAPI::class.java,
            "sendMessage",
            arrayOf(Long::class.javaPrimitiveType!!, PayloadJSON::class.java, Array<MultipartBody.Part>::class.java),
            PreHook { call ->
                val message = ReflectUtils.getField(call.args[1], "data") as? RestAPIParams.Message ?: return@PreHook
                val uploads = messageUploads[message] ?: return@PreHook

                @Suppress("UNCHECKED_CAST")
                val parts = call.args[2] as Array<MultipartBody.Part>
                // Use the documented field names so Discord can associate each description with its file.
                call.args[2] = parts
                    .mapIndexed { index, part ->
                        MultipartBody.Part.b("files[$index]", uploads[index].name, part.b)
                    }.toTypedArray()
            },
        )
    }

    private fun patchMenu() {
        patcher.patch(
            AttachmentBottomSheet::class.java,
            "onViewCreated",
            arrayOf(View::class.java, Bundle::class.java),
            Hook { call ->
                val sheet = call.thisObject as AttachmentBottomSheet
                val attachment = sheet.attachment ?: return@Hook
                val view = call.args[0] as View
                if (!AttachmentUtilsKt.isImage(attachment, view.context.contentResolver)) return@Hook
                // Let other onViewCreated hooks finish inserting their rows before splicing ours into the chain.
                view.post {
                    if (!active || sheet.view !== view) return@post
                    val remove = view.findViewById<TextView>(Utils.getResId("attachment_remove_item", "id"))
                    val parent = remove.parent as ConstraintLayout
                    val mark = view.findViewById<TextView>(Utils.getResId("attachment_mark_spoiler", "id"))
                    val removeParams = remove.layoutParams as ConstraintLayout.LayoutParams
                    val previous = removeParams.topToBottom
                    val edit = nativeAttachmentText(view.context, "attachment_remove_item").apply {
                        id = View.generateViewId()
                        text = "Edit image description"
                        setTextColor(mark.textColors)
                        setOnClickListener { editDescription(sheet, attachment) }
                    }
                    parent.addView(
                        edit,
                        ConstraintLayout.LayoutParams(-1, -2).apply {
                            startToStart = 0
                            endToEnd = 0
                            topToBottom = previous
                        },
                    )
                    removeParams.topToBottom = edit.id
                    remove.layoutParams = removeParams
                    menus.removeAll { it.edit.get() == null }
                    menus.add(MenuState(WeakReference(edit), WeakReference(remove), previous))
                }
            },
        )
        patcher.patch(
            "com.discord.widgets.chat.input.attachments.AttachmentBottomSheet\$onViewCreated\$3",
            "onClick",
            arrayOf(View::class.java),
            PreHook { call ->
                val sheet = ReflectUtils.getField(call.thisObject, "this$0") as AttachmentBottomSheet
                sheet.attachment?.let { drafts.remove(draftKey(it)) }
            },
        )
    }

    private fun editDescription(sheet: AttachmentBottomSheet, attachment: Attachment<*>) {
        val key = draftKey(attachment)
        val dialog = InputDialog()
        dialog
            .setTitle("Image description")
            .setDescription(
                "Describe this image for people using screen readers. Leave blank to remove the description.",
            ).setPlaceholderText("Description (up to 1024 characters)")
            .setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            .setOnOkListener {
                val text = dialog.input
                drafts.save(key, text)
                dialog.dismiss()
                Utils.showToast(if (DescriptionText.hasText(text)) "Image description saved" else "Description removed")
            }
        dialog.setOnDialogShownListener {
            dialog.inputLayout.editText?.apply {
                filters = arrayOf(InputFilter.LengthFilter(1024))
                setSingleLine(false)
                setText(drafts[key].orEmpty())
                setSelection(text.length)
            }
            dialog.okButton.text = "Save"
        }
        dialogs.removeAll { it.get() == null }
        dialogs.add(WeakReference(dialog))
        dialog.show(sheet.parentFragmentManager, "image-description-editor")
    }

    private fun patchImages() {
        patcher.patch(
            WidgetChatListAdapterItemAttachment::class.java,
            "configureUI",
            arrayOf(WidgetChatListAdapterItemAttachment.Model::class.java),
            Hook { call ->
                val holder = call.thisObject as WidgetChatListAdapterItemAttachment
                val model = call.args[0] as WidgetChatListAdapterItemAttachment.Model
                val attachment = model.attachmentEntry.attachment
                val media = holder.itemView.findViewById<InlineMediaView>(
                    Utils.getResId("chat_list_item_attachment_inline_media", "id"),
                )
                val text = runCatching { ReflectUtils.getField(attachment, "description") as? String }.getOrNull()
                    ?: descriptions[attachment]
                val parent = media.parent as ConstraintLayout
                val visible = text != null &&
                    DescriptionText.hasText(text) &&
                    !model.isSpoilerHidden &&
                    attachment.e() == MessageAttachmentType.IMAGE &&
                    media.visibility == View.VISIBLE
                if (!visible) {
                    parent.findViewById<View>(badgeId)?.visibility = View.GONE
                    media
                        .findViewById<View>(Utils.getResId("inline_media_image_preview", "id"))
                        ?.contentDescription = media.context.getString(Utils.getResId("image", "string"))
                    return@Hook
                }
                val badge = parent.findViewById<TextView>(badgeId) ?: run {
                    nativeAttachmentText(media.context, "attachment_duration").apply {
                        id = badgeId
                        setText("ALT")
                        val nativeMargins = layoutParams as ViewGroup.MarginLayoutParams
                        parent.addView(
                            this,
                            ConstraintLayout.LayoutParams(-2, -2).apply {
                                startToStart = media.id
                                bottomToBottom = media.id
                                setMargins(
                                    nativeMargins.leftMargin,
                                    nativeMargins.topMargin,
                                    nativeMargins.rightMargin,
                                    nativeMargins.bottomMargin,
                                )
                            },
                        )
                        elevation = media.elevation + resources.getDimension(Utils.getResId("app_elevation", "dimen"))
                        isFocusable = true
                        badges.removeAll { it.get() == null }
                        badges.add(WeakReference(this))
                    }
                }
                badge.visibility = View.VISIBLE
                badge.bringToFront()
                badge.contentDescription = "View image description"
                badge.setOnClickListener {
                    try {
                        showDescription(badge.context, text)
                    } catch (error: Throwable) {
                        logger.error("Could not open the image description", error)
                        Utils.showToast("Could not open the image description")
                    }
                }
                media
                    .findViewById<View>(Utils.getResId("inline_media_image_preview", "id"))
                    ?.contentDescription = text
            },
        )
    }

    private fun showDescription(context: Context, text: String) {
        var activityContext = context
        while (activityContext is ContextWrapper && activityContext !is FragmentActivity) {
            activityContext = activityContext.baseContext
        }
        val activity = activityContext as? FragmentActivity ?: Utils.appActivity
        val dialog = InputDialog()
        dialog.setTitle("Image description").setDescription(text).setOnOkListener { dialog.dismiss() }
        dialog.setOnDialogShownListener {
            dialog.inputLayout.visibility = View.GONE
            dialog.cancelButton.visibility = View.GONE
            dialog.okButton.text = "Done"
            dialog.body.text = text
            dialog.body.setTextIsSelectable(true)
        }
        dialogs.removeAll { it.get() == null }
        dialogs.add(WeakReference(dialog))
        dialog.show(activity.supportFragmentManager, "image-description-viewer")
    }

    override fun stop(context: Context) {
        active = false
        patcher.unpatchAll()
        badges.forEach { reference ->
            val badge = reference.get() ?: return@forEach
            val parent = badge.parent as? ViewGroup ?: return@forEach
            val media = parent.findViewById<InlineMediaView>(
                Utils.getResId("chat_list_item_attachment_inline_media", "id"),
            )
            parent.removeView(badge)
            media
                .findViewById<View>(Utils.getResId("inline_media_image_preview", "id"))
                ?.contentDescription = context.getString(Utils.getResId("image", "string"))
        }
        badges.clear()
        menus.forEach { state ->
            val edit = state.edit.get() ?: return@forEach
            val remove = state.remove.get() ?: return@forEach
            val params = remove.layoutParams as ConstraintLayout.LayoutParams
            if (params.topToBottom == edit.id) {
                params.topToBottom = state.previous
                remove.layoutParams = params
            }
            (edit.parent as? android.view.ViewGroup)?.removeView(edit)
        }
        menus.clear()
        dialogs.forEach { it.get()?.dismiss() }
        dialogs.clear()
        descriptions.clear()
        drafts.clear()
        uploadDescriptions.clear()
        messageUploads.clear()
    }
}
