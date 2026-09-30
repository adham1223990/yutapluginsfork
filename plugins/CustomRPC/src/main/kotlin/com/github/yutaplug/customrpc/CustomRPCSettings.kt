package com.github.yutaplug.customrpc

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting

class CustomRPCSettings(private val settings: SettingsAPI, private val plugin: CustomRPC) : SettingsPage() {
    private val inputs = linkedMapOf<String, EditText>()
    private lateinit var enabled: CheckedSetting
    private lateinit var typeSummary: TextView
    private var dirty = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("CustomRPC")
        setActionBarSubtitle("Plugin settings")
        inputs.clear()
        dirty = false
        linearLayout.setPadding(0, dp(16), 0, dp(24))
        linearLayout.setBackgroundColor(color("colorBackgroundPrimary", Color.DKGRAY))
        addView(
            text("Create your profile activity. Save changes when you’re ready.", 14, "colorTextMuted").apply {
                setPadding(dp(16), 0, dp(16), dp(16))
            },
        )
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
            ).apply { setPadding(dp(16), 0, dp(16), dp(8)) },
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
            ).apply { setPadding(dp(16), 0, dp(16), dp(8)) },
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
    }

    private fun input(
        label: String,
        key: String,
        hint: String,
        type: Int = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        maxLength: Int = 128,
    ) {
        val field = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(label, 14, "colorTextMuted"))
        }
        val editor = DiscordSettingsUi.input(requireContext()).apply {
            setText(plugin.value(key))
            textSize = 16f
            minimumHeight = dp(40)
            inputType = type
            setSingleLine(true)
            // Use the row's gutter for both the fixed label and native editor.
            setPadding(0, paddingTop, 0, paddingBottom)
            this.hint = hint
            contentDescription = "$label. $hint"
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            setTextColor(color("colorTextNormal", Color.WHITE))
            setHintTextColor(color("colorTextMuted", Color.GRAY))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    dirty = true
                    error =
                        null
                }

                override fun afterTextChanged(s: Editable?) {}
            })
        }
        inputs[key] = editor
        field.addView(editor, LinearLayout.LayoutParams(-1, -2))
        val params = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) }
        linearLayout.addView(field, params)
    }

    private var activeDialog: AlertDialog? = null

    private fun choiceDialog(
        title: String,
        description: String,
        labels: Array<String>,
        selected: BooleanArray,
        multiple: Boolean,
        save: (BooleanArray) -> Unit,
        defaults: (() -> Unit)? = null,
    ) {
        activeDialog?.dismiss()
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                text(description, 14, "colorTextMuted").apply {
                    setPadding(dp(16), dp(8), dp(16), dp(16))
                },
            )
        }
        val choices = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val builder = AlertDialog
            .Builder(requireContext())
            .setCustomTitle(DiscordSettingsUi.title(requireContext(), title))
            .setView(content)
            .setNegativeButton("Cancel", null)
        if (multiple) builder.setPositiveButton("Save") { _, _ -> save(selected) }
        if (defaults != null) builder.setNeutralButton("Defaults") { _, _ -> defaults() }
        val dialog = builder.create()
        labels.forEachIndexed { index, label ->
            val option = Utils
                .createCheckedSetting(
                    requireContext(),
                    if (multiple) CheckedSetting.ViewType.CHECK else CheckedSetting.ViewType.RADIO,
                    label,
                    null,
                ).apply {
                    isChecked = selected[index]
                    setOnCheckedListener { checked ->
                        if (multiple) {
                            selected[index] = checked
                        } else if (checked) {
                            selected.fill(false)
                            selected[index] = true
                            save(selected)
                            dialog.dismiss()
                        }
                    }
                }
            choices.addView(option, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(
            ScrollView(requireContext()).apply { addView(choices) },
            LinearLayout.LayoutParams(-1, minOf(dp(labels.size * 64), resources.displayMetrics.heightPixels / 2)),
        )
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        DiscordSettingsUi.styleDialog(dialog, requireContext())
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

    override fun onDestroyView() {
        activeDialog?.dismiss()
        activeDialog = null
        if (dirty) Utils.showToast("Unsaved CustomRPC changes discarded")
        inputs.clear()
        super.onDestroyView()
    }

    private fun section(label: String) {
        linearLayout.addView(DiscordSettingsUi.divider(requireContext()), LinearLayout.LayoutParams(-1, dp(1)))
        addView(DiscordSettingsUi.header(requireContext(), label))
    }

    private fun text(label: String, size: Int, attribute: String = "colorHeaderPrimary") =
        DiscordSettingsUi.text(requireContext()).apply {
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
                    null,
                    android.graphics.drawable.ColorDrawable(Color.WHITE),
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

    private fun button(label: String, primary: Boolean, action: () -> Unit) {
        val button = DiscordSettingsUi.button(requireContext(), primary).apply {
            text = label
            if (!primary) setTextColor(color("colorTextDanger", Color.RED))
            setOnClickListener { action() }
        }
        linearLayout.addView(
            button,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(16), dp(8), dp(16), 0)
            },
        )
    }

    private fun color(attribute: String, fallback: Int): Int {
        val id = Utils.getResId(attribute, "attr")
        return if (id == 0) fallback else ColorCompat.getThemedColor(requireContext(), id)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
