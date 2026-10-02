package com.github.yutaplug.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.Hook
import com.aliucord.utils.ReflectUtils
import com.discord.stores.StoreStream
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.fcm.NotificationRenderer
import com.discord.utilities.persister.Persister

internal object DeviceNotifications {
    @Suppress("UNCHECKED_CAST")
    private fun persister(): Persister<NotificationClient.SettingsV2> =
        ReflectUtils.getField(StoreStream.getNotifications(), "notificationSettings")
            as Persister<NotificationClient.SettingsV2>

    fun current(): NotificationClient.SettingsV2 = persister().get()

    fun wake(enabled: Boolean) {
        // Legacy Discord stores this flag but provides neither a switch nor wake behavior.
        persister().getAndSet(true) {
            it.copy(
                it.isEnabled,
                it.isEnabledInApp,
                enabled,
                it.isDisableBlink,
                it.isDisableSound,
                it.isDisableVibrate,
                it.token,
                it.locale,
                it.sendBlockedChannels,
            )
        }
    }

    fun start(patcher: PatcherAPI) {
        // Use the public wrapper called when the notification is actually posted,
        // rather than display(), which only begins asynchronous image loading.
        patcher.patch(
            NotificationRenderer::class.java,
            "access\$displayNotification",
            arrayOf(
                NotificationRenderer::class.java,
                Context::class.java,
                Int::class.javaPrimitiveType!!,
                NotificationCompat.Builder::class.java,
            ),
            Hook { frame ->
                if (current().isWake) wakeScreen(frame.args[1] as Context)
            },
        )
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (power.isInteractive) return
        // Android/device policy may restrict waking the screen; it must never break delivery.
        runCatching {
            power
                .newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Notifications:notification",
                ).apply {
                    setReferenceCounted(false)
                    acquire(1_000L)
                }
        }
    }
}
