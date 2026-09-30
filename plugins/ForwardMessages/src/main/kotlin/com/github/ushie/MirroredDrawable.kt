package com.github.ushie

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable

class MirroredDrawable(private val base: Drawable) : Drawable() {
    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        base.bounds = bounds
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val saved = canvas.save()
        canvas.scale(-1f, 1f, bounds.exactCenterX(), bounds.exactCenterY())
        base.draw(canvas)
        canvas.restoreToCount(saved)
    }

    override fun getIntrinsicWidth() = base.intrinsicWidth

    override fun getIntrinsicHeight() = base.intrinsicHeight

    override fun setAlpha(alpha: Int) {
        base.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        base.colorFilter = colorFilter
    }

    override fun setTint(tintColor: Int) {
        base.setTint(tintColor)
    }

    override fun setTintList(tint: ColorStateList?) {
        base.setTintList(tint)
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        base.setTintMode(tintMode)
    }

    override fun onStateChange(state: IntArray): Boolean = base.setState(state).also { if (it) invalidateSelf() }

    override fun isStateful() = base.isStateful

    @Suppress("DEPRECATION")
    override fun getOpacity() = base.opacity.takeUnless { it == PixelFormat.UNKNOWN } ?: PixelFormat.TRANSLUCENT
}
