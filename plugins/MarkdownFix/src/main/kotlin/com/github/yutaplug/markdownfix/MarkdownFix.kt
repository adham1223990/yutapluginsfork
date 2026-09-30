package com.github.yutaplug.markdownfix

import android.content.Context
import android.text.SpannableStringBuilder
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.simpleast.code.CodeNode
import com.discord.simpleast.core.parser.Parser
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.BasicRenderContext
import com.discord.utilities.textprocessing.node.BlockQuoteNode
import com.discord.utilities.textprocessing.node.EditedMessageNode
import com.discord.utilities.textprocessing.node.UrlNode
import com.discord.utilities.textprocessing.node.ZeroSpaceWidthNode
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.facebook.drawee.span.SimpleDraweeSpanTextView
import java.util.WeakHashMap

@AliucordPlugin
class MarkdownFix : Plugin() {
    private var games: GameProfileResolver? = null
    private val textViews = WeakHashMap<SimpleDraweeSpanTextView, Boolean>()

    override fun start(context: Context) {
        val resolver = GameProfileResolver { refreshAppearance() }
        games = resolver
        val parser = MarkdownParser(settings, resolver)
        settingsTab =
            SettingsTab(MarkdownFixSettings::class.java, SettingsTab.Type.PAGE).withArgs(settings, this)
        try {
            installBlockRendering()
            installRichLinks(parser)
            patcher.patch(
                AstRenderer::class.java.getDeclaredMethod("render", Collection::class.java, Any::class.java),
                Hook { frame ->
                    val builder = frame.result as? SpannableStringBuilder ?: return@Hook
                    val renderContext = frame.args[1] as? BasicRenderContext ?: return@Hook
                    MarkdownBlocks.apply(builder, renderContext.context, settings)
                },
            )
            patcher.patch(
                DiscordParser::class.java.getDeclaredMethod(
                    "parseChannelMessage",
                    Context::class.java,
                    String::class.java,
                    MessageRenderContext::class.java,
                    MessagePreprocessor::class.java,
                    DiscordParser.ParserOptions::class.java,
                    Boolean::class.javaPrimitiveType,
                ),
                PreHook { frame ->
                    try {
                        val ast = parser.parse(frame.args[1] as? String ?: "")
                        (frame.args[3] as MessagePreprocessor).process(ast)
                        val renderContext = frame.args[2] as MessageRenderContext
                        val builder = AstRenderer.render(ast, renderContext)
                        // Let AstRenderer trim the final block boundary before appending metadata,
                        // otherwise its zero-width suffix creates an extra empty line after quotes.
                        if (frame.args[5] ==
                            true
                        ) {
                            EditedMessageNode<MessageRenderContext>(
                                frame.args[0] as Context,
                            ).render(builder, renderContext)
                        }
                        ZeroSpaceWidthNode<MessageRenderContext>().render(builder, renderContext)
                        frame.result = builder
                    } catch (error: Exception) {
                        logger.error("Could not render Markdown; using Discord's parser", error)
                    }
                },
            )
            installEmbedParsing(parser)
            installAnsiRendering()
            patcher.patch(
                SimpleDraweeSpanTextView::class.java.getDeclaredMethod(
                    "setDraweeSpanStringBuilder",
                    DraweeSpanStringBuilder::class.java,
                ),
                PreHook { frame ->
                    val view = frame.thisObject as SimpleDraweeSpanTextView
                    val builder = frame.args[0] as? DraweeSpanStringBuilder
                    if (builder == null) {
                        textViews.remove(view)
                    } else {
                        resolver.update(builder)
                        textViews[view] = true
                    }
                },
            )
        } catch (error: Throwable) {
            stop(context)
            throw error
        }
    }

    private fun installEmbedParsing(parser: MarkdownParser) {
        val embed = Class.forName("com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed")
        val embedParsers = listOf("UI_THREAD_TITLES_PARSER", "UI_THREAD_VALUES_PARSER").map {
            embed.getDeclaredField(it).apply { isAccessible = true }.get(null)
        }
        patcher.patch(
            Parser::class.java.getDeclaredMethod("parse", CharSequence::class.java, Any::class.java, List::class.java),
            PreHook { frame ->
                if (embedParsers.none { it === frame.thisObject }) return@PreHook
                try {
                    frame.result = parser.parse(frame.args[0] as CharSequence, frame.args[1] as MessageParseState)
                } catch (error: Exception) {
                    logger.error("Could not parse embed Markdown; using Discord's parser", error)
                }
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun installBlockRendering() {
        for (contextType in listOf(BasicRenderContext::class.java, Any::class.java)) {
            patcher.patch(
                BlockQuoteNode::class.java.getDeclaredMethod("render", SpannableStringBuilder::class.java, contextType),
                PreHook { frame ->
                    MarkdownBlocks.renderQuote(
                        frame.thisObject as BlockQuoteNode<BasicRenderContext>,
                        frame.args[0] as SpannableStringBuilder,
                        frame.args[1] as BasicRenderContext,
                    )
                    frame.result = null
                },
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun installAnsiRendering() {
        patcher.patch(
            CodeNode::class.java.getDeclaredMethod("render", SpannableStringBuilder::class.java, Any::class.java),
            PreHook { frame ->
                val node = frame.thisObject as CodeNode<BasicRenderContext>
                val context = frame.args[1] as? BasicRenderContext ?: return@PreHook
                if (!node.a.equals("ansi", ignoreCase = true)) return@PreHook
                AnsiCodeBlocks.render(node, frame.args[0] as SpannableStringBuilder, context)
                frame.result = null
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun installRichLinks(parser: MarkdownParser) {
        val mask = UrlNode::class.java.getDeclaredField("mask").apply { isAccessible = true }
        for (contextType in listOf(UrlNode.RenderContext::class.java, Any::class.java)) {
            patcher.patch(
                UrlNode::class.java.getDeclaredMethod("render", SpannableStringBuilder::class.java, contextType),
                PreHook { frame ->
                    val context = frame.args[1] as? MessageRenderContext ?: return@PreHook
                    val node = frame.thisObject as UrlNode<MessageRenderContext>
                    val label = mask.get(node) as? String ?: return@PreHook
                    val builder = frame.args[0] as SpannableStringBuilder
                    val children =
                        node.children ?: parser.parse(label, blocks = false, maskedLinks = false, urls = false)
                    MarkdownLinks.render(node, builder, context, label, children)
                    frame.result = null
                },
            )
        }
    }

    internal fun refreshAppearance() {
        // Rebinding spans clears TextView's measurement cache and keeps the current spoiler state.
        for (view in textViews.keys.toList()) {
            val builder = view.j ?: continue
            games?.update(builder)
            view.setDraweeSpanStringBuilder(builder)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        games?.stop()
        games = null
        textViews.clear()
    }
}
