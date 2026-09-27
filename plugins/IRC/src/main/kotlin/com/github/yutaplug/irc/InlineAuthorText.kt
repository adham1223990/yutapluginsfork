package com.github.yutaplug.irc

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.MetricAffectingSpan

/** Adds an author to the native rich text without reparsing the message. */
internal object InlineAuthorText {
    private const val SEPARATOR = "\u2002"

    fun prepend(
        text: SpannableStringBuilder,
        name: String,
        typeface: Typeface?,
        textSize: Float,
        color: Int,
        link: ClickableSpan,
    ) {
        // Rebinding a builder must remove only our prefix, preserving body spans.
        for (previous in text.getSpans(0, text.length, AuthorSpan::class.java)) {
            val start = text.getSpanStart(previous)
            val end = text.getSpanEnd(previous)
            text.removeSpan(previous)
            text.removeSpan(previous.link)
            if (start >= 0 && end >= start) text.delete(start, end)
        }
        text.insert(0, name + SEPARATOR)
        val author = AuthorSpan(name.length, typeface, textSize, color, link)
        text.setSpan(author, 0, name.length + SEPARATOR.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(link, 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun find(text: CharSequence): AuthorSpan? =
        (text as? Spanned)?.getSpans(0, text.length, AuthorSpan::class.java)?.firstOrNull()

    class AuthorSpan(
        val nameLength: Int,
        private val typeface: Typeface?,
        private val textSize: Float,
        private val color: Int,
        val link: ClickableSpan,
    ) : MetricAffectingSpan() {
        override fun updateMeasureState(paint: TextPaint) {
            paint.typeface = typeface
            paint.textSize = textSize
            paint.isFakeBoldText = false
            paint.textSkewX = 0f
        }

        override fun updateDrawState(paint: TextPaint) {
            updateMeasureState(paint)
            paint.color = color
            paint.isUnderlineText = false
        }
    }
}
