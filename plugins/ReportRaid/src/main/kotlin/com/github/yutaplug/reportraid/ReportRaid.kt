package com.github.yutaplug.reportraid

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.app.AppBottomSheet
import com.discord.stores.StoreStream
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheet
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheetViewModel
import java.lang.ref.WeakReference
import java.util.WeakHashMap

@AliucordPlugin
class ReportRaid : Plugin() {
    private val rows = WeakHashMap<WidgetGuildProfileSheet, WeakReference<TextView>>()
    private val pages = WeakHashMap<ReportRaidPage, Boolean>()
    private var active = false

    override fun start(context: Context) {
        active = true
        patcher.patch(
            WidgetGuildProfileSheet::class.java,
            "configureGuildActions",
            arrayOf(Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
                WidgetGuildProfileSheetViewModel.Actions::class.java),
            Hook { call ->
                if (!active) return@Hook
                val sheet = call.thisObject as WidgetGuildProfileSheet
                val guildId = call.args[0] as Long
                val root = sheet.view ?: return@Hook
                val eligible = call.args[2] != null && canReport(guildId)
                val previous = rows[sheet]?.get()
                if (!eligible) {
                    removeRow(sheet)
                    return@Hook
                }
                val leave = root.findViewById<TextView>(Utils.getResId("guild_profile_sheet_leave_server", "id"))
                    ?: return@Hook
                val parent = leave.parent as? ViewGroup ?: return@Hook
                val row = previous?.takeIf { it.parent === parent } ?: run {
                    removeRow(sheet)
                    val layoutId = Utils.getResId("widget_guild_profile_actions", "layout")
                    if (layoutId == 0) return@Hook
                    // Inflate the actual sheet row to preserve Discord's text appearance,
                    // font loading, padding, pressed background, and danger color.
                    val template = LayoutInflater.from(parent.context).inflate(layoutId, null)
                    val nativeRow = template.findViewById<TextView>(leave.id) ?: return@Hook
                    (nativeRow.parent as ViewGroup).removeView(nativeRow)
                    nativeRow.id = View.generateViewId()
                    nativeRow.text = "Report Raid"
                    nativeRow.visibility = View.VISIBLE
                    parent.addView(nativeRow, parent.indexOfChild(leave))
                    rows[sheet] = WeakReference(nativeRow)
                    nativeRow
                }
                val owner = WeakReference(sheet)
                row.setOnClickListener {
                    val currentSheet = owner.get() ?: return@setOnClickListener
                    if (!active || !canReport(guildId)) return@setOnClickListener
                    if (pages.keys.any { it.guildId == guildId && it.isOpen }) return@setOnClickListener
                    val page = ReportRaidPage.create(guildId)
                    pages[page] = true
                    Utils.openPageWithProxy(row.context, page)
                    currentSheet.dismiss()
                }
            },
        )
        patcher.patch(AppBottomSheet::class.java, "onDestroyView", emptyArray(), Hook { call ->
            (call.thisObject as? WidgetGuildProfileSheet)?.let(::removeRow)
        })
    }

    private fun removeRow(sheet: WidgetGuildProfileSheet) {
        rows.remove(sheet)?.get()?.let {
            it.setOnClickListener(null)
            (it.parent as? ViewGroup)?.removeView(it)
        }
    }

    override fun stop(context: Context) {
        active = false
        patcher.unpatchAll()
        rows.keys.toList().forEach(::removeRow)
        pages.keys.toList().forEach { it.close() }
        pages.clear()
    }

    companion object {
        internal fun canReport(guildId: Long): Boolean {
            val guild = StoreStream.getGuilds().guilds[guildId] ?: return false
            if (!guild.hasFeature(GuildFeature.COMMUNITY)) return false
            val permissions = StoreStream.getPermissions().guildPermissions[guildId] ?: return false
            return permissions and (Permission.ADMINISTRATOR or Permission.MANAGE_GUILD or
                Permission.KICK_MEMBERS or Permission.BAN_MEMBERS) != 0L
        }
    }
}
