package com.github.yutaplug.markdownfix

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/** HSV controls retain the hue while choosing grayscale colors. */
internal class BulletColorPicker(context: Context, private val onColor: (String) -> Unit) : LinearLayout(context) {
    private val hsv = floatArrayOf(0f, 0f, 1f)
    private var opacityAlpha = 255
    private var syncing = false
    private val labels = mutableListOf<TextView>()
    private val bars = mutableListOf<SeekBar>()

    init {
        orientation = VERTICAL
        setPadding(0, MarkdownAppearance.dp(context, 16), 0, 0)
        listOf("Hue", "Saturation", "Brightness", "Opacity").forEachIndexed { index, name ->
            labels += DiscordSettingsUi.text(context).apply {
                textSize = 13f
                setTextColor(MarkdownAppearance.themedColor(context, "colorHeaderPrimary", Color.WHITE))
                addView(this)
            }
            bars += SeekBar(context, null, 0, com.lytefast.flexinput.R.i.UiKit_SeekBar).apply {
                max = if (index == 0) 360 else 100
                contentDescription = name
                thumbTintList = ColorStateList.valueOf(
                    MarkdownAppearance.themedColor(context, "colorBrand", Color.rgb(88, 101, 242)),
                )
                addView(this, LayoutParams(-1, MarkdownAppearance.dp(context, 40)))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (syncing || !fromUser) return
                        when (index) {
                            0 -> hsv[0] = progress.toFloat()
                            1 -> hsv[1] = progress / 100f
                            2 -> hsv[2] = progress / 100f
                            3 -> opacityAlpha = (progress * 255f / 100).toInt()
                        }
                        refresh()
                        val color = Color.HSVToColor(opacityAlpha, hsv)
                        onColor(
                            if (opacityAlpha ==
                                255
                            ) {
                                String.format(Locale.US, "#%06X", color and 0xFFFFFF)
                            } else {
                                String.format(Locale.US, "#%08X", color)
                            },
                        )
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) {}

                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            }
        }
        refresh()
    }

    fun setColor(value: String) {
        val normalized = MarkdownAppearance.normalizeColor(value) ?: return
        val color = Color.parseColor(normalized)
        val next = FloatArray(3)
        Color.colorToHSV(color, next)
        if (next[1] > 0f && next[2] > 0f) hsv[0] = next[0]
        if (next[2] > 0f) hsv[1] = next[1]
        hsv[2] = next[2]
        opacityAlpha = Color.alpha(color)
        refresh()
    }

    private fun refresh() {
        syncing = true
        val values =
            listOf(hsv[0].toInt(), (hsv[1] * 100).toInt(), (hsv[2] * 100).toInt(), (opacityAlpha * 100f / 255).toInt())
        val names = listOf("Hue", "Saturation", "Brightness", "Opacity")
        val gradients = listOf(
            intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED),
            intArrayOf(
                Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])),
                Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])),
            ),
            intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))),
            intArrayOf(Color.HSVToColor(0, hsv), Color.HSVToColor(hsv)),
        )
        bars.forEachIndexed { index, bar ->
            bar.progress = values[index]
            labels[index].text = "${names[index]} · ${values[index]}${if (index == 0) "°" else "%"}"
            bar.progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, gradients[index]).apply {
                cornerRadius = MarkdownAppearance.dp(context, 4).toFloat()
                setSize(0, MarkdownAppearance.dp(context, 8))
            }
        }
        syncing = false
    }
}
