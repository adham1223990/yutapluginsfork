package com.github.yutaplug.notifications

import android.content.Context
import android.view.View
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import com.discord.widgets.settings.WidgetSettings

@AliucordPlugin
class Notifications : Plugin() {
    private var preferences: NotificationPreferences? = null
    private var badges: BadgePreferences? = null

    override fun start(context: Context) {
        val store = NotificationPreferences()
        preferences = store
        val badgeStore = BadgePreferences()
        badges = badgeStore
        badgeStore.start(patcher)
        DeviceNotifications.start(patcher)
        // Hook the public click callback rather than an inlineable utility method.
        // This also intercepts the notification row on an already open settings page.
        val callback = WidgetSettings::class.java.classLoader!!.loadClass(
            "com.discord.widgets.settings.WidgetSettings\$onViewBound\$\$inlined\$with\$lambda\$5",
        )
        patcher.patch(
            callback,
            "onClick",
            arrayOf(View::class.java),
            PreHook { frame ->
                val row = frame.args[0] as? View ?: return@PreHook
                Utils.openPageWithProxy(row.context, NotificationsPage(store, badgeStore))
                frame.result = null
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        preferences?.close()
        preferences = null
        badges?.close()
        badges = null
    }
}
