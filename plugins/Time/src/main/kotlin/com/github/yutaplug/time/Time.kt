package com.github.yutaplug.time

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.ReflectUtils
import com.discord.simpleast.core.parser.Rule
import com.discord.utilities.textprocessing.node.TimestampNode
import com.discord.widgets.chat.input.WidgetChatInputEditText
import com.discord.widgets.chat.input.autocomplete.InputAutocomplete
import com.discord.widgets.chat.input.autocomplete.ViewState
import com.lytefast.flexinput.widget.FlexEditText
import java.text.DateFormat
import java.util.Date
import java.util.regex.Pattern

@AliucordPlugin
class Time : Plugin() {
    private val handler = Handler(Looper.getMainLooper())
    private val pickers = mutableMapOf<FlexEditText, Picker>()
    private val timestampPattern = Pattern.compile("^<t:(-?\\d{1,17})(?::(t|T|d|D|f|F|R|s|S))?>")
    private var running = false

    override fun start(context: Context) {
        running = true
        patchTimestamps()
        patcher.patch(
            Class.forName("com.discord.widgets.chat.input.WidgetChatInputEditText\$setOnTextChangedListener\$1"),
            "afterTextChanged",
            arrayOf(Editable::class.java),
            Hook { frame ->
                val owner = ReflectUtils.getField(frame.thisObject, "this\$0") as WidgetChatInputEditText
                val input = ReflectUtils.getField(owner, "editText") as FlexEditText
                // Wait until Discord has finished updating its own autocomplete views.
                handler.post { if (running) update(input) }
            },
        )
        patcher.patch(
            InputAutocomplete::class.java,
            "configureUI",
            arrayOf(ViewState::class.java),
            Hook { frame ->
                if (running) update(ReflectUtils.getField(frame.thisObject, "editText") as FlexEditText)
            },
        )
    }

    private fun patchTimestamps() {
        // 126.21 only recognizes the original seven styles. Keep the existing parser and renderer,
        // extending recognition to the two date/time styles now used by desktop.
        patcher.patch(
            Rule::class.java,
            "match",
            arrayOf(CharSequence::class.java, String::class.java, Any::class.java),
            Hook { frame ->
                if (frame.thisObject.javaClass.name !=
                    "com.discord.utilities.textprocessing.Rules\$createTimestampRule\$1"
                ) {
                    return@Hook
                }
                if (frame.result != null) return@Hook
                val matcher = timestampPattern.matcher(frame.args[0] as CharSequence)
                if (matcher.find()) frame.result = matcher
            },
        )
        patcher.patch(
            TimestampNode::class.java.getDeclaredConstructor(String::class.java, String::class.java),
            Hook { frame ->
                val style = frame.args[1] as? String
                if (style != "s" && style != "S") return@Hook
                val seconds = (frame.args[0] as String).toLongOrNull() ?: return@Hook
                ReflectUtils.setField(frame.thisObject, "formatted", shortDateTime(seconds, style))
            },
        )
    }

    private fun shortDateTime(seconds: Long, style: String): String = DateFormat
        .getDateTimeInstance(
            DateFormat.SHORT,
            if (style == "S") DateFormat.MEDIUM else DateFormat.SHORT,
        ).format(Date(seconds * 1000))

    private fun token(input: FlexEditText): IntRange? {
        val text = input.text ?: return null
        val cursor = input.selectionStart
        if (cursor < 5 || cursor > text.length || cursor != input.selectionEnd) return null
        val start = cursor - 5
        if (text.subSequence(start, cursor).toString() != "@time") return null
        if (start > 0 && !text[start - 1].isWhitespace()) return null
        if (cursor < text.length && !text[cursor].isWhitespace()) return null
        // A literal @time inside inline/fenced code or escaped text is not a command.
        val prefix = text.subSequence(0, start).toString()
        if (prefix.count { it == '`' } % 2 != 0) return null
        return start until cursor
    }

    private fun update(input: FlexEditText) {
        if (!input.isAttachedToWindow) return
        val range = token(input)
        if (range == null || !input.hasFocus() || !input.isShown) {
            pickers[input]?.hide()
            return
        }
        val picker = pickers[input] ?: createPicker(input)?.also { pickers[input] = it } ?: return
        picker.show(range)
    }

    private fun createPicker(input: FlexEditText): Picker? {
        val wrap = input.rootView.findViewById<LinearLayout>(Utils.getResId("chat_input_wrap", "id")) ?: return null
        val contextBar = wrap.findViewById<View>(Utils.getResId("chat_input_context_bar", "id")) ?: return null
        val panel = LayoutInflater.from(input.context).inflate(
            Utils.getResId("widget_chat_input_application_commands", "layout"),
            wrap,
            false,
        ) as ViewGroup
        panel.visibility = View.GONE
        // This is a separate native-layout instance, not the selected slash command's view.
        panel.id = View.NO_ID
        wrap.addView(panel, wrap.indexOfChild(contextBar))
        return Picker(input, panel).also { picker ->
            input.addOnAttachStateChangeListener(picker)
        }
    }

