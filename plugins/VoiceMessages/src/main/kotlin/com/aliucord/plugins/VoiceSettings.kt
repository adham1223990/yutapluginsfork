package com.aliucord.plugins

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.Utils
import com.discord.views.CheckedSetting
import java.util.Locale

class VoiceSettings(private val settings: SettingsAPI) : SettingsPage() {
    // FragmentManager recreates pages through their public empty constructor.
    constructor() : this(SettingsAPI("VoiceMessages"))

    private var backgroundRow: View? = null
    private var microphoneRow: View? = null
    private var colorDialog: AlertDialog? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Voice messages")
        setActionBarSubtitle("Plugin settings")
        linearLayout.setPadding(0, dp(16), 0, dp(24))
        linearLayout.setBackgroundColor(themeColor(requireContext(), "colorBackgroundPrimary", Color.DKGRAY))
        linearLayout.addView(
            text("Make the microphone your own.", 14f, muted()).apply {
                setPadding(dp(16), 0, dp(16), dp(16))
            },
        )

        val recording = section("Recording", "Choose how you start a voice message.")
        toggle(
            recording,
            "disableSelectionPopup",
            "Hold to record",
            "Skip the menu. Release to send, or slide away to cancel.",
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            divider(recording)
            toggle(recording, "legacyOgg", "Try Ogg / Opus", "Experimental on Android 7–9. Uses M4A when unavailable.")
        }

        val quality = section("Audio quality", "Applies to new microphone recordings.")
        qualityOptions(quality)

