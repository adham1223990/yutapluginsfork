package com.github.yutaplug.markdownfix

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A themed editor shared by the scale and bullet-color settings. */
internal object MarkdownEditDialog {
    fun show(
        context: Context,
        title: String,
        description: String,
        label: String,
        value: String,
        hint: String,
        inputType: Int,
        colorPreview: Boolean = false,
        validate: (String) -> String?,
        save: (String) -> Unit,
        reset: () -> Unit,
    ) {
        fun dp(value: Int) = MarkdownAppearance.dp(context, value)

        fun color(attribute: String, fallback: Int) = MarkdownAppearance.themedColor(context, attribute, fallback)

        fun shape(fill: Int, radius: Int = 8) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
        }

        fun text(value: String, size: Float, tint: Int) = TextView(context).apply {
            text = value
            textSize = size
            setTextColor(tint)
        }

        val primary = color("colorHeaderPrimary", Color.WHITE)
        val muted = color("colorTextMuted", Color.LTGRAY)
        val brand = color("colorBrand", Color.rgb(88, 101, 242))
        val surface = color("colorBackgroundSecondary", Color.rgb(47, 49, 54))
        val danger = color("colorTextDanger", Color.rgb(237, 66, 69))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
        }
        content.addView(text(title, 22f, primary).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(text(description, 14f, muted).apply { setPadding(0, dp(8), 0, dp(20)) })
        content.addView(text(label, 13f, primary).apply { setPadding(0, 0, 0, dp(8)) })

        val fieldBackground = shape(surface)
        val field = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            background = fieldBackground
        }
        val input = EditText(context).apply {
            setSingleLine(true)
            setSelectAllOnFocus(true)
            this.inputType = inputType
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(value)
            this.hint = hint
            textSize = 17f
            background = null
            setPadding(0, dp(8), 0, dp(8))
            setTextColor(color("colorTextNormal", Color.WHITE))
            setHintTextColor(muted)
            contentDescription = label
        }
        field.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val swatch = if (colorPreview) {
            View(context).apply {
                contentDescription = "Selected bullet color"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                field.addView(this, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(12) })
            }
        } else {
            null
        }
        content.addView(field, LinearLayout.LayoutParams(-1, -2))
        val error = text("", 13f, danger).apply {
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(error)
        val picker = if (colorPreview) {
            BulletColorPicker(context) { selected -> input.setText(selected) }.also {
                it.setColor(value)
                content.addView(it, LinearLayout.LayoutParams(-1, -2))
            }
        } else {
            null
        }

        fun updateField() {
            fieldBackground.setStroke(
                dp(1),
                if (error.visibility ==
                    View.VISIBLE
                ) {
                    danger
                } else if (input.hasFocus()) {
                    brand
                } else {
                    Color.TRANSPARENT
                },
            )
            swatch?.background = shape(
                MarkdownAppearance.normalizeColor(input.text.toString())?.let(Color::parseColor) ?: Color.TRANSPARENT,
            ).apply { setStroke(dp(1), muted) }
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            background = shape(color("colorBackgroundPrimary", Color.rgb(54, 57, 63)), 16)
            clipToOutline = true
            addView(content)
        }
        val dialog = AlertDialog.Builder(context).create().apply { setView(scroll, 0, 0, 0, 0) }

        fun submit() {
            val current = input.text.toString().trim()
            val problem = validate(current)
            if (problem != null) {
                error.text = problem
                error.visibility = View.VISIBLE
                input.requestFocus()
                updateField()
                return
            }
            save(current)
            dialog.dismiss()
        }

        fun button(title: String, fill: Int, tint: Int, action: () -> Unit) = text(title, 15f, tint).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(fill), shape(Color.WHITE))
            isFocusable = true
            setOnClickListener { action() }
        }

        content.addView(
            button("Reset to default", Color.TRANSPARENT, brand) {
                reset()
                dialog.dismiss()
            },
            LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(8)
                gravity = Gravity.START
            },
        )
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Cancel", surface, primary, dialog::dismiss), LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(
            button("Save", brand, Color.WHITE, ::submit),
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart =
                    dp(12)
            },
        )
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        input.setOnFocusChangeListener { _, _ -> updateField() }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                error.visibility = View.GONE
                picker?.setColor(input.text.toString())
                updateField()
            }

            override fun afterTextChanged(text: Editable?) {}
        })
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        // Inflate AlertDialog's content before sizing: its onCreate installs the default window width.
        // Configure the final bounds before the window is attached to avoid a second visible layout.
        dialog.create()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf(dp(420), context.resources.displayMetrics.widthPixels - dp(40)), -2)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            )
        }
        input.requestFocus()
        updateField()
        dialog.show()
    }
}
