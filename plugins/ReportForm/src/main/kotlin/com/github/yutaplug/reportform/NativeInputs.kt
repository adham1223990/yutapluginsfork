package com.github.yutaplug.reportform

import android.content.Intent
import android.net.Uri
import android.text.InputFilter
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.discord.api.report.ReportNode
import com.discord.views.CheckedSetting
import com.discord.widgets.mobile_reports.ReportsMenuNode
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lytefast.flexinput.R
import org.json.JSONObject
import java.util.regex.Pattern

/** New inputs use Discord's compiled UiKit styles within the native fullscreen report node. */
internal class NativeInputs(
    private val view: ReportsMenuNode,
    val node: ReportNode,
    val extras: ReportMenus.Extras,
) {
    private val context = view.context
    private val container = LinearLayout(ContextThemeWrapper(context, R.i.UiKit_ViewGroup_LinearLayout)).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private val errors = label("")
    private val readers = mutableListOf<() -> Pair<String, List<String>>?>()
    private val validators = mutableListOf<() -> Boolean>()
    private var unsupported = false

    init {
        val anchor = view.findViewById<View>(Utils.getResId("mobile_reports_node_child_list", "id"))
        val host = anchor?.parent as? LinearLayout
            ?: view.findViewById<View>(Utils.getResId("mobile_reports_node_header", "id")).parent as LinearLayout
        host.addView(container, host.indexOfChild(anchor).let { if (it < 0) host.childCount else it })
        extras.raw.string("description")?.let { container.addView(label(it)) }
        val elements = extras.raw.optJSONArray("elements")
        if (elements != null) {
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                if (element.optBoolean("skip_if_unlocalized") && !element.optBoolean("is_localized", true)) continue
                when (element.optString("type")) {
                    "free_text" -> {
                        freeText(element)
                    }

                    "dropdown" -> {
                        dropdown(element)
                    }

                    "text", "text_line_resource" -> {
                        element.optJSONObject("data")?.let { data ->
                            data.string("header")?.let { container.addView(label(it)) }
                            data.string("title")?.let { container.addView(label(it)) }
                            data.string("body")?.let { container.addView(label(it)) }
                            data.string("sms")?.let { container.addView(label("Text $it")) }
                        }
                    }

                    "external_link" -> {
                        element.optJSONObject("data")?.let { data ->
                            data.string("url")?.let { link(data.optString("link_text", "Open link"), it) }
                            data.string("link_description")?.let { container.addView(label(it)) }
                        }
                    }

                    "checkbox", "message_preview", "breadcrumbs", "success", "block_users", "ignore_users",
                    "mute_users", "delete_message", "leave_guild", "share_with_parents", "settings_upsells",
                    "more_you_can_do",
                    -> {}

                    else -> {
                        if (element.optBoolean("should_submit_data")) unsupported = true
                    }
                }
            }
        }
        container.addView(errors)
        if (unsupported) error("This category requires an input this plugin does not support yet.")
    }

    fun remove() = (container.parent as? ViewGroup)?.removeView(container)

    fun setLoading(loading: Boolean) {
        fun enable(child: View) {
            child.isEnabled = !loading
            if (child is ViewGroup) for (i in 0 until child.childCount) enable(child.getChildAt(i))
        }
        enable(container)
    }

    fun error(message: String) {
        errors.text = message
    }

    fun save(): Boolean {
        if (unsupported) return false
        if (!validators.map { it() }.all { it }) {
            error("Complete the required fields before continuing.")
            return false
        }
        extras.session.answers[node.e()] = mutableMapOf<String, List<String>>().apply {
            readers.forEach { read -> read()?.let { put(it.first, it.second) } }
        }
        errors.text = ""
        return true
    }

    private fun freeText(element: JSONObject) {
        val data = element.getJSONObject("data")
        val name = element.getString("name")
        data.string("title")?.let { container.addView(label(it)) }
        data.string("subtitle")?.let { container.addView(label(it)) }
        val input = TextInputEditText(ContextThemeWrapper(context, R.i.UiKit_TextInputLayout_EditText)).apply {
            minLines = data.optInt("rows", 3).coerceIn(1, 8)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(data.optInt("character_limit", 4000).coerceAtLeast(1)))
            setText(extras.session.answers[node.e()]?.get(name)?.firstOrNull().orEmpty())
        }
        val wrapper = TextInputLayout(ContextThemeWrapper(context, R.i.UiKit_TextInputLayout)).apply {
            hint = data.optString("placeholder")
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        container.addView(wrapper, LinearLayout.LayoutParams(-1, -2))
        validators.add {
            val value = input.text.toString()
            val pattern = data.string("pattern")
            val valid = (hasText(value) || !element.optBoolean("required")) &&
                (pattern == null || runCatching { Pattern.compile(pattern).matcher(value).find() }.getOrDefault(false))
            wrapper.error = if (valid) null else "Please enter a valid value"
            valid
        }
        readers.add {
            if (element.optBoolean(
                    "should_submit_data",
                    true,
                )
            ) {
                name to listOf(input.text.toString())
            } else {
                null
            }
        }
    }

    private fun dropdown(element: JSONObject) {
        val data = element.getJSONObject("data")
        val name = element.getString("name")
        data.string("title")?.let { container.addView(label(it)) }
        val options = data.getJSONArray("options")
        var selected = extras.session.answers[node.e()]?.get(name)?.firstOrNull()
        val choices = mutableListOf<CheckedSetting>()
        for (i in 0 until options.length()) {
            val option = options.getJSONObject(i)
            val value = option.getString("value")
            val choice = LayoutInflater.from(context).inflate(
                Utils.getResId("view_mobile_reports_multicheck_item", "layout"),
                container,
                false,
            ) as CheckedSetting
            choice.setText(option.getString("label"))
            choice.isChecked = value == selected
            choice.setOnCheckedListener { checked ->
                if (checked) {
                    selected = value
                    for (other in choices) if (other !== choice) other.isChecked = false
                } else if (selected == value) {
                    selected = null
                }
            }
            choices.add(choice)
            container.addView(choice)
        }
        validators.add { selected != null }
        readers.add { if (element.optBoolean("should_submit_data", true)) name to listOfNotNull(selected) else null }
    }

    private fun label(value: String) = TextView(context, null, 0, R.i.UiKit_TextView).apply { text = value }

    private fun link(title: String, url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return
        val child = LayoutInflater.from(context).inflate(
            Utils.getResId("view_mobile_reports_child", "layout"),
            container,
            false,
        )
        child.findViewById<TextView>(Utils.getResId("mobile_reports_child_menu_title", "id")).text = title
        child.setOnClickListener {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                .onFailure { error("Couldn't open this link.") }
        }
        container.addView(child)
    }

    private fun JSONObject.string(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { hasText(it) }

    private fun hasText(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (!Character.isWhitespace(char) && !Character.isSpaceChar(char)) return true
            index++
        }
        return false
    }
}
