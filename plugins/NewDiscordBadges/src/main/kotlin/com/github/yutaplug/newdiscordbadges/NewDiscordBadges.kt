package com.github.yutaplug.newdiscordbadges

import android.content.Context
import android.util.Base64
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.rn.user.RNUserProfile
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.RNSuperProperties
import com.aliucord.utils.RxUtils
import com.discord.api.user.UserProfile
import com.discord.databinding.UserProfileHeaderBadgeBinding
import com.discord.models.user.User
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.discord.widgets.user.Badge
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet

@AliucordPlugin
@Suppress("unused")
class NewDiscordBadges : Plugin() {
    private val profiles = WeakIdentityMap<UserProfile, List<ProfileBadge>>()
    private val badgeImages = WeakIdentityMap<Badge, List<String>>()
    private val images by lazy { BadgeImages(logger) }
    private val adapterField by lazy {
        UserProfileHeaderView::class.java.getDeclaredField("badgesAdapter").apply { isAccessible = true }
    }
    private val dataField by lazy {
        SimpleRecyclerAdapter::class.java.getDeclaredField("data").apply { isAccessible = true }
    }
    private val bindingField by lazy {
        UserProfileHeaderView.BadgeViewHolder::class.java.getDeclaredField("binding").apply { isAccessible = true }
    }

    @Volatile
    private var generation = 0

    override fun start(context: Context) {
        generation++
        patchProfileRequest()

        // Like Aliucord PR #796, replace flag-based badges with the API's visible list.
        patcher.patch(
            Badge.Companion::class.java,
            "getBadgesForUser",
            arrayOf(
                User::class.java,
                UserProfile::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Context::class.java,
            ),
            after { frame ->
                val profile = frame.args[1] as? UserProfile ?: return@after
                val badges = visibleBadges(profile)
                val existing = existingBadges(frame.result)
                frame.result = merge(existing, badges, frame.args[4] as Context)
            },
        )

        // Older core plugins append Discord badges directly after updateViewState.
        // Reconcile once after those hooks, preserving Aliucord/custom badges.
        patcher.patch(
            UserProfileHeaderView::class.java,
            "updateViewState",
            arrayOf(UserProfileHeaderViewModel.ViewState.Loaded::class.java),
            after { frame ->
                val header = frame.thisObject as UserProfileHeaderView
                val state = frame.args[0] as UserProfileHeaderViewModel.ViewState.Loaded
                val adapter = adapterField.get(header) as RecyclerView.Adapter<*>
                val existing = existingBadges(dataField.get(adapter))
                val merged = merge(existing, visibleBadges(state.userProfile), header.context)
                if (existing != merged) {
                    dataField.set(adapter, merged)
                    adapter.notifyDataSetChanged()
                }
            },
        )

        patcher.patch(
            UserProfileHeaderView.BadgeViewHolder::class.java,
            "bind",
            arrayOf(Badge::class.java),
            PreHook { frame ->
                val binding = bindingField.get(frame.thisObject) as UserProfileHeaderBadgeBinding
                images.unbind(binding.b)
            },
        )
        patcher.patch(
            UserProfileHeaderView.BadgeViewHolder::class.java,
            "bind",
            arrayOf(Badge::class.java),
            after { frame ->
                val badge = frame.args[0] as Badge
                if (!isOurs(badge)) return@after
                val binding = bindingField.get(frame.thisObject) as UserProfileHeaderBadgeBinding
                val image = binding.b
                image.visibility = View.VISIBLE
                image.contentDescription = badge.tooltip
                image.setOnClickListener { Utils.showToast(badge.tooltip.toString()) }
                images.bind(image, badgeImages[badge] ?: Collections.emptyList())
            },
        )
    }

    private fun patchProfileRequest() {
        patcher.patch(
            RestAPI::class.java,
            "userProfileGet",
            arrayOf(Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Long::class.javaObjectType),
            before { frame ->
                val userId = frame.args[0] as Long
                val mutualGuilds = frame.args[1] as Boolean
                val guildId = frame.args[2] as? Long
                val requestGeneration = generation
                frame.result = RxUtils.create<UserProfile> { subscriber ->
                    // Explicitly dispatch: some consumers subscribe from the UI thread.
                    Utils.threadPool.execute {
                        if (subscriber.isUnsubscribed) return@execute
                        if (generation != requestGeneration) {
                            subscriber.onCompleted()
                            return@execute
                        }
                        try {
                            val route = "/users/$userId/profile?with_mutual_guilds=$mutualGuilds" +
                                (guildId?.let { "&guild_id=$it" } ?: "")
                            Http.Request.newDiscordRNRequest(route).setRequestTimeout(10_000).use { request ->
                                request.setHeader("User-Agent", "Discord-Android/343012")
                                request.setHeader("X-Super-Properties", superProperties())
                                request.setHeader("X-Discord-Features", "user-profile")
                                RestAPI.AppHeadersProvider.INSTANCE.fingerprint?.let {
                                    request.setHeader("X-Fingerprint", it)
                                }
                                val response = request.execute()
                                if (!response.ok()) {
                                    if (response.statusCode != 404) throw Http.HttpException(request, response)
                                } else {
                                    val json = response.text()
                                    val profile = GsonUtils.run { gsonRestApi.fromJson(json, RNUserProfile::class.java) }
                                    val raw = GsonUtils.run { gson.fromJson(json, Any::class.java) }
                                    val badges = ProfileBadges.parse(raw)
                                    if (!subscriber.isUnsubscribed && generation == requestGeneration) {
                                        profiles[profile] = badges
                                        subscriber.onNext(profile)
                                    }
                                }
                            }
                            if (!subscriber.isUnsubscribed) subscriber.onCompleted()
                        } catch (error: Exception) {
                            if (!subscriber.isUnsubscribed) subscriber.onError(error)
                        }
                    }
                }
            },
        )
    }

