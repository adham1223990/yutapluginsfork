package com.github.yutaplug.markdownfix

import android.graphics.Color
import android.graphics.Typeface
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
        MarkdownEditDialog.show(
            context = requireContext(),
            title = size.title,
            description = "Choose a scale from 0.1 to 3.0. Use 1.0 for normal text size.",
            label = "Text scale",
            value = format(MarkdownAppearance.scale(settings, size)),
            hint = "1.00",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            validate = { raw ->
                val value = raw.replace(',', '.').toFloatOrNull()
                if (value == null || !value.isFinite() || value !in 0.1f..3f) "Enter a number from 0.1 to 3.0" else null
            },
            save = { raw ->
                settings.setString(size.key, raw.replace(',', '.').toFloat().toString())
                changed()
            },
            reset = {
                settings.setString(size.key, size.default.toString())
                changed()
            },
        )
    }

    private fun showColorDialog() {
        MarkdownEditDialog.show(
            context = requireContext(),
            title = "Bullet color",
            description = "Pick a color with the sliders, or enter #RRGGBB or #AARRGGBB. Changes apply when you save.",
            label = "Hex color",
            value = currentColor(),
            hint = "#5865F2",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            colorPreview = true,
            validate = { raw ->
                if (MarkdownAppearance.normalizeColor(raw) == null) "Enter #RRGGBB or #AARRGGBB" else null
            },
            save = { raw ->
                settings.setString(MarkdownAppearance.BULLET_COLOR, MarkdownAppearance.normalizeColor(raw)!!)
                changed()
            },
            reset = {
                settings.setString(MarkdownAppearance.BULLET_COLOR, MarkdownAppearance.DEFAULT_BULLET_COLOR)
                changed()
            },
        )
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
