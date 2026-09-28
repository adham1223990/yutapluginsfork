package com.github.yutaplug.irc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R
import kotlin.math.roundToInt

/** Draws the reply connector down to the timestamp column. */
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
    private val path = Path()
    private var timestamp: TextView? = null
    private var reply: View? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(timestamp: TextView?, reply: View?) {
        this.timestamp = timestamp
        this.reply = reply
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val timestamp = timestamp?.takeIf { it.visibility != GONE } ?: return
        val reply = reply?.takeIf { it.visibility != GONE } ?: return
        val layout = timestamp.layout ?: return
        val bottom = relativeTop(timestamp) + timestamp.totalPaddingTop +
            layout.getLineBaseline(0) + timestamp.paint.fontMetrics.ascent -
            6f * resources.displayMetrics.density
        val x = paint.strokeWidth / 2f
        val replyCenter = relativeTop(reply) + reply.height / 2f
        if (bottom <= replyCenter) return
        val arrowGap = (reply.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd?.toFloat()
            ?: 4f * resources.displayMetrics.density
        val radius = minOf(
            4f * resources.displayMetrics.density,
            width - paint.strokeWidth - arrowGap,
            bottom - replyCenter,
        ).coerceAtLeast(0f)
        path.reset()
        path.moveTo(width - x - arrowGap, replyCenter)
        path.lineTo(x + radius, replyCenter)
        path.quadTo(x, replyCenter, x, replyCenter + radius)
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