        val appearance = section("Appearance", "Customize the microphone button in the message composer.")
        toggle(
            appearance,
            "integratedButton",
            "Place inside the chatbox",
            "Match the emoji button with a grey microphone and no circular background.",
        )
        divider(appearance)
        backgroundRow =
            colorSetting(
                appearance,
                "buttonColor",
                "Button color",
                "Circular background",
                VoiceMessages.DEFAULT_BUTTON_COLOR,
            )
        divider(appearance)
        microphoneRow = colorSetting(
            appearance,
            "buttonIconColor",
            "Microphone color",
            "Icon when you are not recording",
            VoiceMessages.DEFAULT_ICON_COLOR,
        )
        divider(appearance)
        toggle(
            appearance,
            "translucentButton",
            "Soft opacity",
            "Make the microphone and its background semi-transparent.",
        )
        updateColorAvailability()
    }

    private fun updateColorAvailability() {
        val integrated = settings.getBool("integratedButton", false)
        listOf(backgroundRow, microphoneRow).forEach { row ->
            row?.isEnabled = !integrated
            row?.alpha = if (integrated) 0.5f else 1f
        }
    }

    private fun section(title: String, description: String): LinearLayout {
        linearLayout.addView(DiscordSettingsUi.divider(requireContext()), LinearLayout.LayoutParams(-1, dp(1)))
        linearLayout.addView(DiscordSettingsUi.header(requireContext(), title))
        linearLayout.addView(
            text(description, 14f, muted()).apply {
                setPadding(dp(16), 0, dp(16), dp(8))
            },
        )
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            linearLayout.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun toggle(
        parent: LinearLayout,
        key: String,
        title: String,
        description: String,
        default: Boolean = false,
    ) {
        parent.addView(
            Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, description).apply {
                isChecked = settings.getBool(key, default)
                setOnCheckedListener { value ->
                    settings.setBool(key, value)
                    if (key == "disableSelectionPopup" || key == "integratedButton") {
                        VoiceMessages.instance?.refreshSettings()
                    } else if (key == "translucentButton") {
                        VoiceMessages.instance?.refreshAppearance()
                    }
                    updateColorAvailability()
                }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }

    private fun qualityOptions(parent: LinearLayout) {
        val selected = settings.getInt("audioQuality", 128).takeIf { it in listOf(64, 128, 192) } ?: 128
        val options = mutableListOf<Pair<CheckedSetting, Int>>()
        var updating = false
        val choices = listOf(
            Triple("High", "192 kbps · More detail and larger files", 192),
            Triple("Balanced", "128 kbps · Recommended for everyday voice messages", 128),
            Triple("Compact", "64 kbps · Smaller files and lower audio quality", 64),
        )
        choices.forEach { (name, description, value) ->
            val option = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.RADIO, name, description)
            option.isChecked = selected == value
            option.setOnCheckedListener { checked ->
                if (!updating) {
                    updating = true
                    if (checked) settings.setInt("audioQuality", value)
                    val quality = settings.getInt("audioQuality", 128)
                    options.forEach { (button, bitrate) -> button.isChecked = bitrate == quality }
                    updating = false
                }
            }
            options.add(option to value)
            parent.addView(option, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun colorSetting(
        parent: LinearLayout,
        key: String,
        title: String,
        description: String,
        defaultColor: Int,
    ): View {
        val row = row()
        val column = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(title, 16f, primary()))
        val summary = text("", 12f, muted()).apply { setPadding(0, dp(4), 0, 0) }
        column.addView(summary)

        fun update() {
            val color = savedColor(key, defaultColor)
            summary.text = "$description · ${hex(color)}"
            row.contentDescription = "$title, ${hex(color)}. Change color"
        }
        update()
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        row.setOnClickListener {
            showColorDialog(key, title, defaultColor) {
                update()
                updateColorAvailability()
            }
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    private fun showColorDialog(key: String, title: String, defaultColor: Int, update: () -> Unit) {
        if (settings.getBool("integratedButton", false) || colorDialog?.isShowing == true) return
        val context = requireContext()
        val initialColor = savedColor(key, defaultColor)
        val picker = ColorPickerView(context, initialColor)
        val presetViews = mutableListOf<Pair<TextView, Int>>()

        val input =
            DiscordSettingsUi.input(context).apply {
                setSingleLine(true)
                setSelectAllOnFocus(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                imeOptions = EditorInfo.IME_ACTION_DONE
                filters = arrayOf(InputFilter.LengthFilter(7))
                typeface = Typeface.MONOSPACE
                textSize = 16f
                setTextColor(primary())
                setHintTextColor(muted())
                hint = "#RRGGBB"
                contentDescription = "Color in hexadecimal, #RRGGBB"
            }
        var updating = false

        fun updatePresetSelection(color: Int) {
            presetViews.forEach { (swatch, preset) ->
                swatch.text = if (preset == color) "✓" else ""
                (swatch.parent as View).isSelected = preset == color
            }
        }

        fun setDraft(color: Int, updatePicker: Boolean = true) {
            updating = true
            input.setText(hex(color))
            input.error = null
            // Keep the picker's HSV state when it emitted the change. Converting an
            // achromatic RGB color back to HSV would discard the selected hue.
            if (updatePicker) picker.color = color
            updatePresetSelection(color)
            updating = false
        }
        input.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(text: Editable?) {
                    if (!updating) {
                        parseColor(text.toString())?.let { color ->
                            picker.color = color
                            updatePresetSelection(color)
                            input.error = null
                        }
                    }
                }
            },
        )

        fun hideKeyboard() {
            input.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
        }
        picker.onColorChanged = { color ->
            setDraft(color, updatePicker = false)
            hideKeyboard()
        }
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isFocusableInTouchMode = true
                setPadding(dp(20), dp(8), dp(20), dp(16))
                addView(
                    text("Choose a preset, use the picker, or enter a hex color.", 13f, muted()),
                    LinearLayout.LayoutParams(-1, -2).apply {
                        bottomMargin =
                            dp(12)
                    },
                )
                addView(text("Quick colors", 13f, primary()).apply { setPadding(0, dp(16), 0, dp(4)) })
                val presets =
                    listOf(
                        defaultColor,
                        Color.rgb(88, 101, 242),
                        Color.WHITE,
                        Color.rgb(181, 186, 193),
                        Color.rgb(235, 69, 158),
                        Color.rgb(87, 242, 135),
                        Color.rgb(254, 231, 92),
                        Color.rgb(237, 66, 69),
                        Color.rgb(155, 89, 182),
                    ).distinct().take(8)
                presets.chunked(4).forEach { colors ->
                    val presetRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                    colors.forEach { color ->
                        val swatch =
                            text(
                                "",
                                20f,
                                if (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114 >
                                    150000
                                ) {
                                    Color.BLACK
                                } else {
                                    Color.WHITE
                                },
                            ).apply {
                                gravity = Gravity.CENTER
                                background =
                                    GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setColor(color)
                                        setStroke(dp(1), muted())
                                    }
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }
                        presetViews.add(swatch to color)
                        val target =
                            LinearLayout(context).apply {
                                gravity = Gravity.CENTER
                                minimumWidth = dp(48)
                                isFocusable = true
                                contentDescription = "Use ${hex(color)}"
                                val attribute = android.util.TypedValue()
                                if (context.theme.resolveAttribute(
                                        android.R.attr.selectableItemBackground,
                                        attribute,
                                        true,
                                    ) &&
                                    attribute.resourceId != 0
                                ) {
                                    background = ContextCompat.getDrawable(context, attribute.resourceId)
                                }
                                addView(swatch, LinearLayout.LayoutParams(dp(32), dp(32)))
                                setOnClickListener {
                                    setDraft(color)
                                    hideKeyboard()
                                }
                            }
                        presetRow.addView(target, LinearLayout.LayoutParams(0, dp(48), 1f))
                    }
                    addView(presetRow, LinearLayout.LayoutParams(-1, -2))
                }
                addView(
                    picker,
                    LinearLayout
                        .LayoutParams(
                            -1,
                            minOf(dp(180), resources.displayMetrics.heightPixels / 3).coerceAtLeast(dp(96)),
                        ).apply {
                            topMargin =
                                dp(12)
                        },
                )
                addView(text("Hex color", 13f, primary()).apply { setPadding(0, dp(16), 0, 0) })
                val hexRow =
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
                    }
                addView(hexRow)
            }
        setDraft(initialColor)
        val scroll =
            object : ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val maximum = resources.displayMetrics.heightPixels * 3 / 5
                    val available =
                        if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
                            maximum
                        } else {
                            minOf(maximum, View.MeasureSpec.getSize(heightMeasureSpec))
                        }
                    super.onMeasure(
                        widthMeasureSpec,
                        View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST),
                    )
                }
            }.apply { addView(content) }
        val dialog =
            AlertDialog
                .Builder(context)
                .setCustomTitle(DiscordSettingsUi.title(context, title))
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", null)
                .setPositiveButton("Save", null)
                .create()
        colorDialog = dialog
        dialog.setOnDismissListener {
            hideKeyboard()
            if (colorDialog === dialog) colorDialog = null
        }
        DiscordSettingsUi.styleDialog(dialog, context)
        dialog.window?.apply {
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
        }
        dialog.show()
        content.requestFocus()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            setDraft(defaultColor)
            hideKeyboard()
        }

        fun saveDraft() {
            val selected = parseColor(input.text.toString())
            if (selected == null) {
                input.error = "Enter six hex digits, like #5865F2"
                input.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            } else {
                settings.setInt(key, selected)
                update()
                VoiceMessages.instance?.refreshAppearance()
                dialog.dismiss()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { saveDraft() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                saveDraft()
                true
            } else {
                false
            }
        }
    }

    override fun onDestroyView() {
        colorDialog?.dismiss()
        colorDialog = null
        backgroundRow = null
        microphoneRow = null
        super.onDestroyView()
    }

    private fun row() = LinearLayout(requireContext()).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isFocusable = true
        val value = android.util.TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true) &&
            value.resourceId != 0
        ) {
            background = ContextCompat.getDrawable(context, value.resourceId)
        }
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(
            View(requireContext()).apply {
                setBackgroundColor(themeColor(context, "colorBackgroundModifierAccent", Color.DKGRAY))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            },
        )
    }

    private fun text(value: String, size: Float, color: Int) = DiscordSettingsUi.text(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun primary() = themeColor(requireContext(), "colorHeaderPrimary", Color.WHITE)

    private fun muted() = themeColor(requireContext(), "colorTextMuted", Color.LTGRAY)

    private fun savedColor(key: String, default: Int) =
        settings.getInt(key, default).let { if (Color.alpha(it) == 0) default else it }

    private fun parseColor(value: String): Int? {
        val raw = value.trim().removePrefix("#")
        return if (raw.matches(Regex("[0-9a-fA-F]{6}"))) Color.parseColor("#$raw") else null
    }

    private fun hex(color: Int) = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}

internal fun themeColor(context: Context, name: String, fallback: Int): Int {
    val id = Utils.getResId(name, "attr")
    if (id == 0) return fallback
    val value = android.util.TypedValue()
    if (!context.theme.resolveAttribute(id, value, true)) return fallback
    if (value.type in
        android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT
    ) {
        return value.data
    }
    return try {
        if (value.resourceId == 0) fallback else ContextCompat.getColor(context, value.resourceId)
    } catch (
        _: RuntimeException,
    ) {
        fallback
    }
}
