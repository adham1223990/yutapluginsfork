package com.aliucord.plugins

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.widgets.BottomSheet

/** An action menu rather than a radio selector: each row starts a different flow. */
class VoiceModeSheet : BottomSheet() {
    internal var onSelect: ((Int) -> Unit)? = null
    private var selected = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
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
        linearLayout.setPadding(0, 0, 0, dp(16))
        linearLayout.setBackgroundColor(themeColor(context, "colorBackgroundPrimary", Color.DKGRAY))
        linearLayout.addView(DiscordSettingsUi.title(context, "Send a voice message"))
        linearLayout.addView(
            DiscordSettingsUi.text(context).apply {
                text = "Record something new or choose an audio file."
                textSize = 14f
                setTextColor(muted)
                setPadding(dp(16), 0, dp(16), dp(16))
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        addAction(1, "Record a message", "Use your microphone. Tap it again to finish and send.", "ic_mic_grey_24dp")
        addAction(
            2,
            "Choose an audio file",
            "Send an audio file from your device as a voice message.",
            "ic_file_upload_24dp",
        )
        linearLayout.addView(
            DiscordSettingsUi.text(context).apply {
                text = "Cancel"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(muted)
                isFocusable = true
                background =
                    RippleDrawable(
                        ColorStateList.valueOf(
                            Color.argb(32, Color.red(primary), Color.green(primary), Color.blue(primary)),
                        ),
                        null,
                        android.graphics.drawable.ColorDrawable(Color.WHITE),
                    )
                setOnClickListener { dismissAllowingStateLoss() }
            },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) },
        )
    }

    private fun addAction(choice: Int, title: String, description: String, iconName: String) {
        val context = requireContext()
        val accent = themeColor(context, "colorInteractiveNormal", Color.LTGRAY)
        val primary = themeColor(context, "colorHeaderPrimary", Color.WHITE)
        val muted = themeColor(context, "colorTextMuted", Color.LTGRAY)
        val row =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                isFocusable = true
                contentDescription = "$title. $description"
                background =
                    RippleDrawable(
                        ColorStateList.valueOf(
                            Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)),
                        ),
                        null,
                        android.graphics.drawable.ColorDrawable(Color.WHITE),
                    )
            }
        row.addView(
            ImageView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                val resource = Utils.getResId(iconName, "drawable")
                if (resource !=
                    0
                ) {
                    setImageDrawable(
                        ContextCompat.getDrawable(context, resource)?.mutate()?.apply { setTint(accent) },
                    )
                }
            },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) },
        )
        row.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(
                    DiscordSettingsUi.text(context).apply {
                        text = title
                        textSize = 16f
                        setTextColor(primary)
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
                addView(
                    DiscordSettingsUi.text(context).apply {
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
        linearLayout.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    override fun onDestroyView() {
        onSelect = null
        super.onDestroyView()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
