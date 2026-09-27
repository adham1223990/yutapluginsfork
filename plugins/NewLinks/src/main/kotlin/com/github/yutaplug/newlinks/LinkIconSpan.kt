package com.github.yutaplug.newlinks

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.style.ReplacementSpan
import kotlin.math.ceil

/** Replacement spans must paint their own background and foreground on Android. */
internal class LinkIconSpan(
    private val kind: Kind,
    private val foreground: Int,
    private val background: Int,
) : ReplacementSpan() {
    enum class Kind { MESSAGE, FILE }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        ceil(paint.textSize * 0.82f).toInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        canvas.drawRect(x, top.toFloat(), x + getSize(paint, text, start, end, null), bottom.toFloat(), highlight)
        val size = paint.textSize * 0.78f
        val center = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val fill = Paint(paint).apply {
            color = foreground
            style = Paint.Style.FILL
        }
        if (kind == Kind.FILE) {
            drawFile(canvas, x, center, size, fill, highlight)
        } else {
            drawMessage(canvas, x, center, size, fill)
        }
    }

    private fun drawMessage(canvas: Canvas, x: Float, center: Float, size: Float, fill: Paint) {
        val upper = center - size * 0.4f
        val lower = center + size * 0.3f
        canvas.drawRoundRect(x, upper, x + size, lower, size * 0.07f, size * 0.07f, fill)
        val tail = Path().apply {
            moveTo(x + size * 0.2f, lower - size * 0.05f)
            lineTo(x + size * 0.2f, lower + size * 0.2f)
            lineTo(x + size * 0.43f, lower - size * 0.05f)
            close()
        }
        canvas.drawPath(tail, fill)
    }

    private fun drawFile(canvas: Canvas, x: Float, center: Float, size: Float, fill: Paint, highlight: Paint) {
        val page = Path().apply {
            moveTo(x + size * 0.14f, center - size * 0.46f)
            lineTo(x + size * 0.54f, center - size * 0.46f)
            lineTo(x + size * 0.78f, center - size * 0.22f)
            lineTo(x + size * 0.78f, center + size * 0.46f)
            lineTo(x + size * 0.14f, center + size * 0.46f)
            close()
        }
        canvas.drawPath(page, fill)
        highlight.style = Paint.Style.STROKE
        highlight.strokeWidth = size * 0.06f
        val fold = Path().apply {
            moveTo(x + size * 0.54f, center - size * 0.46f)
            lineTo(x + size * 0.54f, center - size * 0.22f)
            lineTo(x + size * 0.78f, center - size * 0.22f)
        }
        canvas.drawPath(fold, highlight)
        canvas.drawLine(x + size * 0.28f, center + size * 0.04f, x + size * 0.64f, center + size * 0.04f, highlight)
        canvas.drawLine(x + size * 0.28f, center + size * 0.22f, x + size * 0.64f, center + size * 0.22f, highlight)
    }
}
