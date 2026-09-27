package com.github.yutaplug.bettermessagelogger

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting
import java.util.Locale
import com.github.yutaplug.bettermessagelogger.BetterMessageLogger.Companion as Keys

class BetterMessageLoggerSettings(private val settings: SettingsAPI) : BottomSheet() {
    // Android can recreate the sheet without the original constructor arguments.
    constructor() : this(SettingsAPI("BetterMessageLogger"))

    private lateinit var ui: LoggerUi
    private var storageCaption: TextView? = null
    private var databaseToggle: CheckedSetting? = null
    private var preview: TextView? = null
    private val captions = HashMap<String, TextView>()
    private var dialog: AlertDialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ui = LoggerUi(requireContext())
        linearLayout.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(28))
        linearLayout.addView(ui.heading("BetterMessageLogger").apply { textSize = 23f })
        linearLayout.addView(
            ui
                .text("Deleted messages and previous versions, right in your chats.", 14f, ui.muted)
                .apply { setPadding(0, ui.dp(6), 0, ui.dp(18)) },
        )

        val storage = section("Storage", "Saved logs are stored in Aliucord/BetterMessageLogger.db.")
        databaseToggle =
            toggle(
                storage,
                "database",
                "Save logs across restarts",
                "Keep deleted messages and edit history on this device.",
            ) {
                BetterMessageLogger.instance?.setDatabaseEnabled(it)
                updateStorage()
            }
        storageCaption = ui.text("", 12f, ui.muted).apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
        storage.addView(storageCaption)
        ui.divider(storage)
        ui.action(storage, "Export DB to TXT", "Save readable logs to Aliucord/BetterMessageLogger.txt") {
            BetterMessageLogger.instance?.exportDatabaseToText()
        }
        ui.divider(storage)
        ui.action(storage, "Clear saved logs", "Delete saved messages and edit history.", ui.danger) { confirmClear() }

        val appearance = section("Appearance", "Preview your deleted-message colors below.")
        preview = ui.text("", 15f).apply { setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(16)) }
        appearance.addView(preview)
        captions[Keys.DELETED_LABEL_COLOR] =
            ui.action(appearance, "Deleted tag color", colorValue(Keys.DELETED_LABEL_COLOR)) {
                colorDialog(Keys.DELETED_LABEL_COLOR, "Deleted tag color")
            }
        captions[Keys.DELETED_MESSAGE_COLOR] =
            ui.action(appearance, "Deleted text color", colorValue(Keys.DELETED_MESSAGE_COLOR)) {
                colorDialog(Keys.DELETED_MESSAGE_COLOR, "Deleted text color")
            }
        toggle(appearance, Keys.SHOW_DELETED_TAG, "Show deleted tag", "Append (deleted) to logged messages.", true) {
            BetterMessageLogger.instance?.refreshAppearance()
            updateColors()
        }
        ui.action(appearance, "Reset colors", "Restore the default red tag and white message text.") {
            settings.setString(Keys.DELETED_LABEL_COLOR, Keys.DEFAULT_DELETED_LABEL_COLOR)
            settings.setString(Keys.DELETED_MESSAGE_COLOR, Keys.DEFAULT_DELETED_MESSAGE_COLOR)
            BetterMessageLogger.instance?.refreshAppearance()
            updateColors()
        }

        val history = section("Edit history", "Open View Edit History from a message's context menu.")
        toggle(
            history,
            Keys.LOG_EDIT_HISTORY,
            "Log edit history",
            "Keep previous text versions. Turning this off clears saved edit history.",
            true,
        ) {
            BetterMessageLogger.instance?.setEditLoggingEnabled(it)
            updateStorage()
        }
        ui.divider(history)
        toggle(
            history,
            Keys.INLINE_EDIT_HISTORY,
            "Show history in chat",
            "Display previous versions above the current message.",
        ) {
            BetterMessageLogger.instance?.refreshAppearance()
        }

        val people = section("People", "Filters also remove matching entries from saved logs.")
        toggle(
            people,
            "ignoreOwn",
            "Ignore my messages",
            "Skip messages from your current account.",
        ) { filtersChanged() }
        toggle(people, "ignoreBots", "Ignore bot messages", "Skip messages sent by bots.") { filtersChanged() }
        idAction(people, "ignoredUsers", "Ignored users", "Messages from these users are skipped.")

        val channels =
            section(
                "Channels and servers",
                "A non-empty allow list limits logging to its entries. Block lists always take priority.",
            )
        idAction(channels, "blackChannels", "Blocked channels", "Skip these server channels.")
        idAction(channels, "whiteChannels", "Allowed channels", "Only log these server channels.")
        ui.divider(channels)
        idAction(channels, "blackServers", "Blocked servers", "Skip every channel in these servers.")
        idAction(channels, "whiteServers", "Allowed servers", "Only log channels in these servers.")

        val dms = section("Direct messages", "DM filters are separate from server-channel filters.")
        idAction(dms, "blackDms", "Blocked DMs", "Skip these DM channel IDs.")
        idAction(dms, "whiteDms", "Allowed DMs", "Only log these DM channel IDs.")
        updateColors()
        updateStorage()
    }

    private fun section(title: String, description: String): LinearLayout {
        linearLayout.addView(ui.heading(title).apply { setPadding(ui.dp(4), ui.dp(16), 0, ui.dp(6)) })
        linearLayout.addView(ui.text(description, 13f, ui.muted).apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(10)) })
        return ui.card().also { linearLayout.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun toggle(
        parent: LinearLayout,
        key: String,
        title: String,
        subtitle: String,
        default: Boolean = false,
        changed: (Boolean) -> Unit,
    ): CheckedSetting {
        val toggle = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, subtitle)
        toggle.isChecked = settings.getBool(key, default)
        toggle.setOnCheckedListener {
            settings.setBool(key, it)
            changed(it)
        }
        parent.addView(toggle)
        return toggle
    }

    private fun updateStorage() {
        val target = storageCaption ?: return
        target.text =
            if (settings.getBool(
                    "database",
                    false,
                )
            ) {
                "Opening database…"
            } else {
                "Database off · logs stay in memory for this session"
            }
        BetterMessageLogger.instance?.storageStatistics { stats ->
            if (!isAdded || storageCaption !== target) return@storageStatistics
            databaseToggle?.isChecked = settings.getBool("database", false)
            if (stats != null) {
                val size = android.text.format.Formatter.formatShortFileSize(requireContext(), stats.bytes)
                val paused = if (settings.getBool("database", false)) "" else " · saving paused"
                target.text =
                    "${stats.messages} saved messages · ${stats.edits} edits · $size\nAliucord/BetterMessageLogger.db$paused"
            } else if (settings.getBool("database", false)) {
                target.text = "Database is opening"
            }
        }
    }

    private fun confirmClear() {
        showDialog(
            AlertDialog
                .Builder(requireContext())
                .setTitle("Clear saved logs?")
                .setMessage(
                    "This removes all logged messages and edit history from this device. Exported files are kept.",
                ).setNegativeButton("Cancel", null)
                .setPositiveButton("Clear logs") { _, _ ->
                    BetterMessageLogger.instance?.clearDatabase { if (isAdded) updateStorage() }
                }.create(),
        )
    }

    private fun idAction(parent: LinearLayout, key: String, title: String, description: String) {
        captions[key] =
            ui.action(parent, title, "$description · ${readIds(key).size} added") { idDialog(key, title, description) }
    }

    private fun readIds(key: String): LinkedHashSet<Long> = settings
        .getString(key, "")
        .orEmpty()
        .split(',')
        .mapNotNull { it.trim().toLongOrNull()?.takeIf { value -> value > 0 } }
        .toCollection(LinkedHashSet())

    private fun idDialog(key: String, title: String, description: String) {
        val content = ui.column().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8)) }
        content.addView(ui.text(description, 13f, ui.muted))
        val list = ui.column()
        content.addView(
            ScrollView(requireContext()).apply { addView(list) },
            LinearLayout.LayoutParams(-1, ui.dp(180)).apply {
                topMargin =
                    ui.dp(12)
            },
        )
        val input = EditText(requireContext()).apply {
            hint = "Paste an ID"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setTextColor(ui.primary)
            setHintTextColor(ui.muted)
        }
        content.addView(input, LinearLayout.LayoutParams(-1, ui.dp(56)))

        fun updateList() {
            val ids = readIds(key)
            captions[key]?.text = "$description · ${ids.size} added"
            list.removeAllViews()
            if (ids.isEmpty()) {
                list.addView(
                    ui.text("No IDs added", 14f, ui.muted).apply { setPadding(0, ui.dp(16), 0, 0) },
                )
            }
            ids.forEach { id ->
                val row = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(
                    ui.text(id.toString(), 14f).apply { setTextIsSelectable(true) },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
                row.addView(
                    ui.smallButton("Remove") {
                        val updated = readIds(key).apply { remove(id) }
                        settings.setString(key, updated.joinToString(","))
                        filtersChanged()
                        updateList()
                    },
                )
                list.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        updateList()
        val dialog = AlertDialog
            .Builder(requireContext())
            .setTitle(title)
            .setView(content)
            .setNegativeButton("Close", null)
            .setPositiveButton("Add", null)
            .create()
        showDialog(dialog) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = input.text.toString().trim().toLongOrNull()?.takeIf { it > 0 }
                when {
                    id == null -> {
                        input.error = "Enter a positive numeric ID"
                    }

                    id in readIds(key) -> {
                        input.error = "This ID is already added"
                    }

                    else -> {
                        settings.setString(key, readIds(key).apply { add(id) }.joinToString(","))
                        input.text.clear()
                        filtersChanged()
                        updateList()
                    }
                }
            }
        }
    }

    private fun filtersChanged() {
        BetterMessageLogger.instance?.settingsChanged()
        updateStorage()
    }

    private fun colorValue(key: String): String = settings
        .getString(
            key,
            if (key ==
                Keys.DELETED_LABEL_COLOR
            ) {
                Keys.DEFAULT_DELETED_LABEL_COLOR
            } else {
                Keys.DEFAULT_DELETED_MESSAGE_COLOR
            },
        ).let { raw ->
            parseColor(raw)?.let(::hex)
                ?: if (key ==
                    Keys.DELETED_LABEL_COLOR
                ) {
                    Keys.DEFAULT_DELETED_LABEL_COLOR
                } else {
                    Keys.DEFAULT_DELETED_MESSAGE_COLOR
                }
        }

    private fun updateColors() {
        listOf(Keys.DELETED_LABEL_COLOR, Keys.DELETED_MESSAGE_COLOR).forEach { captions[it]?.text = colorValue(it) }
        val message = "A deleted message"
        val tag = if (settings.getBool(Keys.SHOW_DELETED_TAG, true)) " (deleted)" else ""
        preview?.text = SpannableString(message + tag).apply {
            setSpan(ForegroundColorSpan(Color.parseColor(colorValue(Keys.DELETED_MESSAGE_COLOR))), 0, message.length, 0)
            if (tag.isNotEmpty()) {
                setSpan(
                    ForegroundColorSpan(Color.parseColor(colorValue(Keys.DELETED_LABEL_COLOR))),
                    message.length,
                    length,
                    0,
                )
            }
        }
    }

    private fun colorDialog(key: String, title: String) {
        val content = ui.column().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8)) }
        content.addView(
            ui.text(
                "Drag to choose a color or enter a hex value. " +
                    "The first two digits in an 8-digit value control opacity.",
                13f,
                ui.muted,
            ),
        )
        val picker = ColorPickerView(requireContext(), Color.parseColor(colorValue(key)))
        content.addView(picker, LinearLayout.LayoutParams(-1, ui.dp(210)).apply { topMargin = ui.dp(16) })
        val sample = ui.text("Deleted message preview", 16f).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(16), 0, ui.dp(8))
        }
        content.addView(sample)
        val input = EditText(requireContext()).apply {
            hint = "#RRGGBB or #AARRGGBB"
            setText(colorValue(key))
            setTextColor(ui.primary)
            setHintTextColor(ui.muted)
            setSingleLine(true)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        content.addView(input)
        sample.setTextColor(picker.color)
        var updating = false
        picker.onColorChanged = { color ->
            updating = true
            input.setText(hex(color))
            input.setSelection(input.length())
            updating = false
            sample.setTextColor(color)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                parseColor(s.toString())?.let {
                    picker.color = it
                    sample.setTextColor(it)
                }
            }
        })
        val dialog = AlertDialog
            .Builder(requireContext())
            .setTitle(title)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        showDialog(dialog) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val color = parseColor(input.text.toString())
                if (color == null) {
                    input.error = "Use #RRGGBB or #AARRGGBB"
                } else {
                    settings.setString(key, hex(color))
                    updateColors()
                    BetterMessageLogger.instance?.refreshAppearance()
                    dialog.dismiss()
                }
            }
        }
    }

    private fun parseColor(raw: String?): Int? {
        val value = raw.orEmpty().trim().removePrefix("#")
        if ((value.length != 6 && value.length != 8) || value.any { it !in "0123456789abcdefABCDEF" }) return null
        return runCatching { Color.parseColor("#$value") }.getOrNull()
    }

    private fun hex(color: Int) = String.format(Locale.ROOT, "#%08X", color)

    private fun showDialog(value: AlertDialog, onShow: (() -> Unit)? = null) {
        dialog?.dismiss()
        dialog = value
        value.setOnShowListener {
            ui.style(value)
            onShow?.invoke()
        }
        value.show()
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        storageCaption = null
        databaseToggle = null
        preview = null
        captions.clear()
        super.onDestroyView()
    }
}
