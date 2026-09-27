package com.github.yutaplug.newlinks

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import com.discord.api.channel.ChannelUtils
import com.discord.models.guild.Guild
import com.discord.simpleast.core.node.Node
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.EmojiNode
import com.discord.utilities.textprocessing.node.SpoilerNode
import com.discord.utilities.textprocessing.node.UrlNode
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin(requiresRestart = true)
class NewLinks : Plugin() {
    private val hiddenLinks = Collections.synchronizedMap(WeakHashMap<UrlNode<*>, Boolean>())

    override fun start(context: Context) {
        val mask = UrlNode::class.java.getDeclaredField("mask").apply { isAccessible = true }

        // Native spoilers hide text, but cannot hide a newly inserted image span.
        // Inspect the already preprocessed tree before rendering any guild icons.
        patcher.patch(
            AstRenderer::class.java,
            "render",
            arrayOf(Collection::class.java, Any::class.java),
            PreHook { frame ->
                if (frame.args[1] is MessageRenderContext) {
                    val nodes = frame.args[0] as? Collection<*> ?: return@PreHook
                    rememberSpoilers(nodes, false)
                }
            },
        )

        val render = PreHook { frame ->
            val node = frame.thisObject as? UrlNode<*> ?: return@PreHook
            val renderContext = frame.args[1] as? MessageRenderContext ?: return@PreHook
            // Preserve explicitly named Markdown hyperlinks and unrevealed spoilers.
            if (mask.get(node) != null || hiddenLinks[node] == true) return@PreHook
            val destination = resolve(node.url, renderContext.context) ?: return@PreHook
            val builder = frame.args[0] as SpannableStringBuilder
            val start = builder.length
            val richBuilder = builder as? DraweeSpanStringBuilder
            val holders = richBuilder?.k?.toSet()
            try {
                renderLink(builder, renderContext, node.url, destination)
                frame.result = null
            } catch (error: Throwable) {
                // Restore the builder so Discord can render the original URL on failure.
                for (span in builder.getSpans(start, builder.length, Any::class.java)) {
                    if (builder.getSpanStart(span) >= start) builder.removeSpan(span)
                }
                builder.delete(start, builder.length)
                if (richBuilder != null && holders != null) richBuilder.k.retainAll(holders)
                logger.error("Could not render a Discord link", error)
            }
        }
        // Discord calls both the generic bridge and the typed implementation.
        for (contextClass in arrayOf(UrlNode.RenderContext::class.java, Any::class.java)) {
            patcher.patch(
                UrlNode::class.java,
                "render",
                arrayOf(SpannableStringBuilder::class.java, contextClass),
                render,
            )
        }
    }

    private fun rememberSpoilers(nodes: Collection<*>, hidden: Boolean) {
        for (node in nodes) {
            if (node !is Node<*>) continue
            val concealed = hidden || (node is SpoilerNode<*> && !node.isRevealed)
            if (node is UrlNode<*>) {
                if (concealed) hiddenLinks[node] = true else hiddenLinks.remove(node)
            }
            node.children?.let { rememberSpoilers(it, concealed) }
        }
    }

    private data class Destination(val label: String, val guild: Guild?, val icon: LinkIconSpan.Kind?)

    private fun resolve(url: String, context: Context): Destination? {
        DiscordAttachment.parse(url)?.let { return Destination(it.filename, null, LinkIconSpan.Kind.FILE) }
        val link = DiscordLink.parse(url) ?: return null
        val icon = if (link.messageId != null) LinkIconSpan.Kind.MESSAGE else null
        val channels = StoreStream.getChannels()
        val channel = channels.getChannel(link.channelId) ?: return null
        val name = ChannelUtils.c(channel)
        if (name.isEmpty()) return null
        if (link.guildId == null) {
            if (!ChannelUtils.B(channel)) return null
            return Destination("@$name", null, icon)
        }
        if (channel.i() != link.guildId || ChannelUtils.B(channel)) return null
        val guild = StoreStream.getGuilds().getGuild(link.guildId) ?: return null
        if (guild.name.isNullOrEmpty()) return null
        val label = if (ChannelUtils.H(channel)) {
            val parent = channels.getChannel(channel.u())
            if (parent != null && parent.i() == link.guildId) {
                "${guild.name} › ${ChannelUtils.d(parent, context, true)} › $name"
            } else {
                "${guild.name} › $name"
            }
        } else {
            "${guild.name} › ${ChannelUtils.d(channel, context, true)}"
        }
        return Destination(label, guild, icon)
    }

    private fun renderLink(
        builder: SpannableStringBuilder,
        context: MessageRenderContext,
        url: String,
        destination: Destination,
    ) {
        val color = ColorCompat.getThemedColor(context.context, R.b.theme_chat_mention_foreground)
        val background = ColorCompat.getThemedColor(context.context, R.b.theme_chat_mention_background)
        val start = builder.length
        val icon = destination.guild?.let { IconUtils.getForGuild(it) }
        if (icon != null && builder is DraweeSpanStringBuilder) {
            EmojiNode<MessageRenderContext>(
                "\uFFFC",
                { _, _, _ -> icon },
                EmojiNode.EmojiIdAndType.Unicode(destination.guild.name),
            ).render(builder, context)
            // The server icon belongs to the link, not to Discord's emoji picker.
            for (span in builder.getSpans(start, builder.length, ClickableSpan::class.java)) {
                builder.removeSpan(span)
            }
            builder.append(' ')
        }

        val labelStart = builder.length
        builder.append(' ')
        if (destination.icon == LinkIconSpan.Kind.FILE) {
            appendIcon(builder, LinkIconSpan.Kind.FILE, color, background)
            builder.append(' ')
        }
        builder.append(destination.label)
        if (destination.icon == LinkIconSpan.Kind.MESSAGE) {
            builder.append(" › ")
            appendIcon(builder, LinkIconSpan.Kind.MESSAGE, color, background)
        }
        builder.append(' ')
        val end = builder.length
        builder.setSpan(StyleSpan(Typeface.BOLD), labelStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(BackgroundColorSpan(background), labelStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val clickable = ClickableSpan(
            color,
            false,
            { context.onLongPressUrl.invoke(url) },
            { view -> context.onClickUrl.invoke(view.context, url, null) },
        )
        builder.setSpan(clickable, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendIcon(builder: SpannableStringBuilder, kind: LinkIconSpan.Kind, color: Int, background: Int) {
        val start = builder.length
        builder.append('\uFFFC')
        builder.setSpan(LinkIconSpan(kind, color, background), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        hiddenLinks.clear()
    }
}
