package com.github.yutaplug.markdownfix

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting
import java.util.Locale

class MarkdownFixSettings(private val settings: SettingsAPI, private val plugin: MarkdownFix) : BottomSheet() {
    private lateinit var preview: TextView
    private lateinit var customColor: CheckedSetting
    private lateinit var colorRow: LinearLayout
    private lateinit var colorValue: TextView
    private val sizeValues = mutableMapOf<MarkdownAppearance.TextSize, TextView>()

    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val context = requireContext()
        sizeValues.clear()
        linearLayout.setPadding(dp(16), dp(8), dp(16), dp(24))
        addView(text("MarkdownFix", 22, "colorHeaderPrimary").apply { setTypeface(typeface, Typeface.BOLD) })
        addView(
            text("Changes update the preview and visible messages immediately.", 14, "colorTextMuted").apply {
                setPadding(0, dp(6), 0, dp(12))
            },
        )
        preview = text("", 16, "colorTextNormal").apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(color("colorBackgroundSecondary", Color.rgb(47, 49, 54)))
                cornerRadius = dp(8).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Markdown appearance preview"
        }
        addView(preview)

        section("Text sizes")
        for (size in MarkdownAppearance.sizes) {
            val (row, value) = actionRow(size.title, size.example, { showScaleDialog(size) })
            sizeValues[size] = value
            addView(row)
        }

        section("Bullet lists")
        customColor = Utils
            .createCheckedSetting(
                context,
                CheckedSetting.ViewType.SWITCH,
                "Custom bullet color",
                "Use your own color instead of the theme color.",
            ).apply {
                isChecked = settings.getBool(MarkdownAppearance.CUSTOM_BULLET_COLOR, false)
                setOnCheckedListener {
                    settings.setBool(MarkdownAppearance.CUSTOM_BULLET_COLOR, it)
                    changed()
                }
            }
        addView(customColor)
        val (row, value) = actionRow("Bullet color", "#RRGGBB or #AARRGGBB", ::showColorDialog)
        colorRow = row
        colorValue = value
        addView(colorRow)
        section("Defaults")
        addView(actionRow("Reset appearance", "Restore all text sizes and the theme bullet color.", ::reset).first)
        updateUi()
    }

    private fun changed() {
        updateUi()
        plugin.refreshAppearance()
    }

    private fun updateUi() {
        for ((size, value) in sizeValues) value.text = "${format(MarkdownAppearance.scale(settings, size))}×"
        val custom = settings.getBool(MarkdownAppearance.CUSTOM_BULLET_COLOR, false)
        colorRow.isEnabled = custom
        colorRow.alpha = if (custom) 1f else 0.45f
        colorValue.text = currentColor()
        colorValue.compoundDrawablePadding = dp(6)
        colorValue.setCompoundDrawables(
            GradientDrawable().apply {
                setColor(Color.parseColor(currentColor()))
                setStroke(dp(1), color("colorTextMuted", Color.GRAY))
                cornerRadius = dp(3).toFloat()
                setBounds(0, 0, dp(16), dp(16))
            },
            null,
            null,
            null,
        )
        preview.text = buildPreview()
    }

    private fun buildPreview(): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        for ((index, size) in MarkdownAppearance.sizes.withIndex()) {
            val start = builder.length
            builder.append(if (index == 3) "Subtext" else size.title)
            builder.setSpan(MarkdownSizeSpan(settings, size), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(
                if (index == 3) ForegroundColorSpan(color("colorTextMuted", Color.GRAY)) else StyleSpan(Typeface.BOLD),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.append('\n')
        }
        val bulletStart = builder.length
        builder.append("Compact bullet list\n")
        builder.setSpan(BulletMarker(1), bulletStart, builder.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val quoteStart = builder.length
        builder.append("A quoted bullet")
        builder.setSpan(QuoteMarker(), quoteStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(BulletMarker(1), quoteStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        MarkdownBlocks.apply(builder, requireContext(), settings)
        return builder
    }

    private fun showScaleDialog(size: MarkdownAppearance.TextSize) {
        val input = input(
            format(MarkdownAppearance.scale(settings, size)),
            "1.00",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        )
        editDialog(size.title, "Scale from 0.1 to 3.0. 1.0 is normal text size.", input, {
            val value = input.text.toString().trim().replace(',', '.').toFloatOrNull()
            if (value == null || !value.isFinite() || value !in 0.1f..3f) {
                input.error = "Enter a number from 0.1 to 3.0"
                false
            } else {
                settings.setString(size.key, value.toString())
                true
            }
        }, { settings.setString(size.key, size.default.toString()) })
    }

    private fun showColorDialog() {
        val input = input(
            currentColor(),
            "#5865F2",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
        editDialog("Bullet color", "Enter six hex digits, or eight to include opacity.", input, {
            val value = MarkdownAppearance.normalizeColor(input.text.toString())
            if (value == null) {
                input.error = "Enter #RRGGBB or #AARRGGBB"
                false
            } else {
                settings.setString(MarkdownAppearance.BULLET_COLOR, value)
                true
            }
        }, { settings.setString(MarkdownAppearance.BULLET_COLOR, MarkdownAppearance.DEFAULT_BULLET_COLOR) })
    }

    private fun editDialog(title: String, message: String, input: EditText, save: () -> Boolean, reset: () -> Unit) {
        val holder = LinearLayout(requireContext()).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog
            .Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setView(holder)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(color("colorBackgroundPrimary", Color.rgb(54, 57, 63))))
            val titleId = resources.getIdentifier("alertTitle", "id", "android")
            dialog.findViewById<TextView>(titleId)?.setTextColor(color("colorHeaderPrimary", Color.WHITE))
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(color("colorTextNormal", Color.WHITE))
            for (which in listOf(
                AlertDialog.BUTTON_POSITIVE,
                AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL,
            )) {
                dialog.getButton(which).setTextColor(color("colorBrand", Color.rgb(88, 101, 242)))
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (save()) {
                    changed()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                reset()
                changed()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun input(value: String, hint: String, type: Int): EditText = EditText(requireContext()).apply {
        setSingleLine(true)
        setSelectAllOnFocus(true)
        inputType = type
        setText(value)
        this.hint = hint
        setTextColor(color("colorTextNormal", Color.WHITE))
        setHintTextColor(color("colorTextMuted", Color.LTGRAY))
    }

    private fun actionRow(title: String, subtitle: String, action: () -> Unit): Pair<LinearLayout, TextView> {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val attribute = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, attribute, true) &&
                attribute.resourceId != 0
            ) {
                background = context.getDrawable(attribute.resourceId)
            }
            setOnClickListener { action() }
        }
        val labels = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16, "colorHeaderPrimary"))
            addView(text(subtitle, 13, "colorTextMuted").apply { setPadding(0, dp(3), 0, 0) })
        }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val value = text("", 14, "colorTextMuted").apply {
            setPadding(dp(12), 0, 0, 0)
            gravity = Gravity.END
        }
        row.addView(value)
        return row to value
    }

    private fun section(title: String) {
        addView(
            text(title.uppercase(Locale.ROOT), 12, "colorTextMuted").apply {
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.06f
                setPadding(dp(12), dp(20), 0, dp(6))
            },
        )
    }

    private fun reset() {
        MarkdownAppearance.sizes.forEach { settings.setString(it.key, it.default.toString()) }
        settings.setString(MarkdownAppearance.BULLET_COLOR, MarkdownAppearance.DEFAULT_BULLET_COLOR)
        settings.setBool(MarkdownAppearance.CUSTOM_BULLET_COLOR, false)
        customColor.isChecked = false
        changed()
    }

    private fun currentColor(): String = MarkdownAppearance.normalizeColor(
        settings.getString(MarkdownAppearance.BULLET_COLOR, MarkdownAppearance.DEFAULT_BULLET_COLOR),
    )
        ?: MarkdownAppearance.DEFAULT_BULLET_COLOR

    private fun text(value: String, size: Int, attribute: String): TextView = TextView(requireContext()).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        setTextColor(color(attribute, Color.LTGRAY))
    }

    private fun color(attribute: String, fallback: Int): Int =
        MarkdownAppearance.themedColor(requireContext(), attribute, fallback)

    private fun dp(value: Int): Int = MarkdownAppearance.dp(requireContext(), value)

    private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)
}
