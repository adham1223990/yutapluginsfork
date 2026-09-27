package com.github.yutaplug.irc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.Spanned
import android.text.TextPaint
import android.view.View
import android.widget.TextView
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R
import kotlin.math.roundToInt

/** One stroke for the reply elbow and the line beside the author. */
internal class MessageSpine(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val muted = ColorCompat.getThemedColor(context, R.b.colorTextMuted)
        color = Color.argb(
            (Color.alpha(muted) * 0.45f).roundToInt(),
            Color.red(muted),
            Color.green(muted),
            Color.blue(muted),
        )
        strokeWidth = context.resources.displayMetrics.density.coerceAtLeast(1f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val authorPaint = TextPaint()
    private val path = Path()
    private var text: TextView? = null
    private var reply: View? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(text: TextView?, reply: View?) {
        this.text = text
        this.reply = reply
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val text = text?.takeIf { it.visibility != GONE } ?: return
        val layout = text.layout ?: return
        val author = InlineAuthorText.find(text.text)?.takeIf { it.nameLength > 0 } ?: return
        val content = text.text as Spanned
        val authorStart = content.getSpanStart(author)
        val authorEnd = (authorStart + author.nameLength).coerceAtMost(content.length)
        if (authorStart < 0 || authorEnd <= authorStart) return

        val firstLine = layout.getLineForOffset(authorStart)
        val lastLine = layout.getLineForOffset(authorEnd - 1)
        authorPaint.set(text.paint)
        author.updateMeasureState(authorPaint)
        val metrics = authorPaint.fontMetrics
        val textTop = relativeTop(text) + text.totalPaddingTop
        val top = textTop + layout.getLineBaseline(firstLine) + metrics.ascent
        val bottom = textTop + layout.getLineBaseline(lastLine) + metrics.descent
        val x = paint.strokeWidth / 2f

        path.reset()
        val reply = reply?.takeIf { it.visibility != GONE }
        if (reply != null) {
            val replyCenter = relativeTop(reply) + reply.height / 2f
            val radius = minOf(
                4f * resources.displayMetrics.density,
                width - paint.strokeWidth,
                bottom - replyCenter,
            ).coerceAtLeast(0f)
            path.moveTo(width - x, replyCenter)
            path.lineTo(x + radius, replyCenter)
            path.quadTo(x, replyCenter, x, replyCenter + radius)
        } else {
            path.moveTo(x, top)
        }
        path.lineTo(x, bottom)
        canvas.drawPath(path, paint)
    }

    private fun relativeTop(view: View): Int {
        var top = view.top - this.top
        var ancestor = view.parent
        while (ancestor is View && ancestor !== parent) {
            top += ancestor.top
            ancestor = ancestor.parent
        }
        return top
    }
}
