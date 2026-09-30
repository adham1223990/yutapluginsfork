package com.github.yutaplug.customrpc

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.Http
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.Utils
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityAssets
import com.discord.api.activity.ActivityType
import com.discord.api.presence.ClientStatus
import com.discord.app.AppActivity
import com.discord.gateway.GatewaySocket
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelUserSettings
import com.discord.stores.StoreConnectionOpen
import com.discord.stores.StoreGatewayConnection
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserPresence
import com.discord.utilities.icon.IconUtils
import java.lang.ref.WeakReference
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

@AliucordPlugin
class CustomRPC : Plugin() {
    companion object {
        const val ENABLED = "enabled"
        const val ACTIVITY_TYPE = "activityType"
        const val ACTIVITY_FLAGS = "activityFlags"
        const val APPLICATION_ID = "applicationId"
        const val NAME = "name"
        const val DETAILS = "details"
        const val STATE = "state"
        const val LARGE_IMAGE = "largeImage"
        const val LARGE_IMAGE_TEXT = "largeImageText"
        const val SMALL_IMAGE = "smallImage"
        const val SMALL_IMAGE_TEXT = "smallImageText"
        const val LARGE_IMAGE_URL = "largeImageUrl"
        const val SMALL_IMAGE_URL = "smallImageUrl"
        val types =
            listOf(
                ActivityType.PLAYING,
                ActivityType.STREAMING,
                ActivityType.LISTENING,
                ActivityType.WATCHING,
                ActivityType.COMPETING,
            )

        fun typeLabel(type: ActivityType) = when (type) {
            ActivityType.STREAMING -> "Streaming"
            ActivityType.LISTENING -> "Listening to"
            ActivityType.WATCHING -> "Watching"
            ActivityType.COMPETING -> "Competing in"
            else -> "Playing"
        }

        fun publicImageUrl(value: String?): String? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            return text.takeIf {
                uri.scheme?.lowercase() in listOf("http", "https") &&
                    !uri.host.isNullOrEmpty() &&
                    uri.userInfo == null
            }
        }
    }

    @Volatile private var running = false

    @Volatile private var updating = false
    private var createdAt = System.currentTimeMillis()
    private var sharingEnabled = false
    private var lastActivity: WeakReference<AppActivity>? = null
    private var owned: Activity? = null
    private val displaced = mutableMapOf<ActivityType, Activity?>()
    private val imageLock = Any()
    private var imageGeneration = 0
    private val imagePaths = mutableMapOf<String, String>()
    private val imageFailures = mutableMapOf<String, Long>()
    private val imageRequests = mutableSetOf<String>()

    init {
        settingsTab = SettingsTab(CustomRPCSettings::class.java, SettingsTab.Type.PAGE).withArgs(settings, this)
    }

    fun isEnabled() = settings.getBool(ENABLED, false)

    fun activityType(): ActivityType = runCatching {
        ActivityType.valueOf(settings.getString(ACTIVITY_TYPE, "PLAYING") ?: "PLAYING")
    }.getOrDefault(ActivityType.PLAYING).takeIf {
        it in
            types
    }
        ?: ActivityType.PLAYING

    fun flags() = settings.getInt(ACTIVITY_FLAGS, 257)

    fun value(key: String) = settings.getString(key, "")?.trim().orEmpty()

    override fun start(context: Context) {
        running = true
        createdAt = System.currentTimeMillis()
        // FragmentActivity saves its fragments through ComponentActivity's saved-state registry.
        // Plugin fragments cannot be restored before Aliucord loads the plugin after process death.
        patcher.patch(
            ComponentActivity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java),
            PreHook { frame ->
                val activity = frame.thisObject as? AppActivity ?: return@PreHook
                val manager = activity.supportFragmentManager
                if (manager.isDestroyed) return@PreHook
                manager.fragments.filterIsInstance<CustomRPCSettings>().forEach { sheet ->
                    manager.beginTransaction().remove(sheet).commitNowAllowingStateLoss()
                }
            },
        )
        patcher.patch(
            IconUtils::class.java.getDeclaredMethod(
                "getAssetImage",
                Long::class.javaObjectType,
                String::class.java,
                Int::class.javaPrimitiveType,
            ),
            PreHook { frame ->
                publicImageUrl(frame.args[1] as? String)?.let { frame.result = it }
            },
        )
        patcher.patch(
            AppActivity::class.java.getDeclaredMethod("onResume"),
            Hook { frame ->
                lastActivity = WeakReference(frame.thisObject as AppActivity)
                if (isEnabled()) enableActivitySharing(frame.thisObject as AppActivity)
                refresh()
            },
        )
        for (clazz in listOf(StoreGatewayConnection::class.java, GatewaySocket::class.java)) {
            patcher.patch(
                clazz.getDeclaredMethod(
                    "presenceUpdate",
                    ClientStatus::class.java,
                    Long::class.javaObjectType,
                    List::class.java,
                    Boolean::class.javaObjectType,
                ),
                PreHook { frame ->
                    if (running && isEnabled()) {
                        val activities = (frame.args[2] as? List<*>)?.filterIsInstance<Activity>().orEmpty()
                        frame.args[2] = activities.filterNot { it.p() == activityType() } + createActivity()
                    }
                },
            )
        }
        patcher.patch(
            StoreUserPresence::class.java.getDeclaredMethod(
                "updateActivity",
                ActivityType::class.java,
                Activity::class.java,
                Boolean::class.javaPrimitiveType,
            ),
            Hook { frame ->
                if (!updating && running && isEnabled()) {
                    val type = frame.args[0] as ActivityType
                    if (type == activityType()) displaced[type] = frame.args[1] as? Activity
                    refresh()
                }
            },
        )
        for ((method, argument) in listOf(
            "handleConnectionOpen" to ModelPayload::class.java,
            "handleUserSettingsUpdate" to ModelUserSettings::class.java,
            "handleSessionsReplace" to List::class.java,
        )) {
            patcher.patch(
                StoreUserPresence::class.java.getDeclaredMethod(method, argument),
                Hook { if (!updating) refresh() },
            )
        }
        patcher.patch(StoreConnectionOpen::class.java.getDeclaredMethod("handleConnectionOpen"), Hook { refresh() })
        if (isEnabled()) enableActivitySharing(context)
        refresh()
    }

    override fun stop(context: Context) {
        running = false
        sharingEnabled = false
        patcher.unpatchAll()
        clearImageCache()
        lastActivity = null
        reconcile()
    }

    fun enableActivitySharing(context: Context?) {
        val activity = context as? AppActivity ?: lastActivity?.get()
        if (activity != null) lastActivity = WeakReference(activity)
        if (!sharingEnabled) {
            StoreStream.getUserSettings().setIsShowCurrentGameEnabled(activity, true)
            sharingEnabled = activity != null
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled && !isEnabled()) createdAt = System.currentTimeMillis()
        settings.setBool(ENABLED, enabled)
        if (!enabled) sharingEnabled = false
        if (enabled) enableActivitySharing(null)
        refresh()
    }

    fun setType(type: ActivityType) {
        if (type !in types) return
        settings.setString(ACTIVITY_TYPE, type.name)
        refresh()
    }

    fun setFlags(flags: Int) {
        settings.setInt(ACTIVITY_FLAGS, flags)
        refresh()
    }

    fun save(values: Map<String, String>) {
        val imagesChanged = listOf(APPLICATION_ID, LARGE_IMAGE_URL, SMALL_IMAGE_URL).any {
            values[it]?.trim() !=
                value(it)
        }
        values.forEach { (key, value) -> settings.setString(key, value.trim()) }
        if (imagesChanged) clearImageCache()
        refresh()
    }

    private fun refresh() {
        if (running && isEnabled()) requestImages()
        reconcile()
    }

    // Read the latest settings inside the dispatcher, so queued edits cannot resurrect stale activities.
    private fun reconcile() {
        StoreStream.getDispatcherYesThisIsIntentional().schedule {
            updating = true
            try {
                val store = StoreStream.getPresences()
                val next = if (running && isEnabled()) createActivity() else null
                val previous = owned
                if (previous != null && previous.p() != next?.p()) {
                    val current = store
                        .`getLocalPresence$app_productionGoogleRelease`()
                        .activities
                        .orEmpty()
                        .firstOrNull {
                            it.p() ==
                                previous.p()
                        }
                    if (current == previous) store.updateActivity(previous.p(), displaced[previous.p()], true)
                    displaced.remove(previous.p())
                    owned = null
                }
                if (next != null) {
                    val current = store
                        .`getLocalPresence$app_productionGoogleRelease`()
                        .activities
                        .orEmpty()
                        .firstOrNull { it.p() == next.p() }
                    if (owned == null || current != owned) displaced[next.p()] = current
                    store.updateActivity(next.p(), next, false)
                    owned = next
                }
                // StoreUserPresence marks its snapshot changed and sends the presence itself.
            } catch (error: Exception) {
                logger.error("Failed to update CustomRPC presence", error)
            } finally {
                updating = false
            }
        }
    }

    private fun createActivity(): Activity {
        val appId = value(APPLICATION_ID).toLongOrNull()?.takeIf { it > 0 }

        fun optional(key: String) = value(key).takeIf { it.isNotEmpty() }

        fun image(key: String, urlKey: String): String? {
            val url = publicImageUrl(optional(urlKey)) ?: return optional(key).takeIf { appId != null }
            return synchronized(imageLock) { imagePaths["$appId:$url"] } ?: url
        }
        val large = image(LARGE_IMAGE, LARGE_IMAGE_URL)
        val small = image(SMALL_IMAGE, SMALL_IMAGE_URL)
        val assets = if (large != null ||
            small != null
        ) {
            ActivityAssets(
                large,
                optional(LARGE_IMAGE_TEXT).takeIf { large != null },
                small,
                optional(SMALL_IMAGE_TEXT).takeIf {
                    small !=
                        null
                },
            )
        } else {
            null
        }
        return Activity(
            optional(NAME) ?: "Custom RPC",
            activityType(),
            null,
            createdAt,
            null,
            appId,
            optional(DETAILS),
            optional(STATE),
            null,
            null,
            assets,
            flags(),
            null,
            null,
            null,
            null,
            null,
            null,
        )
    }

    private fun clearImageCache() = synchronized(imageLock) {
        imageGeneration++
        imagePaths.clear()
        imageFailures.clear()
        imageRequests.clear()
    }

    private fun requestImages() {
        val id = value(APPLICATION_ID).toLongOrNull()?.takeIf { it > 0 } ?: return
        val urls = listOf(LARGE_IMAGE_URL, SMALL_IMAGE_URL).mapNotNull { publicImageUrl(value(it)) }.distinct()
        val generation: Int
        val pending: List<String>
        synchronized(imageLock) {
            generation = imageGeneration
            pending = urls.filter { url ->
                val key = "$id:$url"
                key !in imagePaths &&
                    System.currentTimeMillis() - (imageFailures[key] ?: 0) >= 60_000 &&
                    imageRequests.add(key)
            }
        }
        if (pending.isEmpty()) return
        Utils.threadPool.execute {
            val resolved = mutableMapOf<String, String>()
            try {
                Http.Request.newDiscordRNRequest("/applications/$id/external-assets", "POST").use { request ->
                    request.setRequestTimeout(10_000)
                    request.setHeader("Content-Type", "application/json")
                    val response = request.executeWithBody(JSONObject().put("urls", JSONArray(pending)).toString())
                    check(response.ok()) { "External images: HTTP ${response.statusCode}" }
                    val assets = JSONArray(response.text())
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val url = asset.optString("url").trim().ifEmpty {
                            if (assets.length() ==
                                pending.size
                            ) {
                                pending[i]
                            } else {
                                ""
                            }
                        }
                        val path = asset
                            .optString(
                                "external_asset_path",
                            ).trim()
                            .removePrefix("https://media.discordapp.net/")
                            .trimStart('/')
                        if (url in pending &&
                            path.isNotEmpty()
                        ) {
                            resolved["$id:$url"] = if (path.startsWith("mp:")) path else "mp:$path"
                        }
                    }
                }
            } catch (error: Exception) {
                logger.error("Failed to proxy CustomRPC images", error)
            }
            synchronized(imageLock) {
                if (generation == imageGeneration && running) {
                    imagePaths.putAll(resolved)
                    pending.forEach { url ->
                        val key = "$id:$url"
                        imageRequests.remove(key)
                        if (key !in
                            resolved
                        ) {
                            imageFailures[key] = System.currentTimeMillis()
                        } else {
                            imageFailures.remove(key)
                        }
                    }
                    if (resolved.isNotEmpty()) Utils.mainThread.post { if (running && isEnabled()) refresh() }
                }
            }
        }
    }
}
