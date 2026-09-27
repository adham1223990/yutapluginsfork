package com.github.yutaplug.bettermessagelogger

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat

/** Shared, theme-aware spacing and surfaces for settings and history. */
internal class LoggerUi(val context: Context) {
    val primary get() = theme("colorHeaderPrimary", Color.WHITE)
    val muted get() = theme("colorTextMuted", Color.LTGRAY)
    val brand get() = theme("colorBrand", Color.rgb(88, 101, 242))
    val surface get() = theme("colorBackgroundSecondary", Color.rgb(47, 49, 54))
    val background get() = theme("colorBackgroundPrimary", Color.rgb(54, 57, 63))
    val danger get() = theme("colorStatusDanger", Color.rgb(237, 66, 69))

    fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun theme(attribute: String, fallback: Int): Int = Utils.getResId(attribute, "attr").let {
        if (it == 0) fallback else ColorCompat.getThemedColor(context, it)
    }

    fun text(value: CharSequence, size: Float = 14f, color: Int = primary) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
    }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun dialogContent() = column().apply {
        setPadding(dp(20), dp(4), dp(20), dp(12))
        isFocusableInTouchMode = true
    }

    fun input(placeholder: String) = EditText(context).apply {
        hint = placeholder
        setTextColor(primary)
        setHintTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setSingleLine(true)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minimumHeight = dp(52)
        background = StateListDrawable().apply {
            fun outline(color: Int) = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), color)
            }
            addState(intArrayOf(android.R.attr.state_focused), outline(brand))
            addState(intArrayOf(), outline(muted))
        }
    }

    fun scroll(content: View, fraction: Float = 0.35f): ScrollView = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maximum = (resources.displayMetrics.heightPixels * fraction).toInt()
            val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                maximum
            } else {
                minOf(maximum, MeasureSpec.getSize(heightMeasureSpec))
            }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        }
    }.apply {
        addView(content)
        isFillViewport = false
    }

    fun card() = column().apply {
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(12).toFloat()
        }
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    fun divider(parent: LinearLayout) {
        parent.addView(
            View(context).apply {
                setBackgroundColor(muted)
                alpha = 0.12f
            },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            },
        )
    }

    fun action(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        color: Int = primary,
        click: () -> Unit,
    ): TextView {
        val row = column().apply {
            setPadding(dp(8), dp(10), dp(8), dp(10))
            minimumHeight = dp(64)
            isFocusable = true
            contentDescription = "$title. $subtitle"
        }
        row.addView(text(title, 16f, color))
        val caption = text(subtitle, 13f, muted).apply { setPadding(0, dp(4), 0, 0) }
        row.addView(caption)
        // A selectable background works on Android 5, unlike View.foreground.
        val value = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true) &&
            value.resourceId != 0
        ) {
            row.background = context.getDrawable(value.resourceId)
        }
        row.setOnClickListener { click() }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return caption
    }

    @Suppress("DEPRECATION") // ADJUST_RESIZE is needed on the Android 5+ versions this plugin supports.
    fun style(dialog: AlertDialog) {
        // Inflate and theme the window before it is attached; onShow is too late for its first frame.
        dialog.create()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        dialog.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(background)
                cornerRadius = dp(16).toFloat()
            },
        )
        val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) dialog.findViewById<TextView>(titleId)?.setTextColor(primary)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(primary)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach {
            dialog.getButton(it)?.apply {
                setTextColor(brand)
                isAllCaps = false
            }
        }
    }

    fun heading(title: String) = text(title, 17f).apply { setTypeface(Typeface.DEFAULT, Typeface.BOLD) }

    fun smallButton(title: String, color: Int = brand, click: () -> Unit) = text(title, 13f, color).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minimumHeight = dp(48)
        isFocusable = true
        setOnClickListener { click() }
    }
}
