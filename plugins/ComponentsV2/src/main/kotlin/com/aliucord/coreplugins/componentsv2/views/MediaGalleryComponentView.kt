@file:Suppress("MISSING_DEPENDENCY_CLASS", "MISSING_DEPENDENCY_SUPERCLASS")

package com.aliucord.coreplugins.componentsv2.views

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
import com.aliucord.Logger
import com.aliucord.coreplugins.CV2Compat
import com.aliucord.coreplugins.componentsv2.BotUiComponentV2Entry
import com.aliucord.coreplugins.componentsv2.ComponentV2Type
import com.aliucord.coreplugins.componentsv2.models.MediaGalleryMessageComponent
import com.aliucord.utils.DimenUtils.dp
import com.aliucord.utils.ReflectUtils
import com.aliucord.utils.ViewUtils.addTo
import com.aliucord.widgets.LinearLayout
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.height
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.width
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.embed.EmbedThumbnail
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.EmbedVideo
import com.discord.api.message.embed.MessageEmbed
import com.discord.api.botuikit.UnfurledMediaItem
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.display.DisplayUtils
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.widgets.botuikit.ComponentProvider
import com.discord.widgets.botuikit.views.ComponentActionListener
import com.discord.widgets.botuikit.views.ComponentView
import com.discord.widgets.chat.list.InlineMediaView
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemBotComponentRow
import com.discord.widgets.media.WidgetMedia
import com.google.android.material.card.MaterialCardView
import com.lytefast.flexinput.R

class MediaGalleryComponentView(ctx: Context) : ConstraintLayout(ctx), ComponentView<MediaGalleryMessageComponent> {
    override fun type() = ComponentV2Type.MEDIA_GALLERY

    companion object {
        private val mediaViewId = View.generateViewId()
        private val maxEmbedHeight = EmbedResourceUtils.INSTANCE.maX_IMAGE_VIEW_HEIGHT_PX
    }

    private val layout = LinearLayout(ctx).addTo(this) {
        layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topToTop = PARENT_ID
            startToStart = PARENT_ID
            endToEnd = PARENT_ID
        }
    }
    private var mediaViews: List<Pair<MessageAttachment, InlineMediaView>>? = null

    private fun mediaThumbnail(media: UnfurledMediaItem): EmbedThumbnail =
        ReflectUtils.allocateInstance(EmbedThumbnail::class.java).also {
            ReflectUtils.setField(it, "url", media.url)
            ReflectUtils.setField(it, "proxyUrl", media.proxyUrl)
            ReflectUtils.setField(it, "width", media.width)
            ReflectUtils.setField(it, "height", media.height)
        }

    private fun imageEmbed(media: UnfurledMediaItem): MessageEmbed =
        ReflectUtils.allocateInstance(MessageEmbed::class.java).also {
            ReflectUtils.setField(it, "type", EmbedType.IMAGE)
            ReflectUtils.setField(it, "url", media.url)
            ReflectUtils.setField(it, "thumbnail", mediaThumbnail(media))
        }

    private fun videoEmbed(media: UnfurledMediaItem, source: MessageEmbed?): MessageEmbed {
        val video = source?.m() ?: ReflectUtils.allocateInstance(EmbedVideo::class.java).also {
            ReflectUtils.setField(it, "url", media.url)
            ReflectUtils.setField(it, "proxyUrl", media.url)
            ReflectUtils.setField(it, "width", media.width)
            ReflectUtils.setField(it, "height", media.height)
        }
        return ReflectUtils.allocateInstance(MessageEmbed::class.java).also {
            ReflectUtils.setField(it, "type", EmbedType.VIDEO)
            ReflectUtils.setField(it, "url", source?.l() ?: media.url)
            ReflectUtils.setField(it, "thumbnail", mediaThumbnail(media))
            ReflectUtils.setField(it, "video", video)
        }
    }

    // This isn't pretty, but Discord actually does this in their code (EmbedResourceUtils.computeMaximumImageWidthPx)
    private fun calculateMaxWidth(contained: Boolean): Int {
        var maxPossibleWidth = DisplayUtils.getScreenSize(context).width() -
            resources.getDimensionPixelSize(R.d.uikit_guideline_chat) -
            resources.getDimensionPixelSize(R.d.chat_cell_horizontal_spacing_total)

        if (contained)
            maxPossibleWidth -= 15.dp

        return maxPossibleWidth.coerceAtMost(1440)
    }

    // Reference: WidgetChatListAdapterItemAttachment.configureUI
    override fun configure(component: MediaGalleryMessageComponent, provider: ComponentProvider, listener: ComponentActionListener) {
        val item = listener as WidgetChatListAdapterItemBotComponentRow
        val entry = item.entry
        if (entry !is BotUiComponentV2Entry) {
            Logger("ComponentsV2").warn("configured media gallery with non-v2 entry")
            return
        }

        val maxEmbedWidth = calculateMaxWidth(component.markedContained)
        layout.removeAllViews()
        val pendingViews = mutableListOf<Pair<MessageAttachment, InlineMediaView>>()
        val sourceVideos = entry.message.referencedMessage?.k()?.filter { it.m() != null }.orEmpty()
        var videoIndex = 0
        component.items.forEachIndexed { index, it ->
            val media = it.media
            val video = if (media.contentType?.startsWith("video/") == true)
                videoEmbed(media, sourceVideos.getOrNull(videoIndex++)) else null
            val image = if (media.contentType?.startsWith("image/") == true)
                imageEmbed(media) else null
            // TODO: there's probably a utility to extract filename from url
            val name = media.url.split("/").last().split("?").first()
            val attachment = CV2Compat.createAttachment(
                name,
                0,
                media.proxyUrl,
                media.url,
                media.width,
                media.height,
            )

            val (width, height) = EmbedResourceUtils.INSTANCE.calculateScaledSize(
                attachment.width!!,
                attachment.height!!,
                maxEmbedWidth,
                maxEmbedHeight,
                resources,
                0,
            )
            MaterialCardView(context).addTo(layout) {
                radius = 8.dp.toFloat()
                elevation = 0f
                setCardBackgroundColor(ColorCompat.getThemedColor(context, R.b.colorBackgroundPrimary))
                layoutParams = android.widget.LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    topMargin = 8.dp
                }
                ConstraintLayout(context).addTo(this) {
                    layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    val mediaView = InlineMediaView(context).addTo(this) {
                        radius = 8.dp.toFloat()
                        elevation = 0f
                        setCardBackgroundColor(ColorCompat.getThemedColor(context, R.b.colorBackgroundPrimary))
                        id = mediaViewId
                        layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                            topToTop = PARENT_ID
                            startToStart = PARENT_ID
                        }
                        setOnClickListener {
                            if (video != null) WidgetMedia.Companion!!.launch(context, video)
                            else if (image != null) WidgetMedia.Companion!!.launch(context, image)
                            else WidgetMedia.Companion!!.launch(context, attachment)
                        }
                        if (video != null) updateUIWithEmbed(video, width, height, true)
                        else if (image != null) updateUIWithEmbed(image, width, height, true)
                        else updateUIWithAttachment(attachment, width, height, true)
                    }
                    val spoilerView = SpoilerView(context, 1).addTo(this) {
                        translationZ = 10f
                        layoutParams = SpoilerView.constraintLayoutParamsAround(mediaViewId)
                    }
                    pendingViews.add(attachment to mediaView)
                    spoilerView.configure(it.spoiler, entry.state, entry.message.id, Pair(component.id, "media:$index"))
                }
            }
        }
        mediaViews = pendingViews.toList()
    }
}
