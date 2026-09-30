package com.github.yutaplug.newthemes

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.models.domain.ModelPayload
import com.discord.app.AppActivity
import androidx.appcompat.app.AppCompatActivity
import com.discord.models.domain.ModelUserSettings
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserSettingsSystem
import com.discord.views.CheckedSetting
import com.discord.widgets.settings.WidgetSettingsAppearance
import d0.z.d.d as DiscordCallableReference
import java.util.WeakHashMap

@AliucordPlugin
class NewThemes : Plugin() {
    private class Page(val radios: List<CheckedSetting>, var model: WidgetSettingsAppearance.Model? = null)
    private val pages = WeakHashMap<WidgetSettingsAppearance, Page>()
    private var sync: ThemeSync? = null
    private var palette: ThemePalette? = null
    private val themeNames = WeakHashMap<Resources.Theme, String>()
    @Volatile private var selected = ThemeChoice.ASH

    override fun start(context: Context) {
        val store = StoreStream.getUserSettingsSystem()
        selected = ThemeChoice.fromNative(store.theme)
        val bridge = ThemeSync(context.applicationContext, ::applyTheme) { message, error -> logger.warn(message, error) }
        sync = bridge
        try {
            palette = ThemePalette { selected }.also { it.start(context, patcher) }
            val themeName = Utils.getResId("theme_name", "attr")
            check(themeName != 0) { "Discord theme name attribute unavailable" }
            // AppActivity compares the inflated theme's name to store settings.
            // Keep the name captured at inflation, so a change triggers exactly
            // one recreation instead of continually comparing 'dark' to 'ash'.
            patcher.patch(AppCompatActivity::class.java.getDeclaredMethod("setTheme", Int::class.javaPrimitiveType), Hook {
                val activity = it.thisObject as? AppActivity ?: return@Hook
                synchronized(themeNames) { themeNames[activity.theme] = selected.native }
            })
            patcher.patch(Resources.Theme::class.java.getDeclaredMethod(
                "resolveAttribute", Int::class.javaPrimitiveType, TypedValue::class.java, Boolean::class.javaPrimitiveType,
            ), Hook {
                if (it.result == true && it.args[0] == themeName) {
                    synchronized(themeNames) { themeNames[it.thisObject] }?.let { name ->
                        (it.args[1] as TypedValue).string = name
                    }
                }
            })
            val callback = WidgetSettingsAppearance::class.java.classLoader!!.loadClass(
                "com.discord.widgets.settings.WidgetSettingsAppearance\$onViewBoundOrOnResume\$1",
            )
            patcher.patch(callback.getDeclaredMethod("invoke", Any::class.java), Hook {
                val widget = (it.thisObject as DiscordCallableReference).boundReceiver as WidgetSettingsAppearance
                bind(widget, it.args[0] as WidgetSettingsAppearance.Model)
            })
            patcher.patch(WidgetSettingsAppearance::class.java.getDeclaredMethod("onViewBoundOrOnResume"), Hook {
                bind(it.thisObject as WidgetSettingsAppearance, null)
                bridge.refresh()
            })
            patcher.patch(WidgetSettingsAppearance::class.java.getDeclaredMethod("onDestroyView"), PreHook {
                pages.remove(it.thisObject)?.radios?.forEach { radio -> radio.setOnCheckedListener(null) }
            })
            // Also catch Discord's original click path if ART inlines the UI callback.
            patcher.patch(StoreUserSettingsSystem::class.java.getDeclaredMethod(
                "setTheme", String::class.java, Boolean::class.javaPrimitiveType, Function0::class.java,
            ), PreHook {
                if (it.args[1] == true) {
                    choose(ThemeChoice.fromNative(it.args[0] as String))
                    it.result = null
                }
            })
            patcher.patch(StoreUserSettingsSystem::class.java.getDeclaredMethod("setIsSyncThemeEnabled", Boolean::class.javaPrimitiveType), Hook {
                bridge.syncChanged()
            })
            // The legacy payload collapses Dark and Onyx to Ash. Never let that
            // representation overwrite the four-value protobuf selection.
            patcher.patch(ModelUserSettings::class.java.getDeclaredMethod("getTheme"), Hook {
                if (it.result != null) it.result = selected.native
            })
            patcher.patch(StoreUserSettingsSystem::class.java.getDeclaredMethod("handleConnectionOpen", ModelPayload::class.java), Hook {
                bridge.refresh()
            })
            patcher.patch(StoreUserSettingsSystem::class.java.getDeclaredMethod("handleUserSettingsUpdate", ModelUserSettings::class.java), Hook {
                bridge.refresh()
            })
            bridge.start()
            // Use a distinct native string for each choice so Discord's settings
            // observable recreates activities even between two dark palettes.
            applyTheme(selected)
        } catch (error: Throwable) {
            bridge.close()
            sync = null
            patcher.unpatchAll()
            palette?.clearCaches()
            palette = null
            themeNames.clear()
            throw error
        }
    }

