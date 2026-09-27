package com.aliucord.plugins

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting
import java.util.Locale

class VoiceSettings(
    private val settings: SettingsAPI,
) : BottomSheet() {
    // FragmentManager recreates sheets through their public empty constructor.
    constructor() : this(SettingsAPI("VoiceMessages"))

    private var previewButton: ImageView? = null
    private var previewCaption: TextView? = null
    private var backgroundRow: View? = null
    private var colorDialog: AlertDialog? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        linearLayout.setPadding(dp(16), dp(8), dp(16), dp(24))
        linearLayout.addView(
            text("Voice messages", 22f, primary()).apply {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            },
        )
        linearLayout.addView(
            text("Make the microphone your own.", 14f, muted()).apply {
                setPadding(0, dp(4), 0, dp(16))
            },
        )
        addPreview()

        val recording = section("Recording", "Choose how you start a voice message.")
        toggle(recording, "disableSelectionPopup", "Hold to record", "Skip the menu. Release to send, or slide away to cancel.")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            divider(recording)
            toggle(recording, "legacyOgg", "Try Ogg / Opus", "Experimental on Android 7–9. Uses M4A when unavailable.")
        }

        val quality = section("Audio quality", "Applies to new microphone recordings.")
        qualityOptions(quality)

        val appearance = section("Appearance", "Your changes appear in the preview above.")
        toggle(appearance, "integratedButton", "Place inside the chatbox", "Use a microphone icon without a circular background.")
        divider(appearance)
        backgroundRow = colorSetting(appearance, "buttonColor", "Button color", "Circular background", VoiceMessages.DEFAULT_BUTTON_COLOR)
        divider(appearance)
        colorSetting(appearance, "buttonIconColor", "Microphone color", "Icon when you are not recording", VoiceMessages.DEFAULT_ICON_COLOR)
        divider(appearance)
        toggle(appearance, "translucentButton", "Soft opacity", "Make the microphone and its background semi-transparent.")
        updatePreview()
    }

    private fun addPreview() {
        val card = card().apply { setPadding(dp(16), dp(16), dp(16), dp(12)) }
        val composer =
            LinearLayout(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        composer.addView(text("+", 26f, muted()), LinearLayout.LayoutParams(dp(32), dp(44)))
        val message =
            text("Message", 15f, muted()).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                background = rounded(themeColor(context, "colorBackgroundTertiary", Color.DKGRAY), 12)
            }
        composer.addView(message, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(10) })
        previewButton =
            ImageView(requireContext()).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setImageDrawable(ContextCompat.getDrawable(context, com.lytefast.flexinput.R.e.ic_mic_grey_24dp)?.mutate())
            }
        composer.addView(previewButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        card.addView(composer)
        previewCaption = text("", 12f, muted()).apply { setPadding(0, dp(10), 0, 0) }
        card.addView(previewCaption)
        linearLayout.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun updatePreview() {
        val integrated = settings.getBool("integratedButton", false)
        val alpha = if (settings.getBool("translucentButton", false)) 160 else 255
        previewButton?.apply {
            val color = savedColor("buttonColor", VoiceMessages.DEFAULT_BUTTON_COLOR)
            background =
                if (integrated) {
                    null
                } else {
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
                    }
                }
            drawable?.setTint(savedColor("buttonIconColor", VoiceMessages.DEFAULT_ICON_COLOR))
            drawable?.alpha = alpha
        }
        previewCaption?.text =
            if (settings.getBool("disableSelectionPopup", false)) {
                "Hold to record · release to send"
            } else {
                "Tap to choose a recording or audio file"
            }
        backgroundRow?.alpha = if (integrated) 0.5f else 1f
        backgroundRow?.isEnabled = !integrated
        backgroundRow?.contentDescription = if (integrated) "Button color: available with a circular background" else null
    }

    private fun section(
        title: String,
        description: String,
    ): LinearLayout {
        linearLayout.addView(
            text(title, 16f, primary()).apply {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(4), dp(24), 0, dp(4))
            },
        )
        linearLayout.addView(text(description, 13f, muted()).apply { setPadding(dp(4), 0, 0, dp(10)) })
        return card().also { linearLayout.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun card() =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(themeColor(context, "colorBackgroundSecondary", Color.rgb(47, 49, 54)), 14)
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
                    updatePreview()
                }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }

    private fun qualityOptions(parent: LinearLayout) {
        val selected = settings.getInt("audioQuality", 128).takeIf { it in listOf(64, 128, 192) } ?: 128
        val radios = mutableListOf<Pair<RadioButton, Int>>()
        val choices =
            listOf(
                Triple("High", "More detail · larger files", 192),
                Triple("Balanced", "Recommended for everyday voice messages", 128),
                Triple("Compact", "Smaller files · lower audio quality", 64),
            )
        choices.forEachIndexed { index, (name, description, value) ->
            if (index > 0) divider(parent)
            val row = row()
            val column =
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(name, 16f, primary()))
                    addView(text(description, 12f, muted()).apply { setPadding(0, dp(4), dp(8), 0) })
                }
            row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text("$value kbps", 12f, muted()).apply { setPadding(dp(8), 0, dp(8), 0) })
            val radio =
                RadioButton(requireContext()).apply {
                    isChecked = selected == value
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    buttonTintList =
                        android.content.res.ColorStateList
                            .valueOf(themeColor(context, "colorBrand", VoiceMessages.DEFAULT_BUTTON_COLOR))
                }
            radios.add(radio to value)
            row.addView(radio, LinearLayout.LayoutParams(dp(32), dp(40)))
            row.contentDescription = "$name, $value kilobits per second. $description"
            row.isSelected = selected == value
            row.setOnClickListener {
                settings.setInt("audioQuality", value)
                radios.forEach { (button, quality) ->
                    button.isChecked = quality == value
                    (button.parent as View).isSelected = quality == value
                }
            }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2))
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
        val swatch = View(requireContext())

        fun update() {
            val color = savedColor(key, defaultColor)
            summary.text = "$description · ${hex(color)}"
            swatch.background = rounded(color, 10).apply { setStroke(dp(1), muted()) }
            row.contentDescription = "$title, ${hex(color)}. Change color"
        }
        update()
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        row.addView(swatch, LinearLayout.LayoutParams(dp(32), dp(32)))
        row.setOnClickListener {
            showColorDialog(key, title, defaultColor) {
                update()
                updatePreview()
            }
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    private fun showColorDialog(
        key: String,
        title: String,
        defaultColor: Int,
        update: () -> Unit,
    ) {
        if (colorDialog?.isShowing == true) return
        val context = requireContext()
        val initialColor = savedColor(key, defaultColor)
        val picker = ColorPickerView(context, initialColor)
        val draftPreview = ImageView(context)
        val draftLabel = text(hex(initialColor), 12f, muted())
        val presetViews = mutableListOf<Pair<TextView, Int>>()
        val integrated = settings.getBool("integratedButton", false)
        val opacity = if (settings.getBool("translucentButton", false)) 160 else 255

        fun renderPreview(
            image: ImageView,
            color: Int,
        ) {
            val buttonColor = if (key == "buttonColor") color else savedColor("buttonColor", VoiceMessages.DEFAULT_BUTTON_COLOR)
            val iconColor = if (key == "buttonIconColor") color else savedColor("buttonIconColor", VoiceMessages.DEFAULT_ICON_COLOR)
            image.apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setImageDrawable(ContextCompat.getDrawable(context, com.lytefast.flexinput.R.e.ic_mic_grey_24dp)?.mutate())
                drawable?.setTint(iconColor)
                drawable?.alpha = opacity
                background =
                    if (integrated) {
                        null
                    } else {
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.argb(opacity, Color.red(buttonColor), Color.green(buttonColor), Color.blue(buttonColor)))
                        }
                    }
            }
        }

        val input =
            EditText(context).apply {
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

        fun updateDraftPreview(color: Int) {
            renderPreview(draftPreview, color)
            draftLabel.text = hex(color)
            presetViews.forEach { (swatch, preset) ->
                swatch.text = if (preset == color) "✓" else ""
                (swatch.parent as View).isSelected = preset == color
            }
        }

        fun setDraft(
            color: Int,
            updatePicker: Boolean = true,
        ) {
            updating = true
            input.setText(hex(color))
            input.error = null
            // Keep the picker's HSV state when it emitted the change. Converting an
            // achromatic RGB color back to HSV would discard the selected hue.
            if (updatePicker) picker.color = color
            updateDraftPreview(color)
            updating = false
        }
        input.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {}

                override fun afterTextChanged(text: Editable?) {
                    if (!updating) {
                        parseColor(text.toString())?.let { color ->
                            picker.color = color
                            updateDraftPreview(color)
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
                val previews =
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        background = rounded(themeColor(context, "colorBackgroundTertiary", Color.DKGRAY), 12)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                    }

                fun addPreview(
                    label: String,
                    image: ImageView,
                    value: TextView,
                ) {
                    val column =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            addView(text(label, 12f, muted()).apply { setPadding(0, 0, 0, dp(8)) })
                            addView(image, LinearLayout.LayoutParams(dp(44), dp(44)))
                            addView(value.apply { setPadding(0, dp(8), 0, 0) })
                        }
                    previews.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
                }
                val currentPreview = ImageView(context)
                renderPreview(currentPreview, initialColor)
                addPreview("Current", currentPreview, text(hex(initialColor), 12f, muted()))
                addPreview("New", draftPreview, draftLabel)
                addView(previews, LinearLayout.LayoutParams(-1, -2))
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
                                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, attribute, true) &&
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
                    LinearLayout.LayoutParams(-1, minOf(dp(180), resources.displayMetrics.heightPixels / 3).coerceAtLeast(dp(96))).apply {
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
                override fun onMeasure(
                    widthMeasureSpec: Int,
                    heightMeasureSpec: Int,
                ) {
                    val maximum = resources.displayMetrics.heightPixels * 3 / 5
                    val available =
                        if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
                            maximum
                        } else {
                            minOf(maximum, View.MeasureSpec.getSize(heightMeasureSpec))
                        }
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
                }
            }.apply { addView(content) }
        val dialog =
            AlertDialog
                .Builder(context)
                .setCustomTitle(
                    text(title, 20f, primary()).apply {
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        setPadding(dp(20), dp(20), dp(20), dp(8))
                    },
                ).setView(scroll)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", null)
                .setPositiveButton("Save", null)
                .create()
        colorDialog = dialog
        dialog.setOnDismissListener {
            hideKeyboard()
            if (colorDialog === dialog) colorDialog = null
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(rounded(themeColor(context, "colorBackgroundSecondary", Color.rgb(47, 49, 54)), 16))
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            setLayout(minOf(dp(400), resources.displayMetrics.widthPixels - dp(32)), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        content.requestFocus()
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which).setTextColor(themeColor(context, "colorBrand", VoiceMessages.DEFAULT_BUTTON_COLOR))
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(muted())
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(primary())
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
        previewButton = null
        previewCaption = null
        backgroundRow = null
        super.onDestroyView()
    }

    private fun row() =
        LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isFocusable = true
            val value = android.util.TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true) && value.resourceId != 0) {
                background = ContextCompat.getDrawable(context, value.resourceId)
            }
        }

    private fun divider(parent: LinearLayout) {
        parent.addView(
            View(requireContext()).apply {
                setBackgroundColor(themeColor(context, "colorBackgroundTertiary", Color.DKGRAY))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            },
        )
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
    ) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun rounded(
        color: Int,
        radius: Int,
    ) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun primary() = themeColor(requireContext(), "colorHeaderPrimary", Color.WHITE)

    private fun muted() = themeColor(requireContext(), "colorTextMuted", Color.LTGRAY)

    private fun savedColor(
        key: String,
        default: Int,
    ) = settings.getInt(key, default).let { if (Color.alpha(it) == 0) default else it }

    private fun parseColor(value: String): Int? {
        val raw = value.trim().removePrefix("#")
        return if (raw.matches(Regex("[0-9a-fA-F]{6}"))) Color.parseColor("#$raw") else null
    }

    private fun hex(color: Int) = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}

internal fun themeColor(
    context: Context,
    name: String,
    fallback: Int,
): Int {
    val id = Utils.getResId(name, "attr")
    if (id == 0) return fallback
    val value = android.util.TypedValue()
    if (!context.theme.resolveAttribute(id, value, true)) return fallback
    if (value.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT) return value.data
    return try {
        if (value.resourceId == 0) fallback else ContextCompat.getColor(context, value.resourceId)
    } catch (
        _: RuntimeException,
    ) {
        fallback
    }
}