    private fun superProperties(): String {
        val properties = JSONObject(RNSuperProperties.superProperties.toString())
            .put("client_version", "343.12 - Stable")
            .put("client_build_number", 343012)
            .put("release_channel", "stable")
        return Base64.encodeToString(properties.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }

    // Xposed runs after callbacks in reverse order. A high priority makes our
    // reconciliation run LAST, including after the older DiscordBadges core hook.
    private fun after(callback: (XC_MethodHook.MethodHookParam) -> Unit): XC_MethodHook =
        object : XC_MethodHook(10_000) {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    callback(param)
                } catch (error: Exception) {
                    logger.error("Could not reconcile profile badges", error)
                }
            }
        }

    // Run ahead of the newer RNAPI replacement if PR #796 has been installed.
    private fun before(callback: (XC_MethodHook.MethodHookParam) -> Unit): XC_MethodHook =
        object : XC_MethodHook(10_000) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                callback(param)
            }
        }

    private fun visibleBadges(profile: UserProfile): List<ProfileBadge> = profiles[profile]
        ?: ProfileBadges.parse(GsonUtils.run { gson.fromJson(gsonRestApi.toJson(profile), Any::class.java) })

    private fun isOurs(badge: Badge): Boolean {
        val text = badge.text?.toString() ?: return false
        return ProfileBadges.hasPrefix(text, ProfileBadges.MARKER)
    }

    private fun existingBadges(value: Any?): List<Badge> {
        val result = ArrayList<Badge>()
        if (value is List<*>) for (entry in value) if (entry is Badge) result.add(entry)
        return result
    }

    private fun imageIdentity(url: String): String {
        var query = 0
        while (query < url.length && url[query] != '?') query++
        return if (query == url.length) url else url.substring(0, query)
    }

    private fun merge(existing: List<Badge>, visible: List<ProfileBadge>, context: Context): MutableList<Badge> {
        val apiImages = HashSet<String>()
        for (badge in visible) for (url in badge.images) apiImages.add(imageIdentity(url))
        val custom = ArrayList<Badge>()
        for (badge in existing) {
            val url = badge.objectType
            if (!isOurs(badge) && (url == null || !apiImages.contains(imageIdentity(url))) &&
                !isDiscordBadge(badge, context) && !custom.contains(badge)
            ) custom.add(badge)
        }
        val result = ArrayList<Badge>()
        var index = visible.size - 1
        while (index >= 0) {
            val badge = visible[index--]
            // Null objectType prevents the old core image loader from issuing duplicate,
            // unbounded downloads or writing into a recycled holder after our loader.
            val rendered = Badge(0, ProfileBadges.MARKER + badge.id + ":" + badge.images[0], badge.description, false, null)
            badgeImages[rendered] = badge.images
            result.add(rendered)
        }
        result.addAll(custom)
        return result
    }

    private fun isDiscordBadge(badge: Badge, context: Context): Boolean {
        val url = badge.objectType.orEmpty()
        if (startsWith(url, "https://cdn.discordapp.com/badge-icons/") ||
            startsWith(url, "https://cdn.discordapp.com/assets/mana/asset-library/generated/")
        ) return true
        if (badge.icon == 0) return false
        val name = try {
            context.resources.getResourceEntryName(badge.icon)
        } catch (_: android.content.res.Resources.NotFoundException) {
            return false
        }
        return startsWith(name, "ic_profile_badge_") || startsWith(name, "ic_hypesquad_house")
    }

    private fun startsWith(value: String, prefix: String) = ProfileBadges.hasPrefix(value, prefix)

    override fun stop(context: Context) {
        generation++
        patcher.unpatchAll()
        profiles.clear()
        badgeImages.clear()
        images.clear()
    }
}
