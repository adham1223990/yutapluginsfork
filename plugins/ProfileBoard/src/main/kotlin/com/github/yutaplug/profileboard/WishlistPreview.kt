package com.github.yutaplug.profileboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Animatable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import b.f.g.c.c
import com.aliucord.Utils
import com.discord.utilities.images.MGImages
import com.facebook.drawee.drawable.`ScalingUtils$ScaleType`
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.image.ImageInfo

/** Static shop previews with the same avatar, profile and list contexts as Discord. */
internal class WishlistPreview(context: Context, private val item: WishlistItem, loaded: () -> Unit) : FrameLayout(context) {
    private var ready = false
    private val backdrop = PreviewBackdrop(context, item.type).apply { visibility = View.INVISIBLE }

    init {
        // Fresco attaches its controller when the view draws. Hiding this host until
        // onFinalImageSet prevents that callback from ever arriving on Discord 126.
        addView(backdrop, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun showPreview() {
            if (!ready) {
                ready = true
                backdrop.visibility = View.VISIBLE
                loaded()
            }
        }
        if (item.type == 3 && item.layers.isNotEmpty()) {
            for (layer in item.layers) {
                val image = SimpleDraweeView(context)
                image.hierarchy.n(`ScalingUtils$ScaleType`.a)
                addView(image, LayoutParams(1, 1))
                loadImage(image, layer.image) { info ->
                    showPreview()
                    image.tag = if (info != null && info.width > 0) info.height.toFloat() / info.width else 0.75f
                    positionLayer(image, layer)
                }
            }
        } else if (item.image != null) {
            val image = SimpleDraweeView(context)
            image.hierarchy.n(if (item.type == 2) `ScalingUtils$ScaleType`.i else `ScalingUtils$ScaleType`.e)
            addView(image, LayoutParams(1, 1))
            loadImage(image, item.image) { showPreview() }
        }
        if (item.type == 2) {
            addView(PreviewBackdrop(context, 2, true), LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
    }

    private fun loadImage(image: SimpleDraweeView, url: String, loaded: (ImageInfo?) -> Unit) {
        MGImages.setImage(image, listOf(url), 0, 0, false, null,
            MGImages.AlwaysUpdateChangeDetector.INSTANCE, object : c<ImageInfo>() {
                override fun onFinalImageSet(id: String?, info: ImageInfo?, animatable: Animatable?) {
                    image.post { loaded(info) }
                }
            })
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        var index = 1
        while (index < childCount) {
            val image = getChildAt(index++) as? SimpleDraweeView ?: continue
            if (item.type == 3 && item.layers.isNotEmpty()) {
                positionLayer(image, item.layers[index - 2])
            } else {
                val params = image.layoutParams as LayoutParams
                params.gravity = Gravity.CENTER
                params.width = when (item.type) { 0 -> (width * 0.68f).toInt(); 2 -> (width * 0.92f).toInt(); else -> width }
                params.height = when (item.type) { 0 -> (height * 0.68f).toInt(); 2 -> (height * 0.21f).toInt(); else -> height }
                image.layoutParams = params
                if (item.type == 2) MGImages.setRoundingParams(image, width * 0.04f, false, null, null, null)
            }
        }
    }

    private fun positionLayer(image: SimpleDraweeView, layer: WishlistLayer) {
        if (width == 0) return
        val params = image.layoutParams as LayoutParams
        params.width = (width * 0.76f).toInt()
        params.height = if (layer.anchor == 2) (height * 0.77f).toInt()
            else (params.width * (image.tag as? Float ?: 0.3f)).toInt()
        params.gravity = Gravity.CENTER_HORIZONTAL or if (layer.anchor == 1) Gravity.BOTTOM else Gravity.TOP
        params.topMargin = (height * 0.1f).toInt()
        params.bottomMargin = (height * 0.1f).toInt()
        image.layoutParams = params
    }
}

/** Context beneath transparent collectible art, using Discord's native placeholder avatar. */
private class PreviewBackdrop(context: Context, private val type: Int, private val foreground: Boolean = false) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatar = context.getDrawable(Utils.getResId("asset_default_avatar_80dp", "drawable"))?.mutate()?.apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        alpha = 110
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = width.toFloat()
        canvas.save()
        canvas.scale(side, height.toFloat())
        if (foreground) {
            drawAvatar(canvas, 0.095f, 0.435f, 0.13f)
            paint.color = 0x665C5E66
            canvas.drawRoundRect(0.26f, 0.48f, 0.47f, 0.52f, 0.02f, 0.02f, paint)
            canvas.restore()
            return
        }
        paint.color = 0xFF2B2D31.toInt()
        if (type == 0) {
            canvas.drawCircle(0.5f, 0.5f, 0.24f, paint)
            drawAvatar(canvas, 0.26f, 0.26f, 0.48f)
        } else if (type == 1 || type == 3) {
            val left = if (type == 3) 0.17f else 0f
            val top = if (type == 3) 0.14f else 0f
            val right = 1f - left
            val bottom = 1f - top
            canvas.drawRoundRect(left, top, right, bottom, 0.025f, 0.025f, paint)
            paint.color = 0xFF222327.toInt()
            canvas.drawRect(left, top + (bottom - top) * 0.33f, right, bottom, paint)
            val diameter = (right - left) * 0.29f
            drawAvatar(canvas, left + 0.04f, top + (bottom - top) * 0.24f, diameter)
            paint.color = 0xFF383A40.toInt()
            var row = 0
            while (row < 5) {
                val y = top + (bottom - top) * (0.57f + row * 0.077f)
                val length = (right - left) * if (row == 0 || row == 3) 0.32f else 0.67f
                canvas.drawRoundRect(left + 0.06f, y, left + 0.06f + length, y + 0.032f, 0.016f, 0.016f, paint)
                row++
            }
        } else if (type == 2) {
            paint.color = 0xFF41434A.toInt()
            var row = 0
            while (row < 5) {
                val y = 0.085f + row * 0.2f
                canvas.drawCircle(0.16f, y, 0.066f, paint)
                val length = if (row % 2 == 0) 0.18f else 0.25f
                canvas.drawRoundRect(0.26f, y - 0.02f, 0.26f + length, y + 0.02f, 0.02f, 0.02f, paint)
                row++
            }
        }
        canvas.restore()
    }

    private fun drawAvatar(canvas: Canvas, x: Float, y: Float, size: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(size / 240f, size / 240f)
        avatar?.setBounds(0, 0, 240, 240)
        avatar?.draw(canvas)
        canvas.restore()
    }
}

internal class WishlistOwnedOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0x44000000)
        paint.color = android.graphics.Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width * 0.026f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val path = Path()
        path.moveTo(width * 0.4f, height * 0.51f)
        path.lineTo(width * 0.47f, height * 0.58f)
        path.lineTo(width * 0.62f, height * 0.43f)
        canvas.drawPath(path, paint)
    }
}
