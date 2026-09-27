package com.aliucord.plugins

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import android.view.View
import kotlin.math.sqrt

/** Keeps the entire recording, compacting older samples as recordings grow. */
internal class WaveFormView(
    context: Context,
) : View(context) {
    private val samples = WaveformSamples()
    private val recent = ArrayDeque<Int>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(185, 187, 190) }

    fun reset() {
        samples.reset()
        recent.clear()
        invalidate()
    }

    fun addAmplitude(amplitude: Int) {
        val wave = (sqrt(amplitude.coerceIn(0, 32767) / 32767.0) * 255).toInt().coerceIn(1, 255)
        recent.addLast(wave)
        if (recent.size > 300) recent.removeFirst()
        samples.add(wave)
        invalidate()
    }

    fun waveform(): String = Base64.encodeToString(samples.bytes(), Base64.NO_WRAP)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val step = 5f * density
        val barWidth = 3f * density
        recent.reversed().forEachIndexed { index, amplitude ->
            val left = width - step * (index + 1)
            if (left >= 0) {
                val halfHeight = maxOf(density, amplitude / 255f * height / 2f)
                canvas.drawRoundRect(
                    left,
                    height / 2f - halfHeight,
                    left + barWidth,
                    height / 2f + halfHeight,
                    barWidth,
                    barWidth,
                    paint,
                )
            }
        }
    }
}