    private inner class Picker(val input: FlexEditText, val panel: ViewGroup) : View.OnAttachStateChangeListener {
        private val formats = listOf("S", "f", "F", "R")
        private val labels = listOf("Short date and time", "Long date and time", "Full date and time", "Relative time")
        private var seconds = 0L
        private var range: IntRange? = null
        private val recycler = panel.findViewById<RecyclerView>(
            Utils.getResId("chat_input_application_commands_recycler", "id"),
        )
        private val nativeViews = listOf(
            "chat_input_mentions_recycler",
            "chat_input_emoji_matching_header",
            "chat_input_categories_recycler",
        ).mapNotNull { input.rootView.findViewById<View>(Utils.getResId(it, "id")) }
        private val hiddenViews = mutableMapOf<View, Int>()

        init {
            panel
                .findViewById<TextView>(
                    Utils.getResId("chat_input_application_commands_option_description", "id"),
                ).text = "@time · Refer to a time in the viewer's time zone"
            recycler.layoutManager = LinearLayoutManager(input.context)
            recycler.adapter = object : RecyclerView.Adapter<Row>() {
                override fun getItemCount() = formats.size

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row = Row(
                    LayoutInflater.from(parent.context).inflate(
                        Utils.getResId("widget_chat_input_autocomplete_item", "layout"),
                        parent,
                        false,
                    ),
                )

                override fun onBindViewHolder(holder: Row, position: Int) {
                    val style = formats[position]
                    val preview = if (style == "S") {
                        shortDateTime(seconds, style)
                    } else {
                        TimestampNode<TimestampNode.RenderContext>(seconds.toString(), style).formatted
                    }
                    holder.itemView.findViewById<TextView>(Utils.getResId("chat_input_item_name", "id")).text = preview
                    holder.itemView
                        .findViewById<TextView>(
                            Utils.getResId("chat_input_item_description", "id"),
                        ).apply {
                            text = labels[position]
                            visibility = View.VISIBLE
                        }
                    holder.itemView
                        .findViewById<View>(Utils.getResId("chat_input_item_emoji", "id"))
                        .visibility = View.GONE
                    holder.itemView
                        .findViewById<View>(Utils.getResId("chat_input_item_status", "id"))
                        .visibility = View.GONE
                    holder.itemView.setOnClickListener { insert(style) }
                }
            }
        }

        fun show(newRange: IntRange) {
            if (panel.visibility != View.VISIBLE || range != newRange) {
                seconds = System.currentTimeMillis() / 1000
                range = newRange
                recycler.adapter?.notifyDataSetChanged()
            }
            for (view in nativeViews) {
                if (view.visibility != View.GONE) hiddenViews[view] = view.visibility
                view.visibility = View.GONE
            }
            panel.visibility = View.VISIBLE
        }

        private fun insert(style: String) {
            val selectedRange = range ?: return
            if (token(input) != selectedRange || !input.isAttachedToWindow) return
            val timestamp = "<t:$seconds:$style>"
            val end = selectedRange.last + 1
            val suffix = if (end == input.text!!.length) " " else ""
            hide()
            input.text?.replace(selectedRange.first, end, timestamp + suffix)
            input.setSelection(selectedRange.first + timestamp.length + suffix.length)
        }

        fun hide(restoreViews: Boolean = false) {
            panel.visibility = View.GONE
            range = null
            if (restoreViews) {
                for ((view, visibility) in hiddenViews) view.visibility = visibility
            }
            hiddenViews.clear()
        }

        fun remove() {
            hide(restoreViews = true)
            input.removeOnAttachStateChangeListener(this)
            (panel.parent as? ViewGroup)?.removeView(panel)
        }

        override fun onViewAttachedToWindow(view: View) {}

        override fun onViewDetachedFromWindow(view: View) {
            pickers.remove(input)
            remove()
        }
    }

    private class Row(view: View) : RecyclerView.ViewHolder(view)

    private fun clearPickers() {
        pickers.values.toList().forEach { it.remove() }
        pickers.clear()
    }

    override fun stop(context: Context) {
        running = false
        patcher.unpatchAll()
        handler.removeCallbacksAndMessages(null)
        if (Looper.myLooper() == Looper.getMainLooper()) clearPickers() else handler.post { clearPickers() }
    }
}
