package xyz.wingio.plugins

import android.content.Context
import android.util.AttributeSet
import android.view.Menu
import android.view.View
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.databinding.WidgetHomeBinding
import com.discord.databinding.WidgetUserSheetBinding
import com.discord.views.channelsidebar.PrivateChannelSideBarActionsView
import com.discord.widgets.friends.WidgetFriendsListAdapter
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.home.WidgetHomeHeaderManager
import com.discord.widgets.home.WidgetHomeModel
import com.discord.widgets.user.calls.PrivateCallLauncher
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel
import com.discord.widgets.voice.controls.VoiceControlsSheetView
import com.discord.widgets.voice.model.CallModel
import java.lang.ref.WeakReference

@AliucordPlugin
class HideCallButtons : Plugin() {
    private var currentHome: WeakReference<WidgetHome>? = null

    override fun start(context: Context) {
        settingsTab = SettingsTab(HideCallButtonsSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
        commands.registerCommand("call", "Start a voice call with this DM recipient") { commandContext ->
            val channel = commandContext.currentChannel
            val home = currentHome?.get()
            when {
                channel == null || !channel.isDM() -> {
                    CommandsAPI.CommandResult("This command can only be used in a direct message.")
                }

                home == null || !home.isAdded -> {
                    CommandsAPI.CommandResult("Unable to start a call from the current screen.")
                }

                else -> {
                    val channelId = channel.id
                    val callContext = commandContext.context
                    Utils.mainThread.post {
                        try {
                            if (home.isAdded) {
                                PrivateCallLauncher(home, home, callContext, home.parentFragmentManager)
                                    .launchVoiceCall(channelId)
                            }
                        } catch (_: Throwable) {
                            // The screen can close while the command is being dispatched.
                        }
                    }
                    null
                }
            }
        }

        val bindingMethod = WidgetUserSheet::class.java.getDeclaredMethod("getBinding").apply { isAccessible = true }
        val videoId = Utils.getResId("user_sheet_video_action_button", "id")
        val callId = Utils.getResId("user_sheet_call_action_button", "id")
        patcher.patch(
            WidgetUserSheet::class.java,
            "configureNote",
            arrayOf(WidgetUserSheetViewModel.ViewState.Loaded::class.java),
            Hook { frame ->
                if (settings.getBool(HIDE_PROFILE_SHEET, false)) {
                    val binding = bindingMethod.invoke(frame.thisObject) as WidgetUserSheetBinding
                    hideView(binding.root, videoId)
                    hideView(binding.root, callId)
                }
            },
        )

        val topbarCallId = Utils.getResId("menu_chat_start_call", "id")
        val topbarVideoId = Utils.getResId("menu_chat_start_video_call", "id")
        patcher.patch(
            WidgetHomeHeaderManager::class.java.getDeclaredMethod(
                "configure",
                WidgetHome::class.java,
                WidgetHomeModel::class.java,
                WidgetHomeBinding::class.java,
            ),
            Hook { frame ->
                val home = frame.args[0] as WidgetHome
                currentHome = WeakReference(home)
                if (settings.getBool(HIDE_DM_TOPBAR, false)) {
                    hideMenuItem(home.toolbar?.menu, topbarCallId)
                    hideMenuItem(home.toolbar?.menu, topbarVideoId)
                }
            },
        )
        patcher.patch(
            PrivateChannelSideBarActionsView::class.java.getDeclaredConstructor(
                Context::class.java,
                AttributeSet::class.java,
            ),
            Hook { frame ->
                if (settings.getBool(HIDE_DM_MEMBER_LIST, false)) {
                    val actions = frame.thisObject as PrivateChannelSideBarActionsView
                    hideView(actions, "private_channel_sidebar_actions_call")
                    hideView(actions, "private_channel_sidebar_actions_video")
                }
            },
        )
        patcher.patch(
            WidgetFriendsListAdapter.ItemUser::class.java.getDeclaredConstructor(WidgetFriendsListAdapter::class.java),
            Hook { frame ->
                if (settings.getBool(HIDE_FRIEND_LIST, false)) {
                    val item = frame.thisObject as WidgetFriendsListAdapter.ItemUser
                    hideView(item.itemView, "friends_list_item_call_button")
                }
            },
        )
        patcher.patch(
            VoiceControlsSheetView::class.java.getDeclaredMethod(
                "configureVideoButton",
                CallModel::class.java,
                Function0::class.java,
                Boolean::class.javaPrimitiveType!!,
            ),
            Hook { frame ->
                if (settings.getBool(HIDE_VC_CAMERA, false)) {
                    hideView(frame.thisObject as VoiceControlsSheetView, "video_button")
                }
            },
        )
    }

    private fun hideMenuItem(menu: Menu?, id: Int) {
        if (id != 0) menu?.findItem(id)?.isVisible = false
    }

    private fun hideView(root: View, name: String) {
        hideView(root, Utils.getResId(name, "id"))
    }

    private fun hideView(root: View, id: Int) {
        if (id != 0) root.findViewById<View>(id)?.visibility = View.GONE
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
        currentHome = null
    }

    companion object {
        internal const val HIDE_DM_TOPBAR = "hideDmTopbar"
        internal const val HIDE_DM_MEMBER_LIST = "hideDmMemberList"
        internal const val HIDE_PROFILE_SHEET = "hideProfileSheet"
        internal const val HIDE_FRIEND_LIST = "hideFriendList"
        internal const val HIDE_VC_CAMERA = "hideVcCamera"
    }
}
