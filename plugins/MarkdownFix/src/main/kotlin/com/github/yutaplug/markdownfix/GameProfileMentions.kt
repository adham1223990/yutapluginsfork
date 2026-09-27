package com.github.yutaplug.markdownfix

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.aliucord.Utils
import com.discord.simpleast.core.parser.Parser
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext
import rx.subscriptions.CompositeSubscription
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

internal class GameProfileMentionRule(private val resolver: GameProfileResolver) :
    MessageRule(Pattern.compile("^<@\\$([0-9]{1,20})>")) {
    override fun parse(
        match: Matcher,
        parser: Parser<MessageRenderContext, in MessageNode, MessageParseState>,
        state: MessageParseState,
    ): MessageSpec = MessageSpec(GameProfileMentionNode(match.group(1)!!, resolver), state)
}

private class GameProfileMentionNode(private val id: String, private val resolver: GameProfileResolver) :
    MessageNode() {
    override fun render(builder: SpannableStringBuilder, context: MessageRenderContext) {
        val start = builder.length
        val name = resolver.name(id)
        builder.append("@${name ?: id}")
        GameProfileResolver.style(builder, context.context, start, builder.length)
        if (name == null) {
            // Span anchors survive emoji insertion, quote merging, and replacement of other mentions.
            builder.setSpan(GameMention(id), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            resolver.fetch(id)
        }
    }
}

private class GameMention(val id: String)

internal class GameProfileResolver(private val onResolved: () -> Unit) {
    private val names = ConcurrentHashMap<String, String>()
    private val requests = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, Long>()
    private val subscriptions = CompositeSubscription()

    @Volatile private var active = true

    fun name(id: String): String? = names[id]

    fun fetch(id: String) {
        val applicationId = id.toLongOrNull()?.takeIf { it > 0 } ?: return
        val lastFailure = failures[id]
        if (!active ||
            names.containsKey(id) ||
            (lastFailure != null && SystemClock.elapsedRealtime() - lastFailure < RETRY_DELAY) ||
            !requests.add(id)
        ) {
            return
        }
        Utils.threadPool.execute {
            if (!active) return@execute
            try {
                val subscription = RestAPI.getApi().getApplications(applicationId).W(
                    { applications ->
                        val application = applications?.firstOrNull { it.g() == applicationId }
                        val name = application?.h()?.trim()?.takeIf { it.isNotEmpty() }
                        complete(id, name)
                    },
                    { complete(id, null) },
                )
                subscriptions.a(subscription)
            } catch (_: Exception) {
                complete(id, null)
            }
        }
    }

    private fun complete(id: String, name: String?) {
        if (!active) return
        if (name == null) failures[id] = SystemClock.elapsedRealtime() else names[id] = name
        requests.remove(id)
        if (name != null) Utils.mainThread.post { if (active) onResolved() }
    }

    fun update(builder: SpannableStringBuilder): Boolean {
        var changed = false
        val mentions = builder
            .getSpans(
                0,
                builder.length,
                GameMention::class.java,
            ).sortedByDescending(builder::getSpanStart)
        for (mention in mentions) {
            val name = names[mention.id] ?: continue
            val start = builder.getSpanStart(mention)
            val end = builder.getSpanEnd(mention)
            if (start < 0 || end <= start) continue
            val coveringSpans = builder
                .getSpans(start, end, Any::class.java)
                .filter {
                    it !== mention && builder.getSpanStart(it) <= start && builder.getSpanEnd(it) >= end
                }.map { SpanRange(it, builder.getSpanStart(it), builder.getSpanEnd(it), builder.getSpanFlags(it)) }
            // Android can remove exclusive spans when their entire text is replaced. Restore their
            // order as well as their bounds so quote margins and hidden spoilers remain intact.
            coveringSpans.forEach { builder.removeSpan(it.span) }
            builder.removeSpan(mention)
            builder.replace(start, end, "@$name")
            val difference = name.length + 1 - (end - start)
            coveringSpans.forEach { builder.setSpan(it.span, it.start, it.end + difference, it.flags) }
            changed = true
        }
        return changed
    }

    private data class SpanRange(
        val span: Any,
        val start: Int,
        val end: Int,
        val flags: Int,
    )

    fun stop() {
        active = false
        subscriptions.unsubscribe()
        names.clear()
        requests.clear()
        failures.clear()
    }

    companion object {
        private const val RETRY_DELAY = 60_000L

        fun style(builder: SpannableStringBuilder, context: Context, start: Int, end: Int) {
            builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(
                ForegroundColorSpan(
                    MarkdownAppearance.themedColor(context, "theme_chat_mention_foreground", Color.WHITE),
                ),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.setSpan(
                BackgroundColorSpan(
                    MarkdownAppearance.themedColor(context, "theme_chat_mention_background", Color.TRANSPARENT),
                ),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}
