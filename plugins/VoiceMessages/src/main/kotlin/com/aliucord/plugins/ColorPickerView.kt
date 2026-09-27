package com.aliucord.plugins

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

internal class ColorPickerView(
    context: Context,
    initialColor: Int,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = FloatArray(3)
    private val barHeight = 32 * resources.displayMetrics.density
    private var draggingHue = false
    var onColorChanged: ((Int) -> Unit)? = null
    var color: Int
        get() = Color.HSVToColor(hsv)
        set(value) {
            Color.colorToHSV(value, hsv)
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        color = initialColor
        contentDescription = "Color picker: saturation and brightness above, hue below"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = (height - barHeight).coerceAtLeast(1f)
        paint.shader =
            LinearGradient(
                0f,
                0f,
                w,
                0f,
                Color.WHITE,
                Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)),
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, 0f, h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader =
            LinearGradient(
                0f,
                h,
                w,
                h,
                intArrayOf(
                    Color.RED,
                    Color.YELLOW,
                    Color.GREEN,
                    Color.CYAN,
                    Color.BLUE,
                    Color.MAGENTA,
                    Color.RED,
                ),
                null,
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(0f, h, w, height.toFloat(), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLACK
        paint.strokeWidth = 3 * resources.displayMetrics.density
        canvas.drawCircle(hsv[1] * w, (1 - hsv[2]) * h, 8 * resources.displayMetrics.density, paint)
        canvas.drawLine(hsv[0] / 360 * w, h, hsv[0] / 360 * w, height.toFloat(), paint)
        paint.color = Color.WHITE
        paint.strokeWidth = resources.displayMetrics.density
        canvas.drawCircle(hsv[1] * w, (1 - hsv[2]) * h, 8 * resources.displayMetrics.density, paint)
        canvas.drawLine(hsv[0] / 360 * w, h, hsv[0] / 360 * w, height.toFloat(), paint)
        paint.style = Paint.Style.FILL
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val h = (height - barHeight).coerceAtLeast(1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingHue = event.y >= h
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                Unit.a
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            else -> {
                return true
            }
        }
        val x = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        if (draggingHue) {
            hsv[0] = x * 360
        } else {
            hsv[1] = x
            hsv[2] = 1 - (event.y / h).coerceIn(0f, 1f)
        }
        invalidate()
        onColorChanged?.invoke(color)
        return true
    }
}