    private fun choose(theme: ThemeChoice) {
        applyTheme(theme)
        sync?.choose(theme)
    }

    private fun applyTheme(theme: ThemeChoice) {
        if (selected != theme) palette?.clearCaches()
        selected = theme
        val store = StoreStream.getUserSettingsSystem()
        if (store.theme != theme.native) store.setTheme(theme.native, false, null)
        pages.values.forEach { page ->
            page.radios.forEachIndexed { index, radio -> radio.g(ThemeChoice.values()[index] == theme, false) }
        }
    }

    private fun bind(widget: WidgetSettingsAppearance, model: WidgetSettingsAppearance.Model?) {
        if (widget.view == null) return
        val binding = WidgetSettingsAppearance.`access$getBinding$p`(widget)
        val page = pages[widget] ?: run {
            val parent = binding.l.parent as ViewGroup
            val layout = Utils.getResId("widget_settings_appearance", "layout")
            val radioId = Utils.getResId("settings_appearance_theme_light_radio", "id")
            check(layout != 0 && radioId != 0) { "Discord appearance resources unavailable" }
            fun cloneRadio(): CheckedSetting {
                // Inflate the exact target layout to retain cs_view_type, style,
                // spacing and Discord's text appearance on both added rows.
                val root = LayoutInflater.from(parent.context).inflate(layout, parent, false)
                val row = root.findViewById<CheckedSetting>(radioId)
                (row.parent as ViewGroup).removeView(row)
                row.id = View.generateViewId()
                return row
            }
            val ash = cloneRadio()
            val onyx = cloneRadio()
            parent.addView(ash, parent.indexOfChild(binding.j))
            parent.addView(onyx, parent.indexOfChild(binding.j) + 1)
            Page(listOf(binding.l, ash, binding.j, onyx)).also { pages[widget] = it }
        }
        if (model != null) page.model = model
        binding.m.visibility = View.GONE
        page.radios.forEachIndexed { index, radio ->
            val theme = ThemeChoice.values()[index]
            radio.setText(theme.label)
            radio.g(selected == theme, false)
            // CheckedSetting.e requires a non-null listener. Replace Discord's
            // easter-egg handler with selection of this row's actual theme.
            radio.e { choose(theme) }
            radio.setOnCheckedListener { checked -> if (checked) choose(theme) }
        }
    }

    override fun stop(context: Context) {
        sync?.close()
        sync = null
        patcher.unpatchAll()
        palette?.clearCaches()
        palette = null
        themeNames.clear()
        pages.forEach { (widget, page) ->
            page.radios.forEach { it.setOnCheckedListener(null) }
            listOf(page.radios[1], page.radios[3]).forEach { (it.parent as? ViewGroup)?.removeView(it) }
            if (widget.view != null) {
                page.radios[2].setText("Dark")
                page.model?.let { WidgetSettingsAppearance.`access$configureUI`(widget, it) }
            }
        }
        pages.clear()
        // Leave a native theme the unmodified client understands.
        val native = if (selected == ThemeChoice.LIGHT) "light" else if (selected == ThemeChoice.ONYX) "pureEvil" else "dark"
        StoreStream.getUserSettingsSystem().setTheme(native, false, null)
        Utils.appActivity?.let { activity ->
            activity.window.decorView.post {
                if (!activity.isFinishing) activity.recreate()
            }
        }
    }
}
