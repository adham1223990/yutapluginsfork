package com.github.yutaplug.profileeffects

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.app.AppBottomSheet
import com.discord.utilities.accessibility.AccessibilityUtils
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

@AliucordPlugin
class ProfileEffects : Plugin() {
    private class Binding(val key: ProfileKey, var animate: Boolean) {
        var profile = Profile()
        var overlay: ProfileOverlay? = null
        var detachListener: View.OnAttachStateChangeListener? = null
        var loading = false
        var refreshAt = 0L
    }

    private val main = Handler(Looper.getMainLooper())
    private val bindings = IdentityHashMap<UserProfileHeaderView, Binding>()
    private val clips = ClipOwners()
    private var repository: ProfileRepository? = null

    override fun start(context: Context) {
        repository = ProfileRepository { message, error -> logger.error(message, error) }
        patcher.patch(
            UserProfileHeaderView::class.java,
            "updateViewState",
            arrayOf(UserProfileHeaderViewModel.ViewState.Loaded::class.java),
            Hook { call ->
                val header = call.thisObject as UserProfileHeaderView
                if (!header.hasResourceName("user_sheet_profile_header_view")) return@Hook
                val state = call.args[0] as UserProfileHeaderViewModel.ViewState.Loaded
                val id = state.user.id
                val guild = state.guildMember?.guildId?.takeIf { it > 0 }
                    ?: bindings[header]?.key?.takeIf { it.userId == id }?.guildId
                bind(header, ProfileKey(id, guild), !state.reducedMotionEnabled || state.allowAnimationInReducedMotion)
            },
        )
        // The sheet supplies its guild even when membership hasn't loaded into the header yet.
        patcher.patch(
            WidgetUserSheet::class.java,
            "configureUI",
            arrayOf(WidgetUserSheetViewModel.ViewState::class.java),
            Hook { call ->
                val sheet = call.thisObject as WidgetUserSheet
                val header = header(sheet) ?: return@Hook
                val state = call.args[0] as? WidgetUserSheetViewModel.ViewState.Loaded
                if (state == null) {
                    release(header)
                    return@Hook
                }
                val guild = state.guildMember?.guildId?.takeIf { it > 0 }
                    ?: state.currentGuildId?.takeIf { it > 0 }
                val animate = bindings[header]?.animate ?: !AccessibilityUtils.INSTANCE.isReducedMotionEnabled()
                bind(header, ProfileKey(state.user.id, guild), animate)
            },
        )
        patcher.patch(
            WidgetUserSheet::class.java,
            "onResume",
            emptyArray(),
            Hook { call ->
                val header = header(call.thisObject as WidgetUserSheet) ?: return@Hook
                val binding = bindings[header] ?: return@Hook
                bind(header, binding.key, binding.animate)
                binding.overlay?.restartAnimation()
            },
        )
        // This method is inherited, so patch the declaring class and restrict the callback to user sheets.
        patcher.patch(
            AppBottomSheet::class.java,
            "onDestroyView",
            emptyArray(),
            PreHook { call ->
                val sheet = call.thisObject as? WidgetUserSheet ?: return@PreHook
                header(sheet)?.let(::release)
            },
        )
    }

    private fun header(sheet: WidgetUserSheet): UserProfileHeaderView? = sheet.view
        ?.findViewById(com.aliucord.Utils.getResId("user_sheet_profile_header_view", "id"))

    private fun bind(header: UserProfileHeaderView, key: ProfileKey, animate: Boolean) {
        val repository = repository ?: return
        if (key.userId <= 0) {
            release(header)
            return
        }
        var binding = bindings[header]
        if (binding == null || binding.key != key) {
            release(header)
            binding = Binding(key, animate)
            bindings[header] = binding
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    val current = bindings[header] ?: return
                    // Adding sibling overlays must also wait for Android's attach traversal to finish.
                    main.post { if (bindings[header] === current) render(header, current) }
                }

                override fun onViewDetachedFromWindow(view: View) = release(header)
            }
            binding.detachListener = listener
            header.addOnAttachStateChangeListener(listener)
        }
        binding.animate = animate
        render(header, binding)
        if (binding.loading || now() < binding.refreshAt) return
        binding.loading = true
        val weakBinding = WeakReference(binding)
        val weakHeader = WeakReference(header)
        repository.load(key) { result ->
            // Network completions never hold a sheet/activity alive or touch Android views off the main thread.
            main.post {
                if (this.repository !== repository) return@post
                val target = weakHeader.get() ?: return@post
                val current = weakBinding.get() ?: return@post
                if (bindings[target] !== current) return@post
                if (result.complete) {
                    current.loading = false
                    current.refreshAt = now() + result.ttl
                }
                current.profile = result.value ?: Profile()
                render(target, current)
            }
        }
    }

    private fun render(header: UserProfileHeaderView, binding: Binding) {
        if (repository == null || bindings[header] !== binding || header.windowToken == null) return
        if (binding.profile.effect == null && binding.profile.frame == null) {
            binding.overlay?.dispose()
            binding.overlay = null
            return
        }
        try {
            var overlay = binding.overlay
            if (overlay != null && !overlay.matches(header)) {
                overlay.dispose()
                overlay = null
            }
            if (overlay == null) {
                overlay = ProfileOverlay.create(header, clips) { message, error -> logger.error(message, error) }
                binding.overlay = overlay
            }
            overlay?.bind(binding.profile, binding.animate)
        } catch (error: Exception) {
            binding.overlay?.dispose()
            binding.overlay = null
            logger.error("Could not attach profile decorations", error)
        }
    }

    private fun release(header: UserProfileHeaderView) {
        val binding = bindings.remove(header) ?: return
        binding.detachListener?.let { header.removeOnAttachStateChangeListener(it) }
        binding.overlay?.dispose()
    }

    private fun now() = System.nanoTime() / 1_000_000L

    override fun stop(context: Context) {
        repository?.close()
        repository = null
        patcher.unpatchAll()
        main.removeCallbacksAndMessages(null)
        for (header in ArrayList(bindings.keys)) release(header)
    }
}
