package com.github.yutaplug.fakedecor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting
import com.facebook.drawee.drawable.`ScalingUtils$ScaleType`
import com.facebook.drawee.view.SimpleDraweeView
import com.lytefast.flexinput.R

class FakeDecorSettings(
    @Suppress("UNUSED_PARAMETER") settings: SettingsAPI,
    private val plugin: FakeDecor,
) : SettingsPage() {
    private var assetInput: EditText? = null
    private var authorizationStatus: TextView? = null
    private var presetDialog: AlertDialog? = null
    private var boundSettingsView: View? = null

    // Aliucord's FragmentProxy owns Fragment attachment; track the view supplied to this page instead.
    internal val isSettingsViewActive: Boolean
        get() = boundSettingsView?.isAttachedToWindow == true

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        boundSettingsView = view
        setActionBarTitle("FakeDecor")
        setActionBarSubtitle("Avatar decorations")
        linearLayout.setPadding(0, 0, 0, DimenUtils.dpToPx(16))
        linearLayout.setBackgroundColor(ColorCompat.getThemedColor(requireContext(), R.b.colorBackgroundPrimary))

        section("Local decoration")
        note("Choose a custom avatar decoration. Your selection is saved separately for each Discord account.")
        val input = TextInput(requireContext(), "Decoration hash or asset", plugin.getSelectedAsset())
        assetInput = input.editText.apply { setSingleLine(true) }
        val inputParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
        }
        linearLayout.addView(input, inputParams)
        action("Apply locally", "Show this decoration in messages, lists, and profiles") {
            val asset = assetInput?.text?.toString().orEmpty()
            plugin.setSelectedAsset(asset)
            val selected = plugin.getSelectedAsset()
            assetInput?.setText(selected)
            Utils.showToast(if (selected.isEmpty()) "Decoration removed" else "Decoration applied")
        }
        action("Browse Decor presets", "Choose an available preset decoration") {
            Utils.showToast("Loading Decor presets…")
            plugin.fetchPresets(this)
        }

        divider()
        section("Display")
        add(
            Utils
                .createCheckedSetting(
                    requireContext(),
                    CheckedSetting.ViewType.SWITCH,
                    "Preserve official Discord decorations",
                    "Show the official decoration when one is available",
                ).apply {
                    setPaddingRelative(paddingStart, 0, paddingEnd, paddingBottom)
                    findViewById<View>(Utils.getResId("setting_container", "id"))?.apply {
                        setPaddingRelative(paddingStart, DimenUtils.dpToPx(8), paddingEnd, paddingBottom)
                    }
                    isChecked = plugin.isPreserveOriginalDecor()
                    setOnCheckedListener { plugin.setPreserveOriginalDecor(it) }
                },
        )

        divider()
        section("Decor account")
        authorizationStatus = note("")
        refreshAuthState()
        action("Authorize with Discord", "Connect this Discord account in your browser") {
            plugin.authorizeDecor(requireContext())
        }
        action("Finish authorization", "Copy the returned token, then tap here") {
            plugin.finishBrowserAuthorization(requireContext(), this)
        }
        action("Disconnect Decor", "Remove access for this Discord account") {
            plugin.disconnectDecor()
            refreshAuthState()
        }

        divider()
        section("Cloud decorations")
        action("My Decor decorations", "Select or delete decorations from your account") {
            plugin.fetchOwnDecorations(this)
        }
        action("Sync from Decor", "Load your current Decor selection") { plugin.refreshOwnDecoration() }
        action("Sync selection to Decor", "Save your local selection to Decor") { plugin.applyToDecorService() }
        action("Upload custom decoration", "Submit a PNG or APNG for review") {
            if (!plugin.isAuthorized()) {
                Utils.showToast("Authorize Decor before uploading")
            } else {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    },
                    PICK_DECORATION,
                )
            }
        }

        divider()
        section("About Decor")
        note(
            "Decor is a separate service. Presets and local selection work without an account. " +
                "Cloud actions require authorization, and uploaded decorations may need review.",
        )
    }

    fun refreshAuthState() {
        authorizationStatus?.text = if (plugin.isAuthorized()) {
            "Connected to Decor for this Discord account"
        } else {
            "Not connected to Decor"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_DECORATION && resultCode == Activity.RESULT_OK) {
            data?.data?.let { plugin.uploadDecoration(requireContext().applicationContext, it) }
        }
    }

    fun showPresets(presets: List<*>) {
        val items = mutableListOf<DecorPreset>()
        for (raw in presets) {
            val preset = raw as? Map<*, *> ?: continue
            val decorations = preset["decorations"] as? List<*> ?: continue
            for (item in decorations) {
                val decoration = item as? Map<*, *> ?: continue
                val asset = FakeDecor.decorationAsset(decoration).takeIf { it.isNotEmpty() } ?: continue
                items +=
                    DecorPreset(asset, decoration["alt"]?.toString() ?: asset, preset["name"]?.toString().orEmpty())
            }
        }
        if (items.isEmpty()) {
            Utils.showToast("No Decor presets found")
            return
        }
        presetDialog?.dismiss()
        presetDialog = AlertDialog
            .Builder(requireContext())
            .setCustomTitle(
                TextView(requireContext(), null, 0, R.i.UiKit_TextView_H1_Bold).apply {
                    text = "Decor presets"
                    setTextColor(ColorCompat.getThemedColor(context, R.b.colorHeaderPrimary))
                    val padding = DimenUtils.dpToPx(16)
                    setPadding(padding, padding, padding, padding)
                },
            ).setAdapter(PresetAdapter(items)) { _, which ->
                assetInput?.setText(items[which].asset)
                assetInput?.let { it.setSelection(it.length()) }
            }.setNegativeButton("Cancel", null)
            .show()
    }

    fun showOwnDecorations(decorations: List<*>) {
        val valid = decorations.filterIsInstance<Map<*, *>>().filter {
            FakeDecor.decorationAsset(it).isNotEmpty()
        }
        if (valid.isEmpty()) {
            Utils.showToast("No Decor decorations found")
            return
        }
        val selected = plugin.getSelectedAsset()
        val labels = valid.map {
            val asset = FakeDecor.decorationAsset(it)
            (if (asset == selected) "✓ " else "") +
                (it["alt"] ?: asset) +
                (if (it["reviewed"] == false) " (pending review)" else "")
        }
        var checked = valid.indexOfFirst { FakeDecor.decorationAsset(it) == selected }.coerceAtLeast(0)
        val dialog = AlertDialog
            .Builder(requireContext())
            .setTitle("My Decor decorations")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { _, which -> checked = which }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete", null)
            .setPositiveButton("Use", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (valid[checked]["reviewed"] == false) {
                    Utils.showToast("This decoration is still pending review")
                } else {
                    plugin.setSelectedAsset(FakeDecor.decorationAsset(valid[checked]))
                    assetInput?.setText(plugin.getSelectedAsset())
                    dialog.dismiss()
                    Utils.showToast("Decoration applied")
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val hash = valid[checked]["hash"]?.toString() ?: return@setOnClickListener
                AlertDialog
                    .Builder(requireContext())
                    .setTitle("Delete decoration?")
                    .setMessage("This removes the decoration from your Decor account.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        plugin.deleteDecoration(hash)
                        dialog.dismiss()
                    }.show()
            }
        }
        dialog.show()
    }

    private fun section(title: String) {
        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
    }

    private fun note(text: String): TextView =
        TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            this.text = text
            // Notes are descriptive, so they do not inherit the settings-row ripple.
            background = null
            setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
            add(this)
        }

    private fun action(title: String, subtitle: String, action: () -> Unit) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            contentDescription = "$title. $subtitle"
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Label).apply {
                text = title
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(8), paddingEnd, 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        row.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = subtitle
                background = null
                setPadding(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        add(row)
    }

    private data class DecorPreset(
        val asset: String,
        val title: String,
        val collection: String,
    )

    private inner class PresetAdapter(private val items: List<DecorPreset>) : BaseAdapter() {
        override fun getCount() = items.size

        override fun getItem(position: Int) = items[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(DimenUtils.dpToPx(16), DimenUtils.dpToPx(8), DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
                val image = SimpleDraweeView(context).apply {
                    hierarchy.n(`ScalingUtils$ScaleType`.e)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setBackgroundColor(ColorCompat.getThemedColor(context, R.b.colorBackgroundSecondary))
                }
                addView(image, LinearLayout.LayoutParams(DimenUtils.dpToPx(72), DimenUtils.dpToPx(72)))
                val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val title = TextView(context, null, 0, R.i.UiKit_Settings_Item_Label).apply {
                    background = null
                    setPadding(0, 0, 0, 0)
                }
                val collection = TextView(context, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                    background = null
                    setPadding(0, DimenUtils.dpToPx(2), 0, 0)
                }
                labels.addView(title, LinearLayout.LayoutParams(-1, -2))
                labels.addView(collection, LinearLayout.LayoutParams(-1, -2))
                addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = DimenUtils.dpToPx(12) })
                tag = PresetRow(image, title, collection)
            }
            val holder = row.tag as PresetRow
            val item = getItem(position)
            holder.title.text = item.title
            holder.collection.text = item.collection
            holder.image.setImageURI(Uri.parse(FakeDecor.assetUrl(item.asset, false)))
            return row
        }
    }

    private data class PresetRow(
        val image: SimpleDraweeView,
        val title: TextView,
        val collection: TextView,
    )

    private fun divider() {
        add(View(requireContext(), null, 0, R.i.UiKit_Settings_Divider), DimenUtils.dpToPx(1))
    }

    private fun add(view: View, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        linearLayout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
    }

    override fun onDestroyView() {
        boundSettingsView = null
        presetDialog?.dismiss()
        presetDialog = null
        assetInput = null
        authorizationStatus = null
        super.onDestroyView()
    }

    companion object {
        private const val PICK_DECORATION = 4831
    }
}
