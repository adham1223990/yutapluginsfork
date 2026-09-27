package com.aliucord.plugins

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.widgets.BottomSheet

/** An action menu rather than a radio selector: each row starts a different flow. */
class VoiceModeSheet : BottomSheet() {
    internal var onSelect: ((Int) -> Unit)? = null
    private var selected = false

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        // An Android-restored sheet has no live action callback. Close it so it cannot
        // block opening a new menu or leave the user with buttons that do nothing.
        if (onSelect == null) {
            dismissAllowingStateLoss()
            return
        }
        val context = requireContext()
        val primary = themeColor(context, "colorHeaderPrimary", Color.WHITE)
        val muted = themeColor(context, "colorTextMuted", Color.LTGRAY)
        linearLayout.setPadding(dp(20), dp(12), dp(20), dp(20))
        linearLayout.addView(
            TextView(context).apply {
                text = "Send a voice message"
                textSize = 22f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(primary)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        linearLayout.addView(
            TextView(context).apply {
                text = "Record something new or choose an audio file."
                textSize = 14f
                setTextColor(muted)
                setPadding(0, dp(6), 0, dp(20))
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        addAction(1, "Record a message", "Use your microphone. Tap it again to finish and send.", "ic_mic_grey_24dp")
        addAction(2, "Choose an audio file", "Send an audio file from your device as a voice message.", "ic_file_upload_24dp")
        linearLayout.addView(
            TextView(context).apply {
                text = "Cancel"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(muted)
                isFocusable = true
                background =
                    RippleDrawable(
                        ColorStateList.valueOf(Color.argb(32, Color.red(primary), Color.green(primary), Color.blue(primary))),
                        null,
                        null,
                    )
                setOnClickListener { dismissAllowingStateLoss() }
            },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) },
        )
    }

    private fun addAction(
        choice: Int,
        title: String,
        description: String,
        iconName: String,
    ) {
        val context = requireContext()
        val accent = themeColor(context, "colorBrand", VoiceMessages.DEFAULT_BUTTON_COLOR)
        val primary = themeColor(context, "colorHeaderPrimary", Color.WHITE)
        val muted = themeColor(context, "colorTextMuted", Color.LTGRAY)
        val surface =
            GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(themeColor(context, "colorBackgroundSecondary", Color.rgb(47, 49, 54)))
            }
        val row =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(96)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                isFocusable = true
                contentDescription = "$title. $description"
                background =
                    RippleDrawable(
                        ColorStateList.valueOf(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent))),
                        surface,
                        null,
                    )
            }
        row.addView(
            ImageView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(dp(11), dp(11), dp(11), dp(11))
                val resource = Utils.getResId(iconName, "drawable")
                if (resource != 0) setImageDrawable(ContextCompat.getDrawable(context, resource)?.mutate()?.apply { setTint(accent) })
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.argb(28, Color.red(accent), Color.green(accent), Color.blue(accent)))
                    }
            },
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(14) },
        )
        row.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(
                    TextView(context).apply {
                        text = title
                        textSize = 16f
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(primary)
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
                addView(
                    TextView(context).apply {
                        text = description
                        textSize = 13f
                        setTextColor(muted)
                        setPadding(0, dp(4), 0, 0)
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        row.setOnClickListener {
            if (!selected) {
                val callback = onSelect ?: return@setOnClickListener
                selected = true
                callback(choice)
            }
        }
        linearLayout.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    override fun onDestroyView() {
        onSelect = null
        super.onDestroyView()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
