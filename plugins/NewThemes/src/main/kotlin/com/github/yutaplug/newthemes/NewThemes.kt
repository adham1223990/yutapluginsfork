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
import com.discord.models.domain.ModelUserSettings
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserSettingsSystem
import com.discord.views.CheckedSetting
import com.discord.widgets.settings.WidgetSettingsAppearance
import de.robv.android.xposed.XposedBridge
import java.util.WeakHashMap

@AliucordPlugin
class NewThemes : Plugin() {
    private class Page(val radios: List<CheckedSetting>, var model: WidgetSettingsAppearance.Model? = null)
    private val pages = WeakHashMap<WidgetSettingsAppearance, Page>()
    private var sync: ThemeSync? = null
    private var palette: ThemePalette? = null
    private val activityThemes = WeakHashMap<AppActivity, ThemeChoice>()
    private val comparingTheme = ThreadLocal<String>()
    private var running = false
    @Volatile private var selected = ThemeChoice.ASH

    override fun start(context: Context) {
        val store = StoreStream.getUserSettingsSystem()
        selected = ThemeChoice.fromNative(store.theme)
        running = true
        val bridge = ThemeSync(context.applicationContext, ::applyTheme) { message, error -> logger.warn(message, error) }
        sync = bridge
        try {
            palette = ThemePalette { selected }.also { it.start(context, patcher) }
            val themeName = Utils.getResId("theme_name", "attr")
            check(themeName != 0) { "Discord theme name attribute unavailable" }
            // Own palette refreshes instead of relying on synthetic native theme names.
            patcher.patch(AppActivity::class.java.getDeclaredMethod("onCreate", android.os.Bundle::class.java), PreHook {
                activityThemes[it.thisObject as AppActivity] = selected
            })
            patcher.patch(AppActivity::class.java.getDeclaredMethod("onResume"), PreHook {
                refreshActivity(it.thisObject as AppActivity)
            })
            activityThemes[Utils.appActivity] = selected
            patcher.patch(Resources.Theme::class.java.getDeclaredMethod(
                "resolveAttribute", Int::class.javaPrimitiveType, TypedValue::class.java, Boolean::class.javaPrimitiveType,
            ), Hook {
                if (it.result == true && it.args[0] == themeName) {
                    comparingTheme.get()?.let { name ->
                        (it.args[1] as TypedValue).string = name
                    }
                }
            })
            val callback = WidgetSettingsAppearance::class.java.classLoader!!.loadClass(
                "com.discord.widgets.settings.WidgetSettingsAppearance\$onViewBoundOrOnResume\$1",
            )
            // Native configureUI replaces listeners after every store emission.
            // Hook the actual target, including callers that ART has inlined.
            callback.declaredMethods.filter { it.name == "invoke" }.forEach(XposedBridge::deoptimizeMethod)
            XposedBridge.deoptimizeMethod(WidgetSettingsAppearance::class.java.getDeclaredMethod(
                "access\$configureUI", WidgetSettingsAppearance::class.java, WidgetSettingsAppearance.Model::class.java,
            ))
            patcher.patch(WidgetSettingsAppearance::class.java.getDeclaredMethod(
                "configureUI", WidgetSettingsAppearance.Model::class.java,
            ), Hook {
                bind(it.thisObject as WidgetSettingsAppearance, it.args[0] as WidgetSettingsAppearance.Model)
            })
            val activitySettingsCallback = AppActivity::class.java.classLoader!!.loadClass("com.discord.app.AppActivity\$c")
            activitySettingsCallback.declaredMethods.filter { it.name == "invoke" }.forEach(XposedBridge::deoptimizeMethod)
            val compareSettings = activitySettingsCallback.getDeclaredMethod("invoke", Any::class.java)
            patcher.patch(compareSettings, PreHook {
                comparingTheme.set((it.args[0] as StoreUserSettingsSystem.Settings).theme)
            })
            patcher.patch(compareSettings, Hook { comparingTheme.remove() })
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
            applyTheme(selected)
        } catch (error: Throwable) {
            bridge.close()
            sync = null
            patcher.unpatchAll()
            palette?.clearCaches()
            palette = null
            running = false
            activityThemes.clear()
            comparingTheme.remove()
            throw error
        }
    }

    private fun refreshActivity(activity: AppActivity) {
        if (!running || activity.isFinishing || activity.isDestroyed) return
        val rendered = activityThemes[activity] ?: selected.also { activityThemes[activity] = it }
        if (rendered == selected || activity.intent?.hasExtra("AC_FRAGMENT_ID") == true) return
        // Mark before posting to coalesce store emissions and onResume callbacks.
        activityThemes[activity] = selected
        activity.window.decorView.post {
            if (running && !activity.isFinishing && !activity.isDestroyed) activity.recreate()
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
        refreshActivity(Utils.appActivity)
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
        running = false
        sync?.close()
        sync = null
        patcher.unpatchAll()
        palette?.clearCaches()
        palette = null
        activityThemes.clear()
        comparingTheme.remove()
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
