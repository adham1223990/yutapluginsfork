package com.github.yutaplug.superreactions

import android.graphics.*
import android.graphics.drawable.Drawable

/** A clipped reflection that stays inexpensive even on a full reaction row. */
internal class SuperReactionDrawable(private val base: Drawable?, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = RectF()
    private val clip = Path()
    private var reflection: LinearGradient? = null
    private var color = 0
    private var owned = false
    private var pending = false
    private var pressed = false
    private var opacity = 255

    init {
        base?.state = intArrayOf()
        base?.jumpToCurrentState()
    }

    fun update(color: Int, owned: Boolean, pending: Boolean) {
        if (this.color == color && this.owned == owned && this.pending == pending) return
        this.color = color
        this.owned = owned
        this.pending = pending
        rebuildReflection()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        base?.bounds = bounds
        pill.set(bounds)
        pill.inset(density * 0.75f, density * 0.75f)
        clip.reset()
        clip.addRoundRect(pill, 6 * density, 6 * density, Path.Direction.CW)
        rebuildReflection()
    }

    private fun rebuildReflection() {
        if (pill.width() <= 0) return
        val light = Color.rgb(
            (Color.red(color) + 255) / 2,
            (Color.green(color) + 255) / 2,
            (Color.blue(color) + 255) / 2,
        )
        reflection = LinearGradient(
            pill.left,
            pill.top,
            pill.right,
            pill.bottom,
            intArrayOf(0, 0, 28, 80, 28, 0, 0).map { tint(light, it) }.toIntArray(),
            floatArrayOf(0f, 0.22f, 0.34f, 0.43f, 0.52f, 0.64f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(clip)
        base?.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = tint(
            color,
            when {
                pressed -> 90
                owned -> 58
                else -> 28
            },
        )
        canvas.drawRect(pill, paint)
        paint.shader = reflection
        paint.alpha = if (pending) 128 else 255
        canvas.drawRect(pill, paint)
        paint.shader = null
        canvas.restoreToCount(save)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * if (owned) 1.5f else 1f
        paint.color = tint(
            color,
            when {
                pending -> 90
                owned -> 235
                else -> 165
            },
        )
        canvas.drawRoundRect(pill, 6 * density, 6 * density, paint)
    }

    private fun tint(color: Int, alpha: Int) = Color.argb(
        alpha * opacity / 255,
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val next = android.R.attr.state_pressed in state
        if (pressed == next) return false
        pressed = next
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha
        base?.alpha = alpha
        rebuildReflection()
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
        base?.colorFilter = filter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
