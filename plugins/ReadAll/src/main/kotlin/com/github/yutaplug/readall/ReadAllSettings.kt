package com.github.yutaplug.readall

import android.os.Bundle
import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting

class ReadAllSettings(private val settings: SettingsAPI, private val plugin: ReadAll) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val context = requireContext()
        val padding = (16 * context.resources.displayMetrics.density + 0.5f).toInt()
        linearLayout.setPadding(padding, padding, padding, padding)

        addView(Utils.createCheckedSetting(
            context,
            CheckedSetting.ViewType.SWITCH,
            "Use /readall command",
            "Replace the DM icon long press menu with one /readall command.",
        ).apply {
            isChecked = settings.getBool(ReadAll.COMMAND_MODE, false)
            setOnCheckedListener { plugin.updateMode(it) }
        })
        addView(Utils.createCheckedSetting(
            context,
            CheckedSetting.ViewType.SWITCH,
            "Include direct messages",
            "Also mark unread DM channels as read.",
        ).apply {
            isChecked = settings.getBool(ReadAll.INCLUDE_DMS, false)
            setOnCheckedListener { settings.setBool(ReadAll.INCLUDE_DMS, it) }
        })
    }
}
