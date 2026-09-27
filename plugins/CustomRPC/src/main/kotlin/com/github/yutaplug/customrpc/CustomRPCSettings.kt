package com.github.yutaplug.customrpc

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting

class CustomRPCSettings(private val settings: SettingsAPI, private val plugin: CustomRPC) : BottomSheet() {
    private val inputs = linkedMapOf<String, EditText>()
    private lateinit var preview: TextView
    private lateinit var enabled: CheckedSetting
    private lateinit var typeSummary: TextView
    private var dirty = false

    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        inputs.clear()
        dirty = false
        linearLayout.setPadding(dp(16), dp(8), dp(16), dp(24))
        addView(text("CustomRPC", 24).apply { setTypeface(typeface, Typeface.BOLD) })
        addView(text("Create your profile activity. Save changes when you’re ready.", 14, "colorTextMuted"))
        enabled =
            Utils
                .createCheckedSetting(
                    requireContext(),
                    CheckedSetting.ViewType.SWITCH,
                    "Show activity",
                    "Enabling also turns on Discord activity sharing.",
                ).apply {
                    isChecked = plugin.isEnabled()
                    setOnCheckedListener { checked ->
                        if (checked && !save()) {
                            isChecked = false
                        } else {
                            if (checked) plugin.enableActivitySharing(requireActivity())
                            plugin.setEnabled(checked)
                        }
                    }
                }
        addView(enabled)
        section("Preview")
        preview =
            text("", 16).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = card()
                contentDescription =
                    "Activity text preview"
            }
        addView(preview)
        section("Activity")
        typeSummary = action("Activity type", CustomRPC.typeLabel(plugin.activityType())) {
            choiceDialog(
                "Activity type",
                "Choose how your activity appears on your profile.",
                CustomRPC.types.map(CustomRPC::typeLabel).toTypedArray(),
                BooleanArray(CustomRPC.types.size) { CustomRPC.types[it] == plugin.activityType() },
                false,
                { selected ->
                    val index = selected.indexOfFirst { it }
                    if (index >= 0) plugin.setType(CustomRPC.types[index])
                    typeSummary.text = CustomRPC.typeLabel(plugin.activityType())
                    updatePreview()
                },
            )
        }
        input("Activity name", CustomRPC.NAME, "Custom RPC")
        input("Details", CustomRPC.DETAILS, "What are you doing?")
        input("State", CustomRPC.STATE, "More about your activity")
        section("Images")
        addView(
            text(
                "Public image URLs override asset keys. An application ID lets Discord proxy images for other clients.",
                14,
                "colorTextMuted",
            ),
        )
        input("Application ID", CustomRPC.APPLICATION_ID, "Optional", InputType.TYPE_CLASS_NUMBER, 20)
        action("Developer Portal", "Manage applications and uploaded assets") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.com/developers/applications")))
            } catch (
                _: Exception,
            ) {
                Utils.showToast("Could not open Developer Portal")
            }
        }
        section("Large image")
        input(
            "Image URL",
            CustomRPC.LARGE_IMAGE_URL,
            "https://…",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            2048,
        )
        input("Asset key", CustomRPC.LARGE_IMAGE, "Optional")
        input("Hover text", CustomRPC.LARGE_IMAGE_TEXT, "Optional")
        section("Small image")
        input(
            "Image URL",
            CustomRPC.SMALL_IMAGE_URL,
            "https://…",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            2048,
        )
        input("Asset key", CustomRPC.SMALL_IMAGE, "Optional")
        input("Hover text", CustomRPC.SMALL_IMAGE_TEXT, "Optional")
        section("Advanced")
        lateinit var flagsSummary: TextView
        flagsSummary = action("Activity flags", ActivityFlags.label(plugin.flags())) {
            val checked = BooleanArray(ActivityFlags.values.size) { plugin.flags() and ActivityFlags.values[it] != 0 }
            choiceDialog(
                "Activity flags",
                "Configure activity capabilities. Joining and spectating also require secrets.",
                ActivityFlags.labels,
                checked,
                true,
                { selected ->
                    var flags = 0
                    ActivityFlags.values.forEachIndexed { index, value ->
                        if (selected[index]) flags = flags or value
                    }
                    plugin.setFlags(flags)
                    flagsSummary.text = ActivityFlags.label(plugin.flags())
                },
                {
                    plugin.setFlags(257)
                    flagsSummary.text = ActivityFlags.label(257)
                },
            )
        }
        addView(
            text(
                "Flags alone do not enable joining or spectating; these require activity secrets.",
                13,
                "colorTextMuted",
            ),
        )
        button("Save changes", true) {
            if (save()) Utils.showToast(if (plugin.isEnabled()) "Activity updated" else "Configuration saved")
        }
        button("Save and enable", true) {
            if (save()) {
                plugin.enableActivitySharing(requireActivity())
                plugin.setEnabled(true)
                enabled.isChecked =
                    true
                Utils.showToast("CustomRPC enabled")
            }
        }
        button("Remove activity", false) {
            plugin.setEnabled(false)
            enabled.isChecked = false
            Utils.showToast("Activity removed")
        }
        updatePreview()
    }

    private fun input(
        label: String,
        key: String,
        hint: String,
        type: Int = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        maxLength: Int = 128,
    ) {
        // Keep the label outside the editor: TextInput's floating hint overlaps an explicit placeholder.
        val field = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            background = card()
        }
        field.addView(text(label, 12, "colorTextMuted"))
        val editor = EditText(requireContext()).apply {
            setText(plugin.value(key))
            textSize = 16f
            background = null
            setPadding(0, dp(6), 0, dp(6))
            minimumHeight = dp(40)
            contentDescription = label
            inputType = type
            setSingleLine(true)
            this.hint = hint
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            setTextColor(color("colorTextNormal", Color.WHITE))
            setHintTextColor(color("colorTextMuted", Color.GRAY))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    dirty = true
                    error =
                        null
                    updatePreview()
                }

                override fun afterTextChanged(s: Editable?) {}
            })
        }
        inputs[key] = editor
        field.addView(editor, LinearLayout.LayoutParams(-1, -2))
        val params = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        linearLayout.addView(field, params)
    }

    private fun choiceDialog(
        title: String,
        description: String,
        labels: Array<String>,
        selected: BooleanArray,
        multiple: Boolean,
        save: (BooleanArray) -> Unit,
        defaults: (() -> Unit)? = null,
    ) {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
            background = card()
        }
        content.addView(text(title, 22).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(
            text(description, 14, "colorTextMuted").apply {
                setPadding(0, dp(8), 0, dp(16))
            },
        )
        val dialog = AlertDialog.Builder(requireContext()).setView(content).create()
        val choices = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val indicators = mutableListOf<CompoundButton>()
        labels.forEachIndexed { index, label ->
            val indicator: CompoundButton = if (multiple) CheckBox(requireContext()) else RadioButton(requireContext())
            indicator.apply {
                isChecked = selected[index]
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(color("colorBrand", Color.rgb(88, 101, 242)), color("colorTextMuted", Color.GRAY)),
                )
            }
            indicators.add(indicator)
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(4), dp(4), dp(8), dp(4))
                background = RippleDrawable(
                    ColorStateList.valueOf(color("colorBackgroundModifierSelected", 0x334f545c)),
                    null,
                    card(),
                )
                isFocusable = true
                contentDescription = label
                setOnClickListener {
                    if (multiple) {
                        selected[index] = !selected[index]
                        indicator.isChecked = selected[index]
                    } else {
                        selected.fill(false)
                        selected[index] = true
                        indicators.forEachIndexed { item, button -> button.isChecked = item == index }
                        save(selected)
                        dialog.dismiss()
                    }
                }
            }
            row.addView(indicator)
            row.addView(text(label, 16), LinearLayout.LayoutParams(0, -2, 1f))
            choices.addView(row)
        }
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = false
            addView(choices)
        }
        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                minOf(
                    dp(labels.size * 56),
                    resources.displayMetrics.heightPixels / 2,
                ),
            ),
        )
        val footer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        fun footerButton(label: String, primary: Boolean, action: () -> Unit) {
            footer.addView(
                Button(requireContext()).apply {
                    text = label
                    textSize = 14f
                    isAllCaps = false
                    minimumWidth = 0
                    setTextColor(if (primary) Color.WHITE else color("colorTextNormal", Color.WHITE))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(if (primary) color("colorBrand", Color.rgb(88, 101, 242)) else Color.TRANSPARENT)
                    }
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) },
            )
        }
        footerButton("Cancel", false) { dialog.dismiss() }
        if (defaults != null) {
            footerButton("Defaults", false) {
                defaults()
                dialog.dismiss()
            }
        }
        if (multiple) {
            footerButton("Save", true) {
                save(selected)
                dialog.dismiss()
            }
        }
        content.addView(footer)
        // Initialize the dialog and its final window size before the first visible frame.
        dialog.create()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf(resources.displayMetrics.widthPixels - dp(32), dp(480)), -2)
        }
        dialog.show()
    }

    private fun save(): Boolean {
        val values = inputs.mapValues { it.value.text.toString().trim() }
        var valid = true
        val id = values[CustomRPC.APPLICATION_ID].orEmpty()
        if (id.isNotEmpty() && (id.toLongOrNull()?.takeIf { it > 0 } == null)) {
            inputs[CustomRPC.APPLICATION_ID]?.error = "Enter a valid positive application ID"
            valid = false
        }
        for (key in listOf(CustomRPC.LARGE_IMAGE_URL, CustomRPC.SMALL_IMAGE_URL)) {
            if (!values[key].isNullOrEmpty() && CustomRPC.publicImageUrl(values[key]) == null) {
                inputs[key]?.error = "Enter a complete HTTP or HTTPS URL"
                valid = false
            }
        }
        if (!valid) {
            inputs.values.firstOrNull { it.error != null }?.requestFocus()
            return false
        }
        plugin.save(values)
        dirty = false
        return true
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return

        fun value(key: String) = inputs[key]?.text?.toString()?.trim() ?: plugin.value(key)
        preview.text =
            listOf(
                "${CustomRPC.typeLabel(plugin.activityType())} ${value(CustomRPC.NAME).ifEmpty { "Custom RPC" }}",
                value(CustomRPC.DETAILS),
                value(CustomRPC.STATE),
            ).filter {
                it.isNotEmpty()
            }.joinToString("\n")
    }

    override fun onDestroyView() {
        if (dirty) Utils.showToast("Unsaved CustomRPC changes discarded")
        inputs.clear()
        super.onDestroyView()
    }

    private fun section(label: String) = addView(
        text(label, 14, "colorTextMuted").apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(8))
        },
    )

    private fun text(label: String, size: Int, attribute: String = "colorHeaderPrimary") =
        TextView(requireContext()).apply {
            text = label
            textSize = size.toFloat()
            setTextColor(color(attribute, Color.LTGRAY))
        }

    private fun action(title: String, summary: String, action: () -> Unit): TextView {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minimumHeight = dp(56)
            background =
                RippleDrawable(
                    ColorStateList.valueOf(color("colorBackgroundModifierSelected", 0x334f545c)),
                    card(),
                    null,
                )
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(text(title, 16))
        val subtitle = text(summary, 13, "colorTextMuted")
        row.addView(subtitle)
        addView(row)
        return subtitle
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit) = addView(
        Button(requireContext()).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else color("colorTextDanger", Color.rgb(237, 66, 69)))
            backgroundTintList = ColorStateList.valueOf(
                color(if (primary) "colorBrand" else "colorBackgroundSecondary", Color.rgb(88, 101, 242)),
            )
            setOnClickListener { action() }
        },
    )

    private fun card() = GradientDrawable().apply {
        setColor(color("colorBackgroundSecondary", Color.rgb(47, 49, 54)))
        cornerRadius =
            dp(12).toFloat()
    }

    private fun color(attribute: String, fallback: Int): Int {
        val id = Utils.getResId(attribute, "attr")
        return if (id == 0) fallback else ColorCompat.getThemedColor(requireContext(), id)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
