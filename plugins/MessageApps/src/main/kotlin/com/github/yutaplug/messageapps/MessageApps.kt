package com.github.yutaplug.messageapps

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin
class MessageApps : Plugin() {
    private val rows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val pages = Collections.newSetFromMap(WeakHashMap<AppsPage, Boolean>())
    private val rowId = View.generateViewId()

    @Volatile internal var active = false
        private set

    override fun start(context: Context) {
        active = true
        patcher.patch(
            WidgetChatListActions::class.java.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            Hook { call ->
                val actions = call.thisObject as WidgetChatListActions
                val model = call.args[0] as WidgetChatListActions.Model
                val channel = model.channel ?: return@Hook
                val container = actions.view?.findViewById<LinearLayout>(
                    Utils.getResId("dialog_chat_actions_container", "id"),
                ) ?: return@Hook
                val row = container.findViewById<TextView>(rowId) ?: run {
                    val template = LayoutInflater.from(container.context).inflate(
                        Utils.getResId("widget_chat_list_actions", "layout"),
                        null,
                    ) as ViewGroup
                    val native = template.findViewById<TextView>(Utils.getResId("dialog_chat_actions_reply", "id"))
                    (native.parent as ViewGroup).removeView(native)
                    native.id = rowId
                    native.text = "Apps"
                    val icon = androidx.core.content.ContextCompat.getDrawable(
                        container.context,
                        Utils.getResId("ic_slash_command_24dp", "drawable"),
                    )
                    val arrow = androidx.core.content.ContextCompat.getDrawable(
                        container.context,
                        Utils.getResId("exo_ic_chevron_right", "drawable"),
                    )
                    native.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, arrow, null)
                    val pin = container.findViewById<View>(Utils.getResId("dialog_chat_actions_pin", "id"))
                    val insertion = if (pin == null) container.childCount else container.indexOfChild(pin) + 1
                    container.addView(native, insertion)
                    rows.add(native)
                    native
                }
                row.visibility = if (model.message.isLocal || model.message.id <= 0L) View.GONE else View.VISIBLE
                row.setOnClickListener {
                    if (!active) return@setOnClickListener
                    val page = AppsPage(this, MessageTarget(channel.k(), channel.i(), model.message.id))
                    pages.add(page)
                    Utils.openPageWithProxy(container.context, page)
                    actions.dismiss()
                }
            },
        )
    }

    internal fun register(page: AppsPage) {
        pages.add(page)
    }

    override fun stop(context: Context) {
        active = false
        patcher.unpatchAll()
        rows.toList().forEach { (it.parent as? ViewGroup)?.removeView(it) }
        rows.clear()
        pages.toList().forEach { it.close() }
        pages.clear()
    }
}
