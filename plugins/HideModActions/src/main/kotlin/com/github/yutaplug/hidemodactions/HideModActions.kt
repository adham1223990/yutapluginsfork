package com.github.yutaplug.hidemodactions

import android.content.Context
import android.view.View
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.widgets.user.profile.UserProfileAdminView

/** Keeps Manage User and group-DM member removal available. */
@AliucordPlugin
class HideModActions : Plugin() {
    override fun start(context: Context) {
        val actions = HIDDEN_ACTIONS.map { it to Utils.getResId(it, "id") }
        patcher.patch(
            UserProfileAdminView::class.java,
            "updateView",
            arrayOf(UserProfileAdminView.ViewState::class.java),
            Hook { frame ->
                val root = frame.thisObject as UserProfileAdminView
                val state = frame.args[0] as UserProfileAdminView.ViewState
                for ((name, id) in actions) {
                    if (name == "user_profile_admin_kick" && state.isMultiUserDM) continue
                    if (id != 0) root.findViewById<View>(id)?.visibility = View.GONE
                }
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    companion object {
        private val HIDDEN_ACTIONS = listOf(
            "user_profile_admin_kick",
            "user_profile_admin_ban",
            "user_profile_admin_disable_communication",
            "user_profile_admin_server_mute",
            "user_profile_admin_server_deafen",
            "user_profile_admin_server_move",
            "user_profile_admin_server_disconnect",
        )
    }
}
